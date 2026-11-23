package org.kdx.shell.droid

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** How to start proot on this device. */
data class ProotConfig(
    val proot: String,
    val env: Map<String, String> = emptyMap(),
    val flags: List<String> = emptyList(),
    /** Extra host paths to bind (e.g. Android shared storage). guest path -> host path */
    val binds: List<Pair<String, String>> = emptyList(),
)

/**
 * DroidSystem 2: a Debian-based Linux system run with proot. The root file system lives in KDX's private
 * storage (Android doesn't allow executables or symlinks on shared storage); the user's DroidSystem folder on
 * shared storage is mounted at /home/user.
 */
class DebianSystem(
    val root: File,
    val proot: ProotConfig,
    val homeHost: File? = null,
    val arch: String,
    val mirror: String = "https://deb.debian.org/debian",
    val securityMirror: String = "https://security.debian.org/debian-security",
) {
    private val stateDir get() = File(root, "var/lib/droidsystem")
    val isInstalled get() = File(stateDir, "installed").isFile
    val flatpakHome get() = File(root, "root/.local/share/flatpak")
    fun hasCommand(path: String) = Files.exists(Paths.get(root.path, path), LinkOption.NOFOLLOW_LINKS)

    /** Extra variables for every command (used by tests on machines that need a proxy; empty on phones). */
    @Volatile var extraEnv: Map<String, String> = emptyMap()

    // ------------------------------------------------------------------ running commands
    val defaultEnv = mapOf(
        "HOME" to "/root", "USER" to "root", "LOGNAME" to "root", "HOSTNAME" to "droidsystem",
        "LANG" to "C.UTF-8", "TERM" to "xterm-256color", "SHELL" to "/bin/bash",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games",
        "TMPDIR" to "/tmp", "DEBIAN_FRONTEND" to "noninteractive",
    )

    /** Builds a process that runs [argv] inside DroidSystem. */
    fun command(
        argv: List<String>,
        cwd: String = "/root",
        env: Map<String, String> = emptyMap(),
        rootfs: File = root,
        binds: List<Pair<String, String>> = emptyList(),
    ): ProcessBuilder {
        File(rootfs, "tmp").mkdirs()
        val args = mutableListOf(proot.proot)
        args += proot.flags
        args += listOf("-0", "-r", rootfs.path)
        val allBinds = mutableListOf("/dev" to "/dev", "/proc" to "/proc", "/sys" to "/sys", "/dev/shm" to File(rootfs, "tmp").path)
        allBinds += proot.binds
        if (rootfs == root) homeHost?.let { it.mkdirs(); allBinds += "/home/user" to it.path }
        allBinds += binds
        // Privacy VPN on: Firefox is told to use its proxy (see LinuxPrivacy)
        LinuxPrivacy.policies?.takeIf { LinuxPrivacy.active && it.isFile }?.let { pol ->
            LinuxPrivacy.FIREFOX_DIRS.forEach { dir ->
                val bound = allBinds.filter { (g, _) -> dir == g || dir.startsWith("$g/") }.maxByOrNull { it.first.length }
                val host = if (bound != null) File(bound.second, dir.removePrefix(bound.first)) else File(rootfs, dir)
                if (host.isDirectory) allBinds += "$dir/distribution/policies.json" to pol.path
            }
        }
        allBinds.forEach { (guest, host) -> args += listOf("-b", if (guest == host) host else "$host:$guest") }
        args += listOf("-w", cwd)
        args += listOf("/usr/bin/env", "-i")
        (defaultEnv + extraEnv + LinuxPrivacy.env + env).forEach { (k, v) -> args += "$k=$v" }
        args += argv
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().putAll(proot.env)
        return pb
    }

    fun shell(cmd: String, cwd: String = "/root", env: Map<String, String> = emptyMap()) =
        command(listOf("/bin/bash", "-c", cmd), cwd, env)

    /** Runs a command to completion, streaming its output lines to [log]. Returns the exit code. */
    fun run(cmd: String, log: (String) -> Unit = {}, env: Map<String, String> = emptyMap()): Int {
        val p = shell(cmd, env = env).start()
        p.outputStream.close()
        p.inputStream.bufferedReader().forEachLine { log(it) }
        return p.waitFor()
    }

    /** Runs a command and returns its output (for small queries). */
    fun query(cmd: String, cwd: String = "/root", env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val p = shell(cmd, cwd, env).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    // ------------------------------------------------------------------ bootstrap
    /**
     * Builds DroidSystem from official Debian packages, the way debootstrap does:
     *  1. resolve the minbase package set and download it (SHA-256 verified)
     *  2. unpack every package's files so dpkg can run
     *  3. inside proot: install the core with dpkg, unpack the rest, configure everything
     *  4. apply the DroidSystem layer (branding, apt settings for proot, tools)
     */
    fun bootstrap(repo: DebianRepo, cache: File, progress: (Float, String) -> Unit, log: (String) -> Unit = {}) {
        progress(0.01f, "Reading Debian package index")
        repo.load(cache)
        val pkgs = repo.minbase()
        val total = pkgs.sumOf { it.size }.coerceAtLeast(1)
        log("Selected ${pkgs.size} packages (${total / 1048576} MiB) from Debian ${repo.suite}/${repo.arch}")

        // 1. download
        val archives = File(root, "var/cache/apt/archives")
        archives.mkdirs()
        val done = AtomicLong()
        val pool = Executors.newFixedThreadPool(4)
        val errors = mutableListOf<Throwable>()
        pkgs.map { p ->
            pool.submit {
                runCatching {
                    DebianRepo.download(repo.url(p), File(archives, debName(p)), p.sha256) {
                        val d = done.addAndGet(it)
                        progress(0.02f + 0.43f * d / total, "Downloading packages (${d / 1048576}/${total / 1048576} MiB)")
                    }
                }.onFailure { synchronized(errors) { errors += it } }
            }
        }.forEach { it.get() }
        pool.shutdown()
        errors.firstOrNull()?.let { throw it }

        // 2. unpack (stage 1)
        progress(0.46f, "Unpacking base system")
        prepareTree()
        pkgs.forEachIndexed { i, p ->
            unpackDeb(File(archives, debName(p)))
            if (i % 5 == 0) progress(0.46f + 0.14f * i / pkgs.size, "Unpacking ${p.name}")
        }
        writeBaseConfig()

        // 3. dpkg inside proot (stage 2)
        val deb = { names: List<String> -> names.mapNotNull { n -> pkgs.firstOrNull { it.name == n } }.joinToString(" ") { "/var/cache/apt/archives/${debName(it)}" } }
        fun dpkg(stage: String, p: Float, cmd: String) {
            progress(p, stage)
            val rc = run(cmd, log)
            if (rc != 0) log("($stage finished with code $rc – continuing)")
        }
        dpkg("Installing core packages", 0.62f, "dpkg --force-depends --install ${deb(listOf("base-passwd"))}")
        dpkg("Installing core packages", 0.64f, "dpkg --force-depends --install ${deb(listOf("base-files"))}")
        dpkg("Installing core packages", 0.66f, "dpkg --force-depends --install ${deb(listOf("dpkg", "libc6"))}")
        dpkg("Installing core packages", 0.70f, "dpkg --force-depends --install ${deb(listOf("perl-base", "mawk", "debconf"))}")
        dpkg("Unpacking all packages", 0.74f,
            "dpkg --force-overwrite --force-confold --skip-same-version --force-depends --unpack /var/cache/apt/archives/*.deb")
        dpkg("Configuring packages", 0.85f, "dpkg --configure --pending --force-configure-any --force-depends")
        dpkg("Configuring packages", 0.90f, "dpkg --configure -a")

        // 4. DroidSystem layer
        progress(0.94f, "Applying DroidSystem layer")
        applyDroidLayer()
        archives.listFiles { f -> f.name.endsWith(".deb") }?.forEach { it.delete() }
        progress(0.97f, "Checking apt")
        val (rc, out) = query("dpkg-query -W -f='\${Status}\\n' apt coreutils bash | sort | uniq -c")
        log(out.trim())
        if (rc != 0 || out.contains("half") || !out.contains("install ok installed")) throw IOException("Base system did not configure correctly:\n$out")
        stateDir.mkdirs()
        File(stateDir, "installed").writeText("${repo.suite} ${repo.arch} ${java.util.Date()}\n")
        progress(1f, "Done")
    }

    private fun debName(p: DebPackage) = p.filename.substringAfterLast('/')

    private fun prepareTree() {
        listOf("usr/bin", "usr/sbin", "usr/lib", "etc", "var/lib/dpkg/info", "var/lib/dpkg/updates", "var/lib/dpkg/triggers",
            "var/cache/apt/archives/partial", "var/log", "tmp", "root", "home/user", "proc", "sys", "dev", "run", "mnt", "media", "opt", "srv")
            .forEach { File(root, it).mkdirs() }
        // merged /usr, as Debian has used since bookworm
        val links = mutableListOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib")
        if (arch == "amd64") { File(root, "usr/lib64").mkdirs(); links += "lib64" to "usr/lib64" }
        links.forEach { (name, target) ->
            val p = Paths.get(root.path, name)
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(p, Paths.get(target))
        }
        File(root, "tmp").setWritable(true, false)
        listOf("status", "available").forEach { File(root, "var/lib/dpkg/$it").apply { if (!exists()) writeText("") } }
        File(root, "var/lib/dpkg/arch").writeText("$arch\n")
    }

    private fun writeBaseConfig() {
        File(root, "etc/resolv.conf").let { f ->
            Files.deleteIfExists(f.toPath())
            f.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost droidsystem\n::1 localhost ip6-localhost ip6-loopback\n")
        File(root, "etc/hostname").writeText("droidsystem\n")
        writeDpkgConfig()
        // no init system / daemons under proot
        File(root, "usr/sbin/policy-rc.d").apply { writeText("#!/bin/sh\nexit 101\n"); setExecutable(true, false) }
    }

    /**
     * dpkg settings for phones: no fsync after every file (much faster on flash storage), and no translations,
     * manuals or documentation – DroidSystem runs in English (C.UTF-8), and under proot every file written costs
     * time. For KDE Plasma that is about a third of all files (≈31,000 files, 430 MB).
     */
    private fun writeDpkgConfig() {
        File(root, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(root, "etc/dpkg/dpkg.cfg.d/droidsystem").writeText(DPKG_CONFIG)
    }

    /** Settings that make Debian behave under proot on Android, plus DroidSystem branding and tools. */
    fun applyDroidLayer() {
        val apt = File(root, "etc/apt")
        File(apt, "sources.list.d").mkdirs()
        File(apt, "sources.list.d/debian.sources").writeText(
            "Types: deb\nURIs: $mirror\nSuites: trixie trixie-updates\nComponents: main contrib non-free non-free-firmware\n" +
                "Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg\n\n" +
                "Types: deb\nURIs: $securityMirror\nSuites: trixie-security\nComponents: main contrib non-free non-free-firmware\n" +
                "Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg\n"
        )
        File(apt, "sources.list").delete()
        File(apt, "apt.conf.d").mkdirs()
        File(apt, "apt.conf.d/99droidsystem").writeText(
            "// proot can't switch users, so apt's download sandbox runs as root\n" +
                "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Use-Pty \"false\";\n" +
                "APT::Install-Recommends \"false\";\nDpkg::Options { \"--force-confold\"; };\n"
        )
        writeDpkgConfig()
        File(root, "etc/os-release").let { f ->
            Files.deleteIfExists(f.toPath())
            f.writeText(
                "PRETTY_NAME=\"DroidSystem 2.0 (Debian trixie)\"\nNAME=\"DroidSystem\"\nVERSION_ID=\"2.0\"\nVERSION=\"2.0 (Breeze)\"\n" +
                    "VERSION_CODENAME=trixie\nID=droidsystem\nID_LIKE=debian\nHOME_URL=\"https://github.com/noahnoahg1234/kde-on-android\"\n"
            )
        }
        File(root, "etc/issue").writeText("DroidSystem 2.0 (Debian trixie) \\n \\l\n\n")
        File(root, "etc/motd").writeText(
            "  [o_o]  DroidSystem 2.0 \"Breeze\" - based on Debian trixie\n /|___|\\ apt, Linux apps and Flatpak on Android\n   d b\n\n" +
                " apt install <package>     install Debian software\n flatpak install <app-id>  install from Flathub (setup: flatpak-setup)\n" +
                " linux-display             start the graphical display for desktop apps\n bazaar                    graphical app store\n\n"
        )
        File(root, "root/.bashrc").writeText(
            "export PS1='\\[\\e[92m\\]\\u@droidsystem\\[\\e[0m\\]:\\[\\e[94m\\]\\w\\[\\e[0m\\]\\$ '\nalias ls='ls --color=auto'\nalias ll='ls -l'\nalias la='ls -la'\n"
        )
        // DroidSystem tools inside Debian
        val bin = File(root, "usr/local/bin").apply { mkdirs() }
        fun tool(name: String, body: String) = File(bin, name).apply { writeText(body); setExecutable(true, false) }
        tool("flatpak-setup", """#!/bin/sh
            |# Installs Flatpak and adds the Flathub remote (per-user installation, which works under proot).
            |set -e
            |if ! command -v flatpak >/dev/null; then apt-get update && apt-get install -y flatpak; fi
            |flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            |echo "Flatpak is ready. Install apps with: flatpak install flathub <app-id>"
            |""".trimMargin())
        tool("bazaar-setup", KdePlasma.bazaarSetupScript())
        tool("windsystem-setup", WindSystem.setupScript())
        tool("droidsystem-pack", StoragePacker.toolScript())
        File(root, WindSystem.OPENBOX_RC).apply { parentFile?.mkdirs() }.writeText(WindSystem.OPENBOX_XML)
        tool("display-setup", """#!/bin/sh
            |# Installs the X display server used by KDX's Linux Display window.
            |set -e
            |apt-get update
            |apt-get install -y tigervnc-standalone-server openbox dbus dbus-x11 x11-xserver-utils x11-utils xdotool fonts-dejavu-core adwaita-icon-theme \
            |  pulseaudio pulseaudio-utils libasound2-plugins
            |echo "Display server ready. Linux apps with windows now open in KDX's Linux Display."
            |""".trimMargin())
        // Real KDE Plasma 6 (see KdePlasma)
        tool("plasma-setup", KdePlasma.setupScript())
        // Sound: PulseAudio with a virtual speaker; KDX plays what it outputs (see LinuxAudio)
        tool("sound-setup", """#!/bin/sh
            |# Installs the sound server that carries Linux apps' audio to Android.
            |set -e
            |apt-get update
            |apt-get install -y pulseaudio pulseaudio-utils libasound2-plugins
            |echo "Sound ready. Linux apps play through your phone's speaker."
            |""".trimMargin())
        File(root, LinuxAudio.CONFIG).apply { parentFile?.mkdirs() }.writeText(LinuxAudio.PA_CONFIG)
        // ALSA-only programs go through PulseAudio too
        File(root, "etc/asound.conf").writeText("pcm.!default { type pulse }\nctl.!default { type pulse }\n")
        // same bwrap stand-in for Debian's own apps (see FlatpakRunner.NO_BWRAP)
        tool("bwrap", FlatpakRunner.NO_BWRAP)
        tool("droidsystem-version", "#!/bin/sh\n. /etc/os-release\necho \"\$PRETTY_NAME\"\n")
        // Openbox for KDX app windows: KDX draws the title bar, so no decorations; main windows fill the screen
        File(root, LinuxDisplay.APP_RC).apply { parentFile?.mkdirs() }.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            |<openbox_config xmlns="http://openbox.org/3.4/rc">
            |  <focus><focusNew>yes</focusNew><followMouse>no</followMouse></focus>
            |  <placement><policy>Smart</policy><center>yes</center></placement>
            |  <desktops><number>1</number></desktops>
            |  <applications>
            |    <application class="*"><decor>no</decor></application>
            |    <application class="*" type="normal"><decor>no</decor><maximized>yes</maximized></application>
            |  </applications>
            |</openbox_config>
            |""".trimMargin())
        stateDir.mkdirs()
        File(stateDir, "layer").writeText(LAYER_VERSION.toString())
    }

    /** Re-applies the DroidSystem layer when KDX ships a newer one (existing installs get new tools/config). */
    fun ensureLayer(): Boolean {
        if (!isInstalled) return false
        val cur = runCatching { File(stateDir, "layer").readText().trim().toInt() }.getOrDefault(0)
        if (cur >= LAYER_VERSION) return false
        applyDroidLayer()
        return true
    }

    // ------------------------------------------------------------------ unpacking
    /** Unpacks a .deb's files into the root file system, keeping symlinks and permissions. */
    fun unpackDeb(deb: File) {
        ArArchiveInputStream(deb.inputStream().buffered()).use { ar ->
            while (true) {
                val e = ar.nextEntry ?: break
                if (e.name.startsWith("data.tar")) { extractTar(decompress(e.name, ar), root); return }
            }
        }
        throw IOException("${deb.name}: no data archive")
    }

    private fun decompress(name: String, input: InputStream): InputStream = when {
        name.endsWith(".xz") -> XZCompressorInputStream(input)
        name.endsWith(".gz") -> GzipCompressorInputStream(input)
        name.endsWith(".zst") -> throw IOException("zstd-compressed package")
        else -> input
    }

    companion object {
        /** Bump when applyDroidLayer changes. 2: app-window Openbox config, xdotool/dbus in display-setup. */
        const val LAYER_VERSION = 10 // 10: droidsystem-pack; 3: bwrap stand-in, 4: sound, 5: plasma-setup, 6: dpkg excludes, 7: bazaar-setup, 8: windsystem

        val DPKG_CONFIG = """force-unsafe-io
            |path-exclude=/usr/share/locale/*
            |path-include=/usr/share/locale/locale.alias
            |path-exclude=/usr/share/doc/*
            |path-include=/usr/share/doc/*/copyright
            |path-exclude=/usr/share/man/*
            |path-exclude=/usr/share/info/*
            |path-exclude=/usr/share/help/*
            |path-exclude=/usr/share/gnome/help/*
            |path-exclude=/usr/share/lintian/*
            |path-exclude=/usr/share/linda/*
            |""".trimMargin()

        /** Extracts a tar stream into [dest] preserving symlinks, hard links (as copies) and permission bits. */
        fun extractTar(input: InputStream, dest: File) {
            val tar = TarArchiveInputStream(input, "UTF-8")
            val hardlinks = mutableListOf<Pair<String, String>>()
            while (true) {
                val t = tar.nextEntry ?: break
                val name = t.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                if (name.split('/').any { it == ".." }) throw IOException("Blocked unsafe path ${t.name}")
                val path = Paths.get(dest.path, name)
                when {
                    t.isDirectory -> { Files.createDirectories(path); setMode(path, t.mode or 0x1C0) }
                    t.isSymbolicLink -> {
                        Files.createDirectories(path.parent)
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path)
                        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(path, Paths.get(t.linkName))
                    }
                    t.isLink -> hardlinks += name to t.linkName.removePrefix("./")
                    t.isFile -> {
                        Files.createDirectories(path.parent)
                        if (Files.isSymbolicLink(path)) Files.delete(path)
                        Files.newOutputStream(path).use { tar.copyTo(it) }
                        setMode(path, t.mode)
                    }
                }
            }
            hardlinks.forEach { (name, target) ->
                val p = Paths.get(dest.path, name)
                Files.createDirectories(p.parent)
                runCatching { Files.copy(Paths.get(dest.path, target), p, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            }
        }

        fun setMode(p: java.nio.file.Path, mode: Int) {
            val perms = HashSet<PosixFilePermission>()
            val bits = listOf(
                0x100 to PosixFilePermission.OWNER_READ, 0x80 to PosixFilePermission.OWNER_WRITE, 0x40 to PosixFilePermission.OWNER_EXECUTE,
                0x20 to PosixFilePermission.GROUP_READ, 0x10 to PosixFilePermission.GROUP_WRITE, 0x8 to PosixFilePermission.GROUP_EXECUTE,
                0x4 to PosixFilePermission.OTHERS_READ, 0x2 to PosixFilePermission.OTHERS_WRITE, 0x1 to PosixFilePermission.OTHERS_EXECUTE,
            )
            bits.forEach { (b, perm) -> if (mode and b != 0) perms += perm }
            perms += PosixFilePermission.OWNER_READ; perms += PosixFilePermission.OWNER_WRITE // we must be able to replace it later
            runCatching { Files.setPosixFilePermissions(p, perms) }
        }
    }
}
