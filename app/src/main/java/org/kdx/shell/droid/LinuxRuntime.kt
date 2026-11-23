package org.kdx.shell.droid

import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * The X display Linux apps draw on: TigerVNC's Xvnc running inside DroidSystem, shown by KDX's
 * Linux Display window through [RfbClient]. A small window manager (openbox) gives apps title bars.
 */
class LinuxDisplay(
    val sys: DebianSystem,
    val display: Int = 1,
    /** App mode: one app per display, maximized without decorations (the KDX window supplies title bar and icon). */
    val appMode: Boolean = false,
    /** Desktop mode: a whole desktop session (KDE Plasma) brings its own window manager, so no Openbox. */
    val desktop: Boolean = false,
) {
    val port get() = 5900 + display
    @Volatile private var server: Process? = null
    @Volatile private var wm: Process? = null
    val log = java.util.concurrent.ConcurrentLinkedDeque<String>()

    val isSetUp get() = sys.hasCommand("usr/bin/Xvnc")

    fun isRunning(): Boolean = server?.isAlive == true && portOpen()

    private fun portOpen() = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess

    /** Marks every process started for this display (server, window manager, app) so they can all be stopped. */
    @Volatile var tag = "d$display-${System.nanoTime()}"
        private set

    /** The Flatpak app shown on this display, if any. */
    @Volatile var appId: String? = null

    /** Extra variables for everything on this display (e.g. KDE Plasma's interface scale), on top of [env]'s defaults. */
    @Volatile var extraEnv: Map<String, String> = emptyMap()

    val env get() = baseEnv + extraEnv

    private val baseEnv get() = mapOf(
        "DISPLAY" to ":$display", "KDX_TAG" to tag, "XDG_RUNTIME_DIR" to "/tmp/runtime-root", "NO_AT_BRIDGE" to "1",
        // abstract socket: no file path, so it works the same in DroidSystem and in every Flatpak sandbox
        "DBUS_SESSION_BUS_ADDRESS" to SESSION_BUS,
        // a system bus too: some apps (e.g. the real Bazaar) crash without one
        "DBUS_SYSTEM_BUS_ADDRESS" to SYSTEM_BUS,
        // sound goes to DroidSystem's PulseAudio server (see LinuxAudio)
        "PULSE_SERVER" to LinuxAudio.PULSE_SERVER,
        // X11 everywhere: there is no Wayland compositor
        "GDK_BACKEND" to "x11", "QT_QPA_PLATFORM" to "xcb", "SDL_VIDEODRIVER" to "x11", "CLUTTER_BACKEND" to "x11",
        "ELECTRON_OZONE_PLATFORM_HINT" to "x11", "MOZ_ENABLE_WAYLAND" to "0",
        // software rendering (llvmpipe); GTK4 draws with cairo
        "LIBGL_ALWAYS_SOFTWARE" to "1", "GSK_RENDERER" to "cairo",
        // touch-friendly toolkits
        "GDK_SCALE" to "1", "QT_AUTO_SCREEN_SCALE_FACTOR" to "0",
        // KDE's Qt integration (Breeze style, icons, KDE file dialogs) once KDE Plasma is installed; ignored otherwise
        "QT_QPA_PLATFORMTHEME" to "kde",
        // Libraries that start their own bubblewrap sandbox fail under proot (no kernel namespaces on Android):
        // glycin = GNOME's image loader (every recent GNOME app that shows pictures), WebKitGTK web processes.
        "GLYCIN_DISABLE_SANDBOX" to "1",
        // Firefox: its seccomp/namespace sandboxes clash with Android's app restrictions under proot and take
        // the browser down; turn them off the way Termux/proot-distro users do
        "MOZ_DISABLE_CONTENT_SANDBOX" to "1", "MOZ_DISABLE_GMP_SANDBOX" to "1", "MOZ_DISABLE_RDD_SANDBOX" to "1",
        "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX" to "1", "MOZ_DISABLE_UTILITY_SANDBOX" to "1", "MOZ_DISABLE_GPU_SANDBOX" to "1",
        "MOZ_DISABLE_VR_SANDBOX" to "1", "MOZ_FAKE_NO_SANDBOX" to "1", "MOZ_CRASHREPORTER_DISABLE" to "1",
        // Fission runs one process per web site; Android 12+ kills apps' extra child processes and phones are
        // short on memory, so keep Firefox to a few processes
        "MOZ_FORCE_DISABLE_FISSION" to "1",
        // never hand over to (or wait for) another running Firefox: each window is its own instance
        "MOZ_NO_REMOTE" to "1",
        // a force-closed Firefox would otherwise ask to start in "Troubleshoot Mode" next time
        "MOZ_DISABLE_AUTO_SAFE_MODE" to "1",
        "WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS" to "1", "WEBKIT_FORCE_SANDBOX" to "0",
    )

    /** One D-Bus session bus shared by all displays (many GTK/Qt/Flatpak apps need it). */
    private fun ensureSessionBus() {
        if (!sys.hasCommand("usr/bin/dbus-daemon")) return
        synchronized(LinuxDisplay::class.java) {
            if (sharedBus?.isAlive == true) return
            sys.run("[ -s /etc/machine-id ] || dbus-uuidgen --ensure=/etc/machine-id; mkdir -p /var/lib/dbus; ln -sf /etc/machine-id /var/lib/dbus/machine-id")
            // if a bus from an earlier run still owns the address, this one exits and the old one keeps serving
            sharedBus = sys.command(listOf("dbus-daemon", "--session", "--nofork", "--nopidfile", "--address=$SESSION_BUS"))
                .start().also { drain(it, "dbus") }
            if (systemBus?.isAlive != true) {
                // the stock system bus wants to switch to the messagebus user, which proot can't; this one doesn't
                File(sys.root, SYSTEM_BUS_CONF).apply { parentFile?.mkdirs() }.writeText(SYSTEM_BUS_XML)
                systemBus = sys.command(listOf("dbus-daemon", "--config-file=/$SYSTEM_BUS_CONF", "--nofork", "--nopidfile"))
                    .start().also { drain(it, "dbus-system") }
            }
        }
    }

    /** The active window's title (what KDX shows in its title bar), or null if unknown. */
    fun windowTitle(): String? {
        if (!sys.hasCommand("usr/bin/xdotool")) return null
        val (rc, out) = runCatching { sys.query("DISPLAY=:$display xdotool getactivewindow getwindowname 2>/dev/null || DISPLAY=:$display xdotool search --onlyvisible --name . getwindowname 2>/dev/null | head -n1") }.getOrNull() ?: return null
        return out.trim().lines().lastOrNull()?.takeIf { rc == 0 && it.isNotBlank() }
    }

    @Synchronized
    fun start(width: Int, height: Int) {
        if (isRunning()) return
        if (!isSetUp) throw IOException("The display server isn't installed yet – run display-setup")
        stop()
        // X needs a sticky, world-writable socket dir; clear stale locks from a previous run
        val x11 = File(sys.root, "tmp/.X11-unix").apply { mkdirs() }
        runCatching { Files.setPosixFilePermissions(x11.toPath(), PosixFilePermissions.fromString("rwxrwxrwx")) }
        File(x11, "X$display").delete(); File(sys.root, "tmp/.X$display-lock").delete()
        File(sys.root, "tmp/runtime-root").mkdirs()
        val w = (width / 8 * 8).coerceIn(640, 3840); val h = height.coerceIn(400, 2160)
        ensureSessionBus()
        server = sys.command(
            listOf("Xvnc", ":$display", "-geometry", "${w}x$h", "-depth", "24", "-SecurityTypes", "None",
                "-localhost", "-rfbport", "$port", "-AlwaysShared", "-ac", "-nolisten", "tcp", "-AcceptSetDesktopSize",
                // up to 60 frames a second; CompareFB=0 skips re-comparing pixels for every update (saves CPU)
                "-FrameRate", "60", "-CompareFB", "0"),
        ).start().also { drain(it, "Xvnc") }
        val deadline = System.currentTimeMillis() + 20_000
        while (!portOpen()) {
            if (server?.isAlive != true) throw IOException("Display server exited:\n" + log.takeLast(8).joinToString("\n"))
            if (System.currentTimeMillis() > deadline) throw IOException("Display server didn't start in time")
            Thread.sleep(150)
        }
        if (!desktop && sys.hasCommand("usr/bin/openbox")) {
            val cfg = File(sys.root, APP_RC)
            wm = if (appMode && cfg.isFile) launchRaw("openbox --config-file /$APP_RC", emptyMap()) else launchRaw("openbox", emptyMap())
        }
    }

    /** Waits until the X server accepts connections on its socket (what X programs connect to). */
    fun waitForX(timeoutMs: Long = 10_000): Boolean {
        val sock = File(sys.root, "tmp/.X11-unix/X$display")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (server?.isAlive != true) return false
            if (sock.exists() && portOpen()) return true
            Thread.sleep(100)
        }
        return false
    }

    /** True while any program other than the X server and window manager runs on this display. */
    @Volatile var appProcess: Process? = null
        set(v) { field = v; appStartedAt = System.currentTimeMillis() }
    @Volatile var appStartedAt = 0L
        private set
    /** Starts the app again (set by whoever launched it). */
    @Volatile var relaunch: (() -> Process)? = null

    /** The app's own output lines (no X server / bus chatter), for the "app stopped" panel. */
    fun appLog(): List<String> = log.filter { !it.startsWith("Xvnc:") && !it.startsWith("dbus:") && !it.startsWith("openbox:") }

    /** Starts a GUI program on this display. */
    fun launch(cmd: String, extraEnv: Map<String, String> = emptyMap()): Process =
        launchRaw(cmd, extraEnv).also { if (appMode) FlatpakRunner.gtkSettings(File(sys.root, "root/.config")) }

    private fun launchRaw(cmd: String, extraEnv: Map<String, String>): Process =
        sys.command(listOf("/bin/bash", "-lc", "exec $cmd"), "/root", env + extraEnv).start().also { it.outputStream.close(); drain(it, cmd.substringBefore(' ')) }

    fun stop() {
        // ask the programs to quit first (Firefox saves its session), then force whatever remains; the proot
        // processes alone aren't enough – the programs they ran can survive them
        runCatching { Procs.stopEnv("KDX_TAG=$tag") }
        appProcess?.destroy(); wm?.destroy(); server?.destroy()
        appProcess = null; wm = null; server = null
        runCatching { Procs.killEnv("KDX_TAG=$tag") }
        // the server is gone: free its display number (see freeDisplay)
        File(sys.root, "tmp/.X11-unix/X$display").delete(); File(sys.root, "tmp/.X$display-lock").delete()
        tag = "d$display-${System.nanoTime()}" // fresh tag for a restart on this display
    }

    companion object {
        /**
         * A free X display number for a new window (2–63), or null if every one is really in use.
         *
         * X marks a display as taken with /tmp/.X<n>-lock (holding the server's PID) and /tmp/.X11-unix/X<n>. When KDX
         * crashes or Android ends it, those stay behind; never cleaning them up used every number in the end, and
         * then KDX crashed each time it started KDE Plasma (the screen blinking black). So locks whose process is gone
         * (or isn't a DroidSystem program any more – PIDs get reused) are removed first.
         */
        fun freeDisplay(root: File, used: Set<Int>, alive: (Int) -> Boolean = ::isDroidProcess): Int? {
            for (n in 2..63) {
                if (n in used) continue
                val lock = File(root, "tmp/.X$n-lock")
                if (lock.exists()) {
                    val pid = runCatching { lock.readText().trim().toInt() }.getOrNull()
                    if (pid != null && alive(pid)) continue
                    lock.delete(); File(root, "tmp/.X11-unix/X$n").delete()
                    if (lock.exists()) continue
                }
                return n
            }
            return null
        }

        /** True if [pid] is running and is a DroidSystem program (they all carry HOSTNAME=droidsystem). */
        fun isDroidProcess(pid: Int): Boolean {
            if (!File("/proc/$pid").exists()) return false
            val env = runCatching { String(File("/proc/$pid/environ").readBytes()) }.getOrNull() ?: return true // can't tell: assume in use
            return env.split('\u0000').any { it == "HOSTNAME=droidsystem" }
        }

        /** Openbox config for app windows: no decorations, normal windows maximized (see DebianSystem.applyDroidLayer). */
        const val APP_RC = "etc/xdg/openbox/kdx-app.xml"
        const val SESSION_BUS = "unix:abstract=/kdx/dbus-session"
        const val SYSTEM_BUS = "unix:abstract=/kdx/dbus-system"
        const val SYSTEM_BUS_CONF = "etc/dbus-1/kdx-system.conf"
        /** A system bus for DroidSystem: same permissions as a session bus (one user), no service activation. */
        val SYSTEM_BUS_XML = """<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>system</type>
  <listen>$SYSTEM_BUS</listen>
  <auth>EXTERNAL</auth>
  <policy context="default">
    <allow send_destination="*" eavesdrop="true"/>
    <allow eavesdrop="true"/>
    <allow own="*"/>
  </policy>
</busconfig>
"""
        @Volatile private var sharedBus: Process? = null
        @Volatile private var systemBus: Process? = null
    }

    /** Where this display's log is also saved (set by KDX for app windows), so it survives crashes. */
    @Volatile var logFile: File? = null

    /** Appends a line to the saved log file (best effort). */
    fun logToFile(line: String) {
        val f = logFile ?: return
        synchronized(this) { runCatching { f.appendText(line + "\n") } }
    }

    fun drain(p: Process, tag: String) = Thread {
        runCatching {
            p.inputStream.bufferedReader().forEachLine {
                log.add("$tag: $it"); while (log.size > 400) log.pollFirst()
                logToFile("$tag: $it")
            }
        }
    }.apply { isDaemon = true }.start()

    private fun <T> java.util.Deque<T>.takeLast(n: Int) = toList().takeLast(n)
}

/** A Flatpak app installed (per-user) inside DroidSystem. */
data class InstalledFlatpak(val id: String, val name: String, val command: String, val runtime: String, val desktopFile: File?)

/**
 * Runs Flatpak apps without bubblewrap (which needs kernel namespaces Android doesn't give apps): the app's
 * runtime becomes /usr and the app becomes /app in a separate proot, mirroring Flatpak's own layout.
 */
class FlatpakRunner(val sys: DebianSystem, private val display: LinuxDisplay) {
    private val homes get() = listOf(sys.flatpakHome, File(sys.root, "var/lib/flatpak"))
    val isSetUp get() = sys.hasCommand("usr/bin/flatpak")

    fun appDir(id: String): File? = homes.map { File(it, "app/$id/current/active") }.firstOrNull { File(it, "metadata").isFile }

    fun installed(): List<InstalledFlatpak> = homes.flatMap { h ->
        (File(h, "app").listFiles() ?: emptyArray()).mapNotNull { d ->
            val active = File(d, "current/active")
            val meta = File(active, "metadata").takeIf { it.isFile } ?: return@mapNotNull null
            val app = DroidSystem.parseIni(meta.readText())["Application"] ?: return@mapNotNull null
            val desktop = File(active, "export/share/applications/${d.name}.desktop").takeIf { it.isFile }
            val name = desktop?.let { DroidSystem.parseIni(it.readText())["Desktop Entry"]?.get("Name") } ?: d.name.substringAfterLast('.')
            InstalledFlatpak(d.name, name, app["command"] ?: "", app["runtime"] ?: "", desktop)
        }
    }.distinctBy { it.id }

    private fun runtimeDir(ref: String): File? {
        val (name, arch, branch) = ref.split('/').let { Triple(it[0], it.getOrNull(1) ?: "", it.getOrNull(2) ?: "") }
        return homes.map { File(it, "runtime/$name/$arch/$branch/active/files") }.firstOrNull { it.isDirectory }
    }

    /** Installed deploy dirs (…/active) of a runtime-type ref name, any branch, preferring [branch]. */
    private fun installedRefs(name: String, arch: String, branch: String?): List<File> =
        homes.flatMap { h -> (File(h, "runtime/$name/$arch").listFiles() ?: emptyArray()).map { File(it, "active") } }
            .filter { File(it, "files").isDirectory }
            .sortedByDescending { it.parentFile?.name == branch }

    /**
     * Flatpak extensions ([Extension …] groups in runtime/app metadata): GL drivers, codecs, themes, plugins…
     * Returns (guest mount point, host dir) pairs plus extra library dirs, the way flatpak mounts them.
     */
    fun extensionMounts(metadata: String, base: String, arch: String, defaultBranch: String): Pair<List<Pair<String, String>>, List<String>> {
        val mounts = mutableListOf<Pair<String, String>>()
        val ldPaths = mutableListOf<String>()
        DroidSystem.parseIni(metadata).filterKeys { it.startsWith("Extension ") }.forEach { (group, ext) ->
            val extName = group.removePrefix("Extension ").trim()
            val dir = ext["directory"] ?: return@forEach
            val versions = (ext["versions"] ?: ext["version"] ?: defaultBranch).split(';').filter { it.isNotBlank() }
            val subdirs = ext["subdirectories"] == "true"
            val names = if (subdirs) homes.flatMap { h -> (File(h, "runtime").listFiles() ?: emptyArray()).map { it.name } }
                .filter { it.startsWith("$extName.") && !it.endsWith(".Debug") && !it.endsWith(".Locale") || it == extName }.distinct()
            else listOf(extName)
            names.forEach { n ->
                val found = versions.firstNotNullOfOrNull { v -> installedRefs(n, arch, v).firstOrNull { it.parentFile?.name == v } }
                    ?: installedRefs(n, arch, versions.firstOrNull()).firstOrNull() ?: return@forEach
                val guest = if (subdirs && n != extName) "$base/$dir/${n.removePrefix("$extName.")}" else "$base/$dir"
                mounts += guest to File(found, "files").path
                ext["add-ld-path"]?.let { ldPaths += "$guest/$it" }
            }
        }
        return mounts to ldPaths
    }

    private val triplet get() = when (sys.arch) { "amd64" -> "x86_64-linux-gnu"; "arm64" -> "aarch64-linux-gnu"; "armhf" -> "arm-linux-gnueabihf"; else -> "i386-linux-gnu" }

    /** Builds the process that runs [id]. Throws with a readable message if something is missing. */
    fun command(id: String, args: List<String> = emptyList(), on: LinuxDisplay = display, commandOverride: String? = null): ProcessBuilder {
        val app = appDir(id) ?: throw IOException("$id is not installed")
        val ini = DroidSystem.parseIni(File(app, "metadata").readText())
        val meta = ini["Application"] ?: throw IOException("$id: bad metadata")
        val runtimeRef = meta["runtime"] ?: throw IOException("$id: no runtime")
        val runtime = runtimeDir(runtimeRef) ?: throw IOException("Runtime $runtimeRef is missing – reinstall with: flatpak install --user flathub $id")
        val command = commandOverride ?: meta["command"] ?: throw IOException("$id has no command")

        // the sandbox root: an empty tree with Flatpak's standard symlinks
        val sb = File(sys.root, "var/lib/droidsystem/flatpak-root").apply { mkdirs() }
        listOf("usr", "app", "etc", "tmp", "root", "run", "dev", "proc", "sys", "var/tmp", "home").forEach { File(sb, it).mkdirs() }
        listOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib", "lib64" to "usr/lib64", "lib32" to "usr/lib32").forEach { (n, t) ->
            val p = Paths.get(sb.path, n)
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(p, Paths.get(t))
        }
        val binds = mutableListOf(
            "/usr" to runtime.path, "/app" to File(app, "files").path,
            "/tmp" to File(sys.root, "tmp").path, "/root" to File(sys.root, "root").path,
            // Android forbids hard links, so proot (--link2symlink) turns the hard links Flatpak/OSTree install into
            // symlinks to files in Flatpak's repo – as DroidSystem paths (/var/lib/flatpak/..., /root/.local/...)
            // or as real paths in KDX's private storage. Make both resolve inside the app's sandbox too; without
            // this the runtime looks empty ("/usr/bin/env not found").
            "/var/lib/flatpak" to File(sys.root, "var/lib/flatpak").apply { mkdirs() }.path,
            sys.root.path to sys.root.path,
        )
        File(runtime, "etc").takeIf { it.isDirectory }?.let { binds += "/etc" to it.path }
        // host files Flatpak would normally provide
        listOf("passwd", "group", "resolv.conf", "hosts", "localtime", "machine-id").forEach { f ->
            val host = File(sys.root, "etc/$f")
            if (Files.exists(host.toPath())) binds += "/etc/$f" to host.path
        }
        // Extensions of the runtime (GL drivers = Mesa llvmpipe, codecs, ...) and of the app (plugins, ...)
        val ldExtra = mutableListOf<String>()
        val rtParts = runtimeRef.split('/')
        val rtArch = rtParts.getOrElse(1) { "" }; val rtBranch = rtParts.getOrElse(2) { "" }
        File(runtime.parentFile, "metadata").takeIf { it.isFile }?.let { m ->
            val (mnt, ld) = extensionMounts(m.readText(), "/usr", rtArch, rtBranch); binds += mnt; ldExtra += ld
        }
        val (appMnt, appLd) = extensionMounts(File(app, "metadata").readText(), "/app", rtArch, "stable")
        binds += appMnt; ldExtra += appLd
        // the shared D-Bus session bus lives in /tmp/runtime-root (bound via /tmp)

        val dataHome = "/root/.var/app/$id"
        listOf("config", "data", "cache").forEach { File(sys.root, "root/.var/app/$id/$it").mkdirs() }
        // A bwrap that reports the standard "no namespaces" error, ahead of the runtime's real one: libraries that
        // sandbox helpers with bubblewrap (glycin image loaders, WebKit…) then fall back to running them directly,
        // instead of failing because bwrap can't work under proot.
        File(sb, "run/kdx/bin").mkdirs()
        File(sb, "run/kdx/bin/bwrap").apply { writeText(NO_BWRAP); setExecutable(true, false) }
        // Flatpak normally generates this; without it fontconfig prints an error
        File(sb, "run/host").mkdirs()
        File(sb, "run/host/font-dirs.xml").takeIf { !it.exists() }
            ?.writeText("<?xml version=\"1.0\"?>\n<!DOCTYPE fontconfig SYSTEM \"fonts.dtd\">\n<fontconfig></fontconfig>\n")
        gtkSettings(File(sys.root, "root/.var/app/$id/config"))
        val env = on.env + mapOf(
            "PATH" to "/app/bin:/run/kdx/bin:/usr/bin", "FLATPAK_ID" to id, "container" to "flatpak",
            "LD_LIBRARY_PATH" to (listOf("/app/lib", "/app/lib/$triplet") + ldExtra + "/usr/lib/$triplet/GL/default/lib").joinToString(":"),
            "XDG_DATA_DIRS" to "/app/share:/usr/share:/usr/share/runtime/share:/run/host/share",
            "XDG_CONFIG_HOME" to "$dataHome/config", "XDG_DATA_HOME" to "$dataHome/data", "XDG_CACHE_HOME" to "$dataHome/cache",
            "GIO_USE_VFS" to "local", "GTK_USE_PORTAL" to "0", "GTK_A11Y" to "none",
            // apps that manage Flatpak themselves (the real Bazaar) use DroidSystem's per-user installation
            "FLATPAK_USER_DIR" to "/root/.local/share/flatpak", "HOST_XDG_DATA_HOME" to "/root/.local/share",
            // Chromium/Electron's own sandbox needs kernel namespaces Android doesn't give apps
            "ZYPAK_DISABLE_SANDBOX" to "1", "ELECTRON_DISABLE_SANDBOX" to "1", "ELECTRON_NO_SANDBOX" to "1",
            "QTWEBENGINE_DISABLE_SANDBOX" to "1", "MOZ_DISABLE_CONTENT_SANDBOX" to "1",
            "__EGL_VENDOR_LIBRARY_DIRS" to "/usr/lib/$triplet/GL/default/share/glvnd/egl_vendor.d",
            "LIBGL_DRIVERS_PATH" to "/usr/lib/$triplet/GL/default/lib/dri",
        ) + (ini["Environment"] ?: emptyMap())
        val exe = if (command.startsWith("/")) command else listOf("/app/bin/$command", "/usr/bin/$command")
            .firstOrNull { File(sb, it).exists() || File(if (it.startsWith("/app")) File(app, "files") else runtime, it.substringAfter('/').substringAfter('/')).exists() }
            ?: "/app/bin/$command"
        return sys.command(listOf(exe) + args, "/root", env, rootfs = sb, binds = binds)
    }

    companion object {
        const val NO_BWRAP = "#!/bin/sh\n# DroidSystem: bubblewrap needs kernel namespaces, which Android doesn't allow apps\n" +
            "echo 'bwrap: No permissions to create a new namespace, likely because the kernel does not allow non-privileged user namespaces.' >&2\nexit 1\n"

        /**
         * GTK apps draw their own minimize/maximize/close buttons; KDX's title bar already has them, so hide the
         * app's (only if the user has no GTK settings yet).
         */
        fun gtkSettings(configHome: File) {
            listOf("gtk-3.0", "gtk-4.0").forEach { v ->
                val f = File(configHome, "$v/settings.ini")
                if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText("[Settings]\ngtk-decoration-layout=:\n") }
            }
        }
    }

    fun launch(id: String, args: List<String> = emptyList(), on: LinuxDisplay = display, commandOverride: String? = null): Process {
        if (!on.desktop) on.appId = id // a desktop display (KDE Plasma) shows many apps
        return command(id, args, on, commandOverride).start().also { it.outputStream.close(); on.drain(it, id) }
    }

    /** Stops any leftover processes of [id] (from a closed window, a crash or an earlier KDX session). */
    fun killLeftovers(id: String) = runCatching { Procs.killEnv("FLATPAK_ID=$id") }
}


/**
 * Sound for Linux apps: PulseAudio runs inside DroidSystem with a virtual speaker ("kdx"). Apps (Debian and
 * Flatpak) connect over TCP ([PULSE_SERVER]); everything played into the speaker is streamed as raw 48 kHz
 * stereo 16-bit PCM on [PCM_PORT], which KDX plays through Android (AndroidAudio). TCP avoids shared memory and
 * socket files, which don't work reliably under proot.
 */
class LinuxAudio(val sys: DebianSystem) {
    @Volatile private var server: Process? = null
    val log = java.util.concurrent.ConcurrentLinkedDeque<String>()

    val isSetUp get() = sys.hasCommand("usr/bin/pulseaudio")

    fun isRunning() = server?.isAlive == true && portOpen(PCM_PORT)

    private fun portOpen(p: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 300) } }.isSuccess

    @Synchronized
    fun start() {
        if (isRunning() || !isSetUp) return
        if (!File(sys.root, CONFIG).isFile) File(sys.root, CONFIG).apply { parentFile?.mkdirs() }.writeText(PA_CONFIG)
        server?.destroy()
        server = sys.command(listOf("pulseaudio", "-n", "-F", "/$CONFIG", "--daemonize=no", "--exit-idle-time=-1",
            "--disallow-exit=true", "--use-pid-file=no", "--disable-shm=true", "--log-target=stderr",
            "--system=false", "--realtime=false", "--high-priority=false"), env = mapOf("HOME" to "/root")).start().also { p ->
            p.outputStream.close()
            Thread { runCatching { p.inputStream.bufferedReader().forEachLine { log.add(it); while (log.size > 100) log.pollFirst() } } }
                .apply { isDaemon = true }.start()
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (!portOpen(PCM_PORT) && System.currentTimeMillis() < deadline && server?.isAlive == true) Thread.sleep(150)
    }

    fun stop() { server?.destroy(); server = null }

    companion object {
        const val NATIVE_PORT = 4714
        const val PCM_PORT = 4713
        const val RATE = 48000
        const val PULSE_SERVER = "tcp:127.0.0.1:4714"
        const val CONFIG = "etc/pulse/kdx.pa"
        val PA_CONFIG = """
            |.fail
            |load-module module-null-sink sink_name=kdx rate=$RATE channels=2 sink_properties=device.description=KDX
            |set-default-sink kdx
            |load-module module-native-protocol-tcp port=$NATIVE_PORT listen=127.0.0.1 auth-anonymous=1
            |load-module module-simple-protocol-tcp port=$PCM_PORT listen=127.0.0.1 source=kdx.monitor record=true playback=false rate=$RATE channels=2 format=s16le
            |.nofail
            |load-module module-always-sink
            |""".trimMargin()
    }
}
