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
class LinuxDisplay(val sys: DebianSystem, val display: Int = 1) {
    val port get() = 5900 + display
    @Volatile private var server: Process? = null
    @Volatile private var wm: Process? = null
    val log = java.util.concurrent.ConcurrentLinkedDeque<String>()

    val isSetUp get() = sys.hasCommand("usr/bin/Xvnc")

    fun isRunning(): Boolean = server?.isAlive == true && portOpen()

    private fun portOpen() = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess

    val env get() = mapOf(
        "DISPLAY" to ":$display", "XDG_RUNTIME_DIR" to "/tmp/runtime-root", "NO_AT_BRIDGE" to "1",
        "GDK_BACKEND" to "x11", "QT_QPA_PLATFORM" to "xcb", "LIBGL_ALWAYS_SOFTWARE" to "1", "GSK_RENDERER" to "cairo",
    )

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
        server = sys.command(
            listOf("Xvnc", ":$display", "-geometry", "${w}x$h", "-depth", "24", "-SecurityTypes", "None",
                "-localhost", "-rfbport", "$port", "-AlwaysShared", "-ac", "-nolisten", "tcp"),
        ).start().also { drain(it, "Xvnc") }
        val deadline = System.currentTimeMillis() + 20_000
        while (!portOpen()) {
            if (server?.isAlive != true) throw IOException("Display server exited:\n" + log.takeLast(8).joinToString("\n"))
            if (System.currentTimeMillis() > deadline) throw IOException("Display server didn't start in time")
            Thread.sleep(150)
        }
        if (sys.hasCommand("usr/bin/openbox")) wm = launch("openbox")
    }

    /** Starts a GUI program on this display. */
    fun launch(cmd: String, extraEnv: Map<String, String> = emptyMap()): Process =
        sys.command(listOf("/bin/bash", "-lc", "exec $cmd"), "/root", env + extraEnv).start().also { it.outputStream.close(); drain(it, cmd.substringBefore(' ')) }

    fun stop() {
        wm?.destroy(); server?.destroy()
        wm = null; server = null
    }

    fun drain(p: Process, tag: String) = Thread {
        runCatching { p.inputStream.bufferedReader().forEachLine { log.add("$tag: $it"); while (log.size > 200) log.pollFirst() } }
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

    private val triplet get() = when (sys.arch) { "amd64" -> "x86_64-linux-gnu"; "arm64" -> "aarch64-linux-gnu"; "armhf" -> "arm-linux-gnueabihf"; else -> "i386-linux-gnu" }

    /** Builds the process that runs [id]. Throws with a readable message if something is missing. */
    fun command(id: String, args: List<String> = emptyList()): ProcessBuilder {
        val app = appDir(id) ?: throw IOException("$id is not installed")
        val ini = DroidSystem.parseIni(File(app, "metadata").readText())
        val meta = ini["Application"] ?: throw IOException("$id: bad metadata")
        val runtimeRef = meta["runtime"] ?: throw IOException("$id: no runtime")
        val runtime = runtimeDir(runtimeRef) ?: throw IOException("Runtime $runtimeRef is missing – reinstall with: flatpak install --user flathub $id")
        val command = meta["command"] ?: throw IOException("$id has no command")

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
        )
        File(runtime, "etc").takeIf { it.isDirectory }?.let { binds += "/etc" to it.path }
        // host files Flatpak would normally provide
        listOf("passwd", "group", "resolv.conf", "hosts", "localtime", "machine-id").forEach { f ->
            val host = File(sys.root, "etc/$f")
            if (Files.exists(host.toPath())) binds += "/etc/$f" to host.path
        }
        // GL driver extension (Mesa software rendering), if installed
        homes.flatMap { h -> (File(h, "runtime/org.freedesktop.Platform.GL.default").listFiles() ?: emptyArray()).toList() }
            .flatMap { a -> (a.listFiles() ?: emptyArray()).toList() }
            .map { File(it, "active/files") }.firstOrNull { it.isDirectory }
            ?.let { binds += "/usr/lib/$triplet/GL/default" to it.path }

        val dataHome = "/root/.var/app/$id"
        listOf("config", "data", "cache").forEach { File(sys.root, "root/.var/app/$id/$it").mkdirs() }
        val env = display.env + mapOf(
            "PATH" to "/app/bin:/usr/bin", "FLATPAK_ID" to id, "container" to "flatpak",
            "LD_LIBRARY_PATH" to "/app/lib:/app/lib/$triplet",
            "XDG_DATA_DIRS" to "/app/share:/usr/share:/usr/share/runtime/share:/run/host/share",
            "XDG_CONFIG_HOME" to "$dataHome/config", "XDG_DATA_HOME" to "$dataHome/data", "XDG_CACHE_HOME" to "$dataHome/cache",
            "GIO_USE_VFS" to "local", "GTK_USE_PORTAL" to "0",
        ) + (ini["Environment"] ?: emptyMap())
        val exe = if (command.startsWith("/")) command else listOf("/app/bin/$command", "/usr/bin/$command")
            .firstOrNull { File(sb, it).exists() || File(if (it.startsWith("/app")) File(app, "files") else runtime, it.substringAfter('/').substringAfter('/')).exists() }
            ?: "/app/bin/$command"
        return sys.command(listOf(exe) + args, "/root", env, rootfs = sb, binds = binds)
    }

    fun launch(id: String, args: List<String> = emptyList()): Process =
        command(id, args).start().also { it.outputStream.close(); display.drain(it, id) }
}
