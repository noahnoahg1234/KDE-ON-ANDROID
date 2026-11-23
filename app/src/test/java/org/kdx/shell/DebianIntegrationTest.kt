package org.kdx.shell

import org.junit.Assert.*
import org.kdx.shell.droid.deleteTree
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.kdx.shell.droid.DebianRepo
import org.kdx.shell.droid.DebianSystem
import org.kdx.shell.droid.ProotConfig
import org.kdx.shell.droid.LinuxDisplay
import org.kdx.shell.droid.FlatpakRunner
import org.kdx.shell.droid.RfbClient
import org.kdx.shell.droid.LinuxAudio
import java.io.File

/**
 * Builds a real DroidSystem 2 (Debian) on this machine with the host's proot and exercises it.
 * Slow and networked, so it only runs with -Dkdx.integration=/path/to/workdir.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DebianIntegrationTest {
    private val work = System.getProperty("kdx.integration")?.let { File(it) }
    /** -Pkdx.arch=arm64 builds an ARM64 DroidSystem (runs through qemu-user binfmt on an x86 machine). */
    private val arch = System.getProperty("kdx.arch") ?: "amd64"
    private val sys by lazy {
        DebianSystem(File(work, "rootfs"), ProotConfig(System.getProperty("kdx.proot") ?: "/usr/bin/proot"), File(work, "home"), arch,
            mirror = "https://deb.debian.org/debian", securityMirror = "https://deb.debian.org/debian-security").apply {
            // test machines that only reach the internet through an HTTPS proxy (phones connect directly)
            System.getenv("KDX_TEST_PROXY")?.let { p -> extraEnv = mapOf("https_proxy" to p, "http_proxy" to p, "no_proxy" to "localhost,127.0.0.1") }
        }
    }
    private fun log(s: String) = println("  | $s")

    @Test fun a_bootstrap() {
        assumeTrue(work != null)
        if (sys.isInstalled) return
        var last = ""
        sys.bootstrap(DebianRepo(mirror = "https://deb.debian.org/debian", arch = arch), File(work, "cache"), { p, m -> if (m != last) { println("[%3.0f%%] %s".format(p * 100, m)); last = m } }, ::log)
        assertTrue(sys.isInstalled)
        trustTestProxy()
    }

    /** Machines behind a TLS-inspecting proxy: trust its CA inside the guest (phones don't need this). */
    private fun trustTestProxy() {
        val ca = System.getenv("KDX_EXTRA_CA") ?: return
        File(sys.root, "usr/local/share/ca-certificates").mkdirs()
        File(ca).copyTo(File(sys.root, "usr/local/share/ca-certificates/test-proxy.crt"), overwrite = true)
        sys.run("update-ca-certificates", ::log)
    }

    @Test fun b_basics() {
        assumeTrue(work != null && sys.isInstalled)
        val (rc, out) = sys.query("cat /etc/os-release; id; uname -m; bash --version | head -1; ls /home")
        println(out)
        assertEquals(0, rc)
        assertTrue(out.contains("DroidSystem 2.0"))
    }

    @Test fun c_apt() {
        assumeTrue(work != null && sys.isInstalled)
        assertEquals(0, sys.run("apt-get update", ::log))
        assertEquals(0, sys.run("apt-get install -y cowsay", ::log))
        val (rc, out) = sys.query("/usr/games/cowsay hello from droidsystem")
        println(out); assertEquals(0, rc); assertTrue(out.contains("hello from droidsystem"))
    }

    private fun snapshot(display: LinuxDisplay, name: String, settleMs: Long = 4000): IntArray {
        val rfb = RfbClient(port = display.port)
        rfb.connect()
        val t = Thread { runCatching { rfb.loop() } }.apply { isDaemon = true; start() }
        Thread.sleep(settleMs)
        File(work, "$name.ppm").outputStream().buffered().use { o ->
            o.write("P6 ${rfb.width} ${rfb.height} 255\n".toByteArray())
            rfb.pixels.forEach { c -> o.write(c shr 16 and 0xFF); o.write(c shr 8 and 0xFF); o.write(c and 0xFF) }
        }
        val px = rfb.pixels.copyOf()
        rfb.close(); t.join(1000)
        return px
    }

    @Test fun d_display() {
        assumeTrue(work != null && sys.isInstalled)
        if (!sys.hasCommand("usr/bin/Xvnc")) assertEquals(0, sys.run("display-setup && apt-get install -y x11-apps", ::log))
        val display = LinuxDisplay(sys)
        try {
            display.start(1024, 640)
            assertTrue(display.isRunning())
            display.launch("xclock -geometry 300x300+40+40")
            display.launch("xeyes -geometry 300x200+400+40")
            val px = snapshot(display, "display")
            assertTrue("display shows something", px.distinct().size > 10)
        } finally {
            display.log.forEach { println("  X| $it") }
            display.stop()
            sys.run("pkill -f Xvnc; pkill openbox; pkill xclock; pkill xeyes")
        }
    }

    @Test fun e_flatpak() {
        assumeTrue(work != null && sys.isInstalled)
        // OSTree (inside flatpak) relies on statx; old proot builds don't translate it. Termux's proot does.
        val (statRc, _) = sys.query("stat -c %n /var/lib/droidsystem/installed")
        assumeTrue("this proot doesn't support statx – real flatpak installs need a newer proot (Termux's has it)", statRc == 0)
        val id = "io.github.mike632t.x11-calc"
        // Test machines behind a TLS-inspecting proxy: trust its CA inside the guest (phones don't need this).
        System.getenv("KDX_EXTRA_CA")?.let { ca ->
            File(sys.root, "usr/local/share/ca-certificates").mkdirs()
            File(ca).copyTo(File(sys.root, "usr/local/share/ca-certificates/test-proxy.crt"), overwrite = true)
            sys.run("update-ca-certificates", ::log)
        }
        assertEquals(0, sys.run("flatpak-setup", ::log))
        val display = LinuxDisplay(sys)
        val fp = FlatpakRunner(sys, display)
        if (fp.appDir(id) == null) {
            val rc = sys.run("flatpak install --user -y --noninteractive flathub $id", ::log)
            println("flatpak install exit code $rc")
        }
        assertNotNull("flatpak app deployed", fp.appDir(id))
        println("installed: " + fp.installed())
        try {
            display.start(1024, 640)
            val p = fp.launch(id)
            Thread.sleep(3000)
            val px = snapshot(display, "flatpak")
            display.log.forEach { println("  X| $it") }
            assertTrue("flatpak app process is running", p.isAlive)
            assertTrue(px.distinct().size > 10)
        } finally {
            display.stop()
            sys.run("pkill -f Xvnc; pkill openbox; pkill -f x11-calc")
        }
    }

    /** Exercises FlatpakRunner's sandbox layout with a stand-in app whose "runtime" is Debian's /usr. */
    @Test fun f_flatpakRunnerLayout() {
        assumeTrue(work != null && sys.isInstalled && sys.hasCommand("usr/bin/Xvnc"))
        val id = "org.example.XEyes"
        val app = File(sys.root, "var/lib/flatpak/app/$id/current/active").apply { mkdirs() }
        File(app, "metadata").writeText("[Application]\nname=$id\nruntime=org.example.Platform/x86_64/1\ncommand=run-eyes\n\n[Environment]\nEYES_TEST=1\n")
        File(app, "files/bin").mkdirs()
        File(app, "files/bin/run-eyes").apply {
            writeText("#!/bin/sh\n[ \"\$FLATPAK_ID\" = $id ] && [ \"\$EYES_TEST\" = 1 ] && cat /app/bin/run-eyes /usr/bin/xeyes > /dev/null && echo sandbox-ok\nexec /usr/bin/xeyes -geometry 500x300+100+100\n")
            setExecutable(true, false)
        }
        val rt = File(sys.root, "var/lib/flatpak/runtime/org.example.Platform/x86_64/1/active")
        rt.mkdirs()
        val files = File(rt, "files").toPath()
        if (!java.nio.file.Files.exists(files, java.nio.file.LinkOption.NOFOLLOW_LINKS)) java.nio.file.Files.createSymbolicLink(files, File(sys.root, "usr").toPath())
        val display = LinuxDisplay(sys)
        val fp = FlatpakRunner(sys, display)
        assertTrue(fp.installed().any { it.id == id })
        try {
            display.start(1024, 640)
            val p = fp.launch(id)
            Thread.sleep(3000)
            snapshot(display, "flatpak-runner")
            display.log.forEach { println("  X| $it") }
            assertTrue("sandboxed app started", display.log.any { it.contains("sandbox-ok") })
            assertTrue(p.isAlive)
        } finally {
            display.stop()
            sys.run("pkill -f Xvnc; pkill openbox; pkill xeyes")
        }
    }

    /** App windows: maximized without decorations, title readable, resizable, no pointer drawn. */
    @Test fun g_appWindow() {
        assumeTrue(work != null && sys.isInstalled && sys.hasCommand("usr/bin/Xvnc"))
        sys.ensureLayer()
        if (!sys.hasCommand("usr/bin/xdotool")) assertEquals(0, sys.run("apt-get install -y xdotool dbus dbus-x11 x11-utils", ::log))
        val d = LinuxDisplay(sys, 3, appMode = true)
        try {
            d.start(800, 600)
            d.appProcess = d.launch("xeyes")
            Thread.sleep(2500)
            assertEquals("xeyes", d.windowTitle())
            val rfb = RfbClient(port = d.port)
            rfb.connect()
            val t = Thread { runCatching { rfb.loop() } }.apply { isDaemon = true; start() }
            Thread.sleep(1500)
            assertTrue("server supports resizing", rfb.canResize)
            rfb.setDesktopSize(560, 720)
            var n = 0
            while ((rfb.width != 560 || rfb.height != 720) && n++ < 50) Thread.sleep(100)
            assertEquals(560, rfb.width); assertEquals(720, rfb.height)
            Thread.sleep(1500) // openbox re-maximizes xeyes
            File(work, "appwindow.ppm").outputStream().buffered().use { o ->
                o.write("P6 ${rfb.width} ${rfb.height} 255\n".toByteArray())
                rfb.pixels.forEach { c -> o.write(c shr 16 and 0xFF); o.write(c shr 8 and 0xFF); o.write(c and 0xFF) }
            }
            // maximized: xeyes' white eyes reach close to the edges of the screen
            val px = rfb.pixels; val w = rfb.width; val h = rfb.height
            val white = { x: Int, y: Int -> px[y * w + x] and 0xFFFFFF == 0xFFFFFF }
            assertTrue("app fills the screen", (0 until w).any { white(it, h / 2) && it < w / 8 } && (0 until w).any { white(it, h / 2) && it > w * 7 / 8 })
            // no mouse pointer is drawn (TigerVNC would draw one where the client didn't put it, e.g. after a resize)
            val edges = (0 until h).count { y -> (w - 12 until w).any { x -> px[y * w + x] and 0xFFFFFF != 0 } } +
                (0 until w).count { x -> (h - 12 until h).any { y -> px[y * w + x] and 0xFFFFFF != 0 } }
            assertEquals("pointer hidden", 0, edges)
            rfb.close(); t.join(1000)
            assertTrue(d.appProcess!!.isAlive)
        } finally {
            d.log.forEach { if (!it.contains("EncodeManager") && !it.contains("Compar")) println("  X| $it") }
            d.stop()
            sys.run("pkill -f 'Xvnc :3'; pkill xeyes; pkill -f kdx-app.xml")
        }
    }

    /** Flatpak extensions ([Extension …] in runtime metadata) are mounted where the runtime expects them. */
    @Test fun h_flatpakExtensions() {
        assumeTrue(work != null && sys.isInstalled)
        val display = LinuxDisplay(sys)
        val fp = FlatpakRunner(sys, display)
        val rt = File(sys.root, "var/lib/flatpak/runtime/org.example.Platform/x86_64/1/active")
        File(rt, "metadata").writeText("[Runtime]\nname=org.example.Platform\n\n[Extension org.example.Platform.GL]\ndirectory=lib/GL\nversion=1\nsubdirectories=true\nadd-ld-path=lib\n")
        val ext = File(sys.root, "var/lib/flatpak/runtime/org.example.Platform.GL.default/x86_64/1/active/files/lib").apply { mkdirs() }
        File(ext, "marker").writeText("gl-extension\n")
        val (mounts, ld) = fp.extensionMounts(File(rt, "metadata").readText(), "/usr", "x86_64", "1")
        assertEquals(listOf("/usr/lib/GL/default"), mounts.map { it.first })
        assertEquals(listOf("/usr/lib/GL/default/lib"), ld)
        val id = "org.example.ExtCheck"
        val app = File(sys.root, "var/lib/flatpak/app/$id/current/active").apply { mkdirs() }
        File(app, "metadata").writeText("[Application]\nname=$id\nruntime=org.example.Platform/x86_64/1\ncommand=check\n")
        File(app, "files/bin").mkdirs()
        File(app, "files/bin/check").apply { writeText("#!/bin/sh\ncat /usr/lib/GL/default/lib/marker\necho LD=\$LD_LIBRARY_PATH\n"); setExecutable(true, false) }
        val p = fp.command(id).start(); p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        println(out)
        assertTrue(out.contains("gl-extension"))
        assertTrue(out.contains("/usr/lib/GL/default/lib"))
    }

    /** Runs an already-installed Flatpak (id from -Pkdx.flatpak) the way KDX does, for diagnosing app problems. */
    @Test fun i_runInstalledFlatpak() {
        val id = System.getProperty("kdx.flatpak")
        assumeTrue(work != null && sys.isInstalled && id != null && sys.hasCommand("usr/bin/Xvnc"))
        val base = LinuxDisplay(sys)
        val fp = FlatpakRunner(sys, base)
        assertNotNull("$id installed", fp.appDir(id!!))
        val d = LinuxDisplay(sys, 4, appMode = true)
        try {
            d.start(900, 1200)
            // inside this app's sandbox: is the network reachable, and is the session bus?
            // test machines behind a TLS-inspecting proxy: let the sandbox trust it (phones don't need this)
            val ca = System.getenv("KDX_EXTRA_CA")
            // ...and only resolves names via its HTTPS proxy (phones resolve DNS normally)
            val proxy = System.getenv("KDX_TEST_PROXY")
            fun withCa(pb: ProcessBuilder) = pb.apply {
                if (ca != null) command(command().take(1) + listOf("-b", "$ca:/etc/ssl/certs/ca-certificates.crt") + command().drop(1))
                if (proxy != null) command().indexOf("/usr/bin/env").takeIf { it > 0 }?.let { i ->
                    command().addAll(i + 2, listOf("https_proxy=$proxy", "http_proxy=$proxy", "HTTPS_PROXY=$proxy", "no_proxy=localhost,127.0.0.1,deb.debian.org"))
                }
            }
            fun inSandbox(sh: String): String {
                val pb = withCa(fp.command(id)); val cmd = pb.command()
                val exe = cmd.indexOfLast { it.startsWith("/app/") || it.startsWith("/usr/") }
                pb.command(cmd.subList(0, exe) + listOf("/bin/sh", "-c", sh))
                val p = pb.start(); p.outputStream.close()
                return p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
            }
            val net = inSandbox("curl -sS -o /dev/null -w '%{http_code}' --max-time 20 http://deb.debian.org/debian/dists/trixie/Release")
            println("SANDBOX NET: $net")
            val bus = inSandbox("gdbus call --session --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.GetId")
            println("SANDBOX DBUS: $bus")
            println("SANDBOX ENV: " + inSandbox("env | grep -E 'GLYCIN|WEBKIT' | tr '\\n' ' '"))
            println("SANDBOX HTTPS: " + inSandbox("curl -sS -o /dev/null -w '%{http_code}' --max-time 20 https://api.garythe.cat/gary"))
            // (a test machine's HTTPS-only proxy answers plain HTTP with 405 – still proves the network is reachable)
            assertTrue("network works inside the Flatpak sandbox: $net", net.takeLast(3) == "200" || (proxy != null && net.takeLast(3) == "405"))
            assertTrue("session bus works inside the Flatpak sandbox: $bus", bus.startsWith("('"))
            d.appProcess = withCa(fp.command(id)).apply {
                command().replaceAll { if (it == "DISPLAY=:1") "DISPLAY=:${d.display}" else it }
                System.getProperty("kdx.debug")?.let { dbg -> command().indexOf("/usr/bin/env").takeIf { it > 0 }?.let { i -> command().add(i + 2, "G_MESSAGES_DEBUG=$dbg") } }
            }.start().also { it.outputStream.close(); d.drain(it, id) }
            Thread.sleep(System.getProperty("kdx.wait")?.toLong() ?: 12000)
            println("TITLE: " + d.windowTitle())
            println("ALIVE: " + d.appProcess!!.isAlive)
            val rfb = RfbClient(port = d.port); rfb.connect()
            val t = Thread { runCatching { rfb.loop() } }.apply { isDaemon = true; start() }
            Thread.sleep(2500)
            File(work, "app-$id.ppm").outputStream().buffered().use { o ->
                o.write("P6 ${rfb.width} ${rfb.height} 255\n".toByteArray())
                rfb.pixels.forEach { c -> o.write(c shr 16 and 0xFF); o.write(c shr 8 and 0xFF); o.write(c and 0xFF) }
            }
            rfb.close(); t.join(1000)
        } finally {
            d.log.filter { !it.startsWith("Xvnc") }.forEach { println("  LOG| $it") }
            d.stop()
            sys.run("pkill -f 'Xvnc :4'; pkill -f kdx-app.xml")
        }
    }

    /**
     * Runtimes installed by flatpak inside proot on Android are mostly symlinks (proot's --link2symlink stands in for
     * the hard links Android forbids), pointing at Flatpak's repo by DroidSystem path or by real path. The app's
     * sandbox must resolve both, or the runtime looks empty ("/usr/bin/env not found").
     */
    @Test fun j_linkedRuntime() {
        assumeTrue(work != null && sys.isInstalled)
        val repoObj = File(sys.root, "var/lib/flatpak/repo/objects/ab").apply { mkdirs() }
        // the "real" files live in the repo; the runtime's files are links to them
        listOf("env", "sh", "cat", "echo").forEach { b -> File(sys.root, "usr/bin/$b").copyTo(File(repoObj, "$b.file"), overwrite = true).setExecutable(true, false) }
        val rt = File(sys.root, "var/lib/flatpak/runtime/org.example.Linked/x86_64/1/active/files")
        rt.deleteTree(); File(rt, "bin").mkdirs()
        fun link(name: String, target: String) = java.nio.file.Files.createSymbolicLink(File(rt, "bin/$name").toPath(), java.nio.file.Paths.get(target))
        link("env", "/var/lib/flatpak/repo/objects/ab/env.file")                 // DroidSystem (guest) path
        link("sh", File(repoObj, "sh.file").path)                                 // real (host) path
        link("cat", "/var/lib/flatpak/repo/objects/ab/cat.file")
        link("echo", File(repoObj, "echo.file").path)
        // the C library etc. come from Debian here (a real runtime ships its own)
        listOf("lib", "lib64", "share").forEach { d ->
            java.nio.file.Files.createSymbolicLink(File(rt, d).toPath(), File(sys.root, "usr/$d").toPath())
        }
        val id = "org.example.Linked.App"
        val app = File(sys.root, "var/lib/flatpak/app/$id/current/active").apply { mkdirs() }
        File(app, "metadata").writeText("[Application]\nname=$id\nruntime=org.example.Linked/x86_64/1\ncommand=hello\n")
        File(app, "files/bin").mkdirs()
        File(app, "files/bin/hello").apply { writeText("#!/bin/sh\necho linked-runtime-ok\n"); setExecutable(true, false) }
        val fp = FlatpakRunner(sys, LinuxDisplay(sys))
        val p = fp.command(id).start(); p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText(); val rc = p.waitFor()
        println(out)
        assertEquals(out, 0, rc)
        assertTrue(out.contains("linked-runtime-ok"))
    }

    /** Frame rate of the display pipeline with a constantly animating app (glxgears, software GL). */
    @Test fun k_frameRate() {
        assumeTrue(work != null && sys.isInstalled && sys.hasCommand("usr/bin/Xvnc"))
        if (!sys.hasCommand("usr/bin/glxgears")) assertEquals(0, sys.run("apt-get install -y mesa-utils", ::log))
        val d = LinuxDisplay(sys, 5, appMode = true)
        try {
            val size = System.getProperty("kdx.fpsSize") ?: "640x480"
            d.start(size.substringBefore('x').toInt(), size.substringAfter('x').toInt())
            d.appProcess = d.launch(System.getProperty("kdx.fpsApp") ?: "glxgears -geometry $size")
            Thread.sleep(3000)
            var rfb = RfbClient(port = d.port)
            for ((cu, native) in listOf(true to false, true to true)) {
                val fence = cu
                rfb = RfbClient(port = d.port, useContinuous = cu, useFence = fence, serverOrder = native); rfb.connect()
                val th = Thread { runCatching { rfb.loop() } }.apply { isDaemon = true; start() }
                Thread.sleep(2000)
                val f0 = rfb.frames; val t0 = System.nanoTime()
                Thread.sleep(4000)
                val fps = (rfb.frames - f0) * 1e9 / (System.nanoTime() - t0)
                println("FPS: %.1f (continuous=%s serverOrder=%s)".format(fps, cu, native))
                if (native) File(work, "fps.txt").writeText("%.1f".format(fps))
                if (!native) { rfb.close(); th.join(1000) }
            }
            val t = Thread {}
            d.log.filter { !it.startsWith("Xvnc") && !it.startsWith("dbus") }.takeLast(6).forEach { println("  APP| $it") }
            rfb.close(); t.join(1000)
            assertTrue("continuous updates negotiated", rfb.continuous)
        } finally {
            d.stop(); sys.run("pkill -f 'Xvnc :5'; pkill glxgears; pkill -f kdx-app.xml")
        }
    }

    /** Sound: apps play into DroidSystem's PulseAudio; the PCM stream KDX plays must carry that audio. */
    @Test fun l_sound() {
        assumeTrue(work != null && sys.isInstalled)
        sys.ensureLayer()
        if (!sys.hasCommand("usr/bin/pulseaudio")) assertEquals(0, sys.run("sound-setup", ::log))
        val audio = LinuxAudio(sys)
        try {
            audio.start()
            audio.log.forEach { println("  PA| $it") }
            assertTrue("sound server is up", audio.isRunning())
            val sock = java.net.Socket("127.0.0.1", LinuxAudio.PCM_PORT)
            val input = sock.getInputStream()
            fun nonSilent(ms: Long): Pair<Int, Int> {
                val end = System.currentTimeMillis() + ms; var total = 0; var loud = 0
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val n = input.read(buf); if (n < 0) break
                    for (i in 0 until n step 2) { total++; val v = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8); if (kotlin.math.abs(v.toShort().toInt()) > 1000) loud++ }
                }
                return total to loud
            }
            val (quietTotal, quietLoud) = nonSilent(800)
            println("idle: $quietTotal samples, $quietLoud loud")
            // play 2 s of a loud 440 Hz square wave from inside DroidSystem, like an app would
            val player = sys.command(listOf("/bin/bash", "-c",
                "perl -e 'for(\$i=0;\$i<96000;\$i++){\$v=((\$i/55)%2)?12000:-12000; print pack(\"ss\",\$v,\$v)}' | " +
                    "pacat --raw --format=s16le --rate=48000 --channels=2 --latency-msec=100"),
                env = mapOf("PULSE_SERVER" to LinuxAudio.PULSE_SERVER)).start()
            val (total, loud) = nonSilent(2500)
            println("playing: $total samples, $loud loud; player: " + player.inputStream.bufferedReader().readText().trim() + " rc=" + player.waitFor())
            sock.close()
            assertTrue("PCM stream flows", total > 100_000)
            assertTrue("sound from the app arrives (loud samples: $loud)", loud > total / 4)
        } finally {
            audio.stop(); sys.run("pkill pulseaudio")
        }
    }

    private fun procsMatching(marker: String): List<Int> = (File("/proc").listFiles() ?: emptyArray())
        .filter { it.name.all(Char::isDigit) }
        .filter { runCatching { String(File(it, "cmdline").readBytes()).contains(marker) }.getOrDefault(false) }
        .map { it.name.toInt() }

    /** Reopening a Flatpak app after closing its window (Firefox exited with code 0 in ~3 s on a phone). */
    @Test fun m_reopenFlatpak() {
        val id = System.getProperty("kdx.flatpak") ?: "org.mozilla.firefox"
        assumeTrue(work != null && sys.isInstalled && sys.hasCommand("usr/bin/Xvnc"))
        val fp = FlatpakRunner(sys, LinuxDisplay(sys))
        assumeTrue(fp.appDir(id) != null)
        val d1 = LinuxDisplay(sys, 6, appMode = true)
        val d2 = LinuxDisplay(sys, 7, appMode = true)
        try {
            d1.start(800, 600)
            d1.appProcess = fp.launch(id, on = d1)
            Thread.sleep(30_000)
            println("FIRST: alive=${d1.appProcess!!.isAlive} title=${d1.windowTitle()}")
            val ff = procsMatching("/app/lib/firefox").firstOrNull()
            println("TAG ${d1.tag}: matched=${org.kdx.shell.droid.Procs.withEnv("KDX_TAG=${d1.tag}").size}; firefox pid=$ff env has tag: " +
                (ff?.let { String(File("/proc/$it/environ").readBytes()).split('\u0000').filter { e -> e.startsWith("KDX_TAG") || e.startsWith("FLATPAK_ID") } }))
            d1.stop() // what closing the KDX window does
            Thread.sleep(3000)
            val left = procsMatching("/app/lib/firefox") + procsMatching("Xvnc :6")
            println("LEFTOVER after closing window: ${left.size} processes")
            d2.start(800, 600)
            val p2 = fp.launch(id, on = d2)
            d2.appProcess = p2
            Thread.sleep(15_000)
            val code = if (p2.isAlive) "running" else "exited code ${p2.exitValue()}"
            println("SECOND: $code title=${d2.windowTitle()}")
            d2.appLog().takeLast(8).forEach { println("  2| $it") }
            assertEquals("no processes left after closing the window", 0, left.size)
            assertTrue("reopened app keeps running ($code)", p2.isAlive)
            // an orphan KDX lost track of (window gone, display not stopped): launching again must clear it first
            val d3 = LinuxDisplay(sys, 8, appMode = true)
            try {
                d3.start(800, 600)
                fp.killLeftovers(id) // what KdxState.launchFlatpak does before every launch
                val p3 = fp.launch(id, on = d3); d3.appProcess = p3
                Thread.sleep(15_000)
                println("THIRD (after orphan on display 7): " + if (p3.isAlive) "running" else "exited ${p3.exitValue()}")
                assertTrue("launch after clearing an orphan keeps running", p3.isAlive)
            } finally { d3.stop() }
        } finally {
            d1.stop(); d2.stop()
            sys.run("pkill -f 'Xvnc :6'; pkill -f 'Xvnc :7'; pkill -f /app/lib/firefox; pkill -f kdx-app.xml")
        }
    }

    /** Auto mode with a struggling app: the AutoScaler should raise the frame rate by lowering the resolution. */
    @Test fun n_autoScale() {
        assumeTrue(work != null && sys.isInstalled && sys.hasCommand("usr/bin/glxgears"))
        val d = LinuxDisplay(sys, 9, appMode = true)
        try {
            val w0 = 1280; val h0 = 960
            d.start(w0, h0)
            d.appProcess = d.launch("glxgears")
            val rfb = RfbClient(port = d.port); rfb.connect()
            val t = Thread { runCatching { rfb.loop() } }.apply { isDaemon = true; start() }
            Thread.sleep(4000)
            val scaler = org.kdx.shell.droid.AutoScaler()
            val readings = mutableListOf<String>()
            var firstFps = -1; var lastFps = 0
            repeat(12) {
                val f0 = rfb.frames; Thread.sleep(2000)
                val fps = ((rfb.frames - f0) / 2).toInt()
                if (firstFps < 0) firstFps = fps
                lastFps = fps
                if (scaler.update(fps)) rfb.setDesktopSize((w0 * scaler.scale).toInt() / 8 * 8, (h0 * scaler.scale).toInt())
                readings += "$fps fps @ ${(scaler.scale * 100).toInt()}% (${rfb.width}x${rfb.height})"
            }
            println("AUTO: " + readings.joinToString(" | "))
            rfb.close(); t.join(1000)
            assertTrue("auto mode lowered the resolution", scaler.scale < 1f)
            assertTrue("frame rate went up ($firstFps -> $lastFps)", lastFps > firstFps)
        } finally {
            d.stop(); sys.run("pkill -f 'Xvnc :9'; pkill glxgears; pkill -f kdx-app.xml")
        }
    }

    /** The Debian side of Dolphin's new file types (the same commands DroidShell runs). */
    @Test fun o_fileTypes() {
        assumeTrue(work != null && sys.isInstalled)
        val shared = File(work, "home/files").apply { deleteTree(); mkdirs() } // = /home/user/files in DroidSystem
        // 1. .deb via Debian's apt (dependencies resolved, program runs)
        val pkg = File(shared, "hello-kdx").apply { File(this, "DEBIAN").mkdirs(); File(this, "usr/bin").mkdirs() }
        File(pkg, "DEBIAN/control").writeText("Package: hello-kdx\nVersion: 1.0\nArchitecture: all\nMaintainer: KDX <kdx@example.org>\nDepends: bash\nDescription: test\n")
        File(pkg, "usr/bin/hello-kdx").apply { writeText("#!/bin/sh\necho hello from a deb\n"); setExecutable(true, false) }
        assertEquals(0, sys.run("cd /home/user/files && dpkg-deb --build hello-kdx hello-kdx.deb >/dev/null && apt-get install -y /home/user/files/hello-kdx.deb", ::log))
        assertEquals("hello from a deb", sys.query("hello-kdx").second.trim())
        // 2. a Python script: interpreter installed on first use, then run
        File(shared, "hi.py").writeText("print('python says hi', 6*7)\n")
        val (_, pout) = sys.query("command -v python3 >/dev/null || { apt-get update -qq && apt-get install -y python3 >/dev/null; } && python3 /home/user/files/hi.py")
        println("PYTHON: ${pout.lines().lastOrNull { it.isNotBlank() }}")
        assertTrue(pout.contains("python says hi 42"))
        // 3. a .tar.zst archive via bsdtar (installed on first use)
        assertEquals(0, sys.run("command -v bsdtar >/dev/null || apt-get install -y libarchive-tools zstd; " +
            "cd /home/user/files && mkdir -p z && echo zstd-ok > z/inside.txt && tar --zstd -cf z.tar.zst z && rm -r z && " +
            "mkdir -p out && bsdtar -xvf z.tar.zst -C out", ::log))
        assertEquals("zstd-ok", File(shared, "out/z/inside.txt").readText().trim())
        // 4. a program copied to private storage runs (shared storage can't run programs on Android)
        val prog = File(sys.root, "root/bin/hello-prog").apply { parentFile?.mkdirs(); File(sys.root, "usr/bin/true").copyTo(this, overwrite = true); setExecutable(true, false) }
        assertEquals(0, sys.query("/root/bin/hello-prog").first)
        prog.delete()
    }

    /** Real KDE Plasma: installs it (if needed), starts a whole session and checks it draws a desktop. */
    @Test fun p_plasma() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.plasma") != null)
        if (!org.kdx.shell.droid.KdePlasma.isInstalled(sys)) {
            File(sys.root, "usr/local/bin/plasma-setup").apply { writeText(org.kdx.shell.droid.KdePlasma.setupScript()); setExecutable(true, false) }
            assertEquals(0, sys.run("plasma-setup", ::log))
        }
        org.kdx.shell.droid.KdePlasma.writeDefaults(sys)
        val d = LinuxDisplay(sys, 30, desktop = true)
        try {
            d.start(1280, 800)
            d.launch(org.kdx.shell.droid.KdePlasma.COMMAND, org.kdx.shell.droid.KdePlasma.env)
            val wait = (System.getProperty("kdx.plasma").toLongOrNull() ?: 60) * 1000
            var t = 0L
            while (t < wait) { Thread.sleep(10_000); t += 10_000; snapshot(d, "plasma-${t / 1000}s", 1500) }
            val px = snapshot(d, "plasma", 2000)
            println("distinct colours: ${px.distinct().size}")
            val (_, ps) = sys.query("ps -eo pid,rss,comm | grep -Ei 'plasma|kwin|ksmserver|kded|dolphin' || true")
            println(ps)
            assertTrue("plasmashell runs", ps.contains("plasmashell"))
            assertTrue("kwin runs", ps.contains("kwin"))
            assertTrue("desktop is drawn", px.distinct().size > 200)
        } finally {
            d.log.forEach { println("  X| $it") }
            d.stop()
        }
    }

    /**
     * A Flatpak app started from inside real KDE Plasma (KDE's own launcher, like a click in Kickoff) goes through
     * DroidSystem's flatpak wrapper to KDX, which starts it on Plasma's screen. Uses the real Bazaar if installed.
     */
    @Test fun q_flatpakFromPlasma() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.plasma") != null && org.kdx.shell.droid.KdePlasma.isInstalled(sys))
        val id = listOf("io.github.kolunmi.Bazaar", "org.mozilla.firefox").first { File(sys.root, "root/.local/share/flatpak/app/$it").isDirectory }
        org.kdx.shell.droid.KdePlasma.writeDefaults(sys)
        val d = LinuxDisplay(sys, 31, desktop = true)
        val runner = org.kdx.shell.droid.FlatpakRunner(sys, d)
        val got = java.util.concurrent.LinkedBlockingQueue<org.kdx.shell.droid.LinuxLauncher.Request>()
        val launcher = org.kdx.shell.droid.LinuxLauncher(sys) { r ->
            got.put(r)
            if (r.display == 31) { runner.launch(r.id, r.args, on = d, commandOverride = r.command); null } else "wrong display ${r.display}"
        }
        try {
            launcher.start()
            org.kdx.shell.droid.LinuxLauncher.installWrapper(sys)
            assertTrue("wrapper installed", File(sys.root, "usr/bin/flatpak").readText().contains("KDX-DroidSystem flatpak wrapper"))
            d.start(1280, 800)
            d.launch(org.kdx.shell.droid.KdePlasma.COMMAND, org.kdx.shell.droid.KdePlasma.env)
            Thread.sleep(15_000)
            // other flatpak commands still reach the real flatpak
            val (rc, list) = sys.query("flatpak list --app --columns=application")
            assertEquals(0, rc); assertTrue(list, list.contains(id))
            // what Plasma does when you click the app in Kickoff
            val desktopFile = "/root/.local/share/flatpak/exports/share/applications/$id.desktop"
            val env = (d.env + org.kdx.shell.droid.KdePlasma.env).entries.joinToString(" ") { "${it.key}='${it.value}'" }
            println(sys.query("env $env kioclient exec $desktopFile 2>&1; echo kioclient rc=$?").second)
            val req = got.poll(30, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull("KDX got the request", req)
            println("request: $req")
            assertEquals(id, req!!.id); assertEquals(31, req.display)
            Thread.sleep(20_000)
            val px = snapshot(d, "plasma-flatpak", 2000)
            assertTrue(px.distinct().size > 200)
            val (_, ps) = sys.query("ps -eo comm,args | grep -i '${id.substringAfterLast('.').lowercase()}' | grep -v grep || true")
            println(ps)
            assertTrue("the app runs", ps.isNotBlank())
        } finally {
            d.log.filter { !it.startsWith("Xvnc") }.takeLast(40).forEach { println("  X| $it") }
            launcher.stop(); d.stop()
        }
    }

    /** bazaar-setup as DroidSystem installs it: the real Bazaar from Flathub, plus an icon on Plasma's desktop. */
    @Test fun r_bazaarSetup() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.plasma") != null)
        sys.applyDroidLayer()
        assertTrue(sys.hasCommand("usr/local/bin/bazaar-setup"))
        val rc = sys.run("bazaar-setup", ::log)
        assertEquals(0, rc)
        assertTrue(org.kdx.shell.droid.KdePlasma.bazaarInstalled(sys))
        assertTrue("on Plasma's desktop", File(sys.root, "root/Desktop/${org.kdx.shell.droid.KdePlasma.BAZAAR_ID}.desktop").isFile)
        // running it again (already installed) is fine too
        assertEquals(0, sys.run("bazaar-setup", ::log))
    }

    /** Touch gestures → TouchTranslator → VNC → the X server: xev shows which mouse buttons X apps receive. */
    @Test fun s_touchReachesX() {
        assumeTrue(work != null && sys.isInstalled && sys.hasCommand("usr/bin/xev"))
        val d = LinuxDisplay(sys, 40, desktop = true)
        try {
            d.start(800, 600)
            val xev = d.launch("xev -geometry 800x600+0+0 -event button")
            Thread.sleep(2500)
            val rfb = RfbClient(port = d.port); rfb.connect()
            val loop = Thread { runCatching { rfb.loop() } }.apply { isDaemon = true; start() }
            val t = org.kdx.shell.apps.TouchTranslator(slopPx = 20f, toFb = { it.x.toInt() to it.y.toInt() }, out = { x, y, m -> rfb.pointer(x, y, m) }, scrollStepPx = { 50f })
            fun o(x: Int, y: Int) = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())
            t.down(1, o(400, 300), 0); t.up(1, o(400, 300), 80)                                   // tap → button 1
            t.down(1, o(300, 300), 1000); t.down(2, o(500, 300), 1010); t.up(1, o(300, 300), 1080); t.up(2, o(500, 300), 1090) // → button 3
            t.down(1, o(400, 500), 2000); for (i in 1..12) t.move(1, o(400, 500 - i * 15), 2000L + i * 16); t.up(1, o(400, 320), 2400) // drag up → button 5
            Thread.sleep(1500)
            rfb.close(); loop.join(1000)
            val text = d.log.filter { it.startsWith("xev:") }.joinToString("\n")
            val buttons = Regex("ButtonPress.*?button (\\d)", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1].toInt() }.toList()
            println("X saw button presses: $buttons")
            assertEquals(1, buttons.first())
            assertTrue("right-click", 3 in buttons)
            assertTrue("scroll (wheel down)", buttons.count { it == 5 } >= 2)
        } finally { d.stop() }
    }

    /** Typed text (Latin, accented, emoji, Enter) and a Ctrl shortcut reach X as the right keysyms. */
    @Test fun t_keysReachX() {
        assumeTrue(work != null && sys.isInstalled && sys.hasCommand("usr/bin/xev"))
        val d = LinuxDisplay(sys, 41, desktop = true)
        try {
            d.start(800, 600)
            d.launch("xev -geometry 800x600+0+0 -event keyboard")
            Thread.sleep(2500)
            val rfb = RfbClient(port = d.port); rfb.connect()
            val loop = Thread { runCatching { rfb.loop() } }.apply { isDaemon = true; start() }
            rfb.pointer(400, 300, 0) // pointer over the window: it has keyboard focus
            Thread.sleep(300)
            rfb.type("aé😀\n")
            rfb.key(RfbClient.KEY_CTRL, true); rfb.tapKey(org.kdx.shell.apps.KeyMap.shortcutKeysym('c'.code)!!); rfb.key(RfbClient.KEY_CTRL, false)
            Thread.sleep(1500)
            rfb.close(); loop.join(1000)
            val text = d.log.filter { it.startsWith("xev:") }.joinToString("\n")
            val pressed = Regex("KeyPress event.*?keysym (0x[0-9a-f]+), (\\w+)", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[2] }.toList()
            println("X saw key presses: $pressed")
            assertEquals(listOf("a", "eacute", "U0001F600", "Return", "Control_L", "c"), pressed)
        } finally { d.stop() }
    }

    /** KDX's start-up cleanup of leftover Linux programs must not kill a display server KDX just started. */
    @Test fun u_cleanupSparesNewDisplay() {
        assumeTrue(work != null && sys.isInstalled && sys.hasCommand("usr/bin/Xvnc"))
        val d = LinuxDisplay(sys, 42, desktop = true)
        try {
            d.start(800, 600)
            org.kdx.shell.droid.Procs.killOrphans("HOSTNAME=droidsystem") // what KDX runs when DroidSystem attaches
            Thread.sleep(500)
            assertTrue("display server survived the cleanup", d.waitForX(3000))
            val (rc, out) = sys.query("DISPLAY=:42 xdpyinfo | head -3")
            println(out)
            assertEquals("X programs can connect", 0, rc)
        } finally { d.stop() }
    }

    /** windsystem-setup as DroidSystem installs it (x86_64 here: Debian's Wine, 64- and 32-bit). */
    @Test fun v_windSystemSetup() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.wind") != null)
        sys.applyDroidLayer()
        if (!org.kdx.shell.droid.WindSystem.isInstalled(sys)) assertEquals(0, sys.run("windsystem-setup", ::log))
        assertTrue(org.kdx.shell.droid.WindSystem.isInstalled(sys))
        val (rc, out) = sys.query("windsystem-wine --version")
        println(out); assertEquals(0, rc); assertTrue(out.contains("wine-"))
    }

    /**
     * A WindSystem session with the real KDX code: drive C: prepared, settings applied (Windows 10), Notepad opens
     * and shows up in the taskbar's window list, and a crashing Windows program produces a crash report.
     */
    @Test fun w_windSystemSession() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.wind") != null && org.kdx.shell.droid.WindSystem.isInstalled(sys))
        val shared = File(work, "WindSystem").apply { mkdirs() }
        // like /storage on phones: Android/WindSystem is visible inside DroidSystem under the same path
        val sys = DebianSystem(this.sys.root, this.sys.proot.copy(binds = this.sys.proot.binds + (shared.path to shared.path)), File(work, "home"), arch)
        System.getProperty("kdx.crashExe")?.let { File(it).copyTo(File(shared, "Programs/crashme.exe").apply { parentFile?.mkdirs() }, overwrite = true) }
        if (System.getProperty("kdx.windFresh") != null) File(sys.root, "root/.windsystem").takeIf { it.exists() }?.deleteTree() // first start from scratch
        val d = LinuxDisplay(sys, 43, desktop = true)
        val windows = java.util.concurrent.atomic.AtomicReference<List<Pair<String, String>>>(emptyList())
        val crashes = java.util.concurrent.LinkedBlockingQueue<Pair<org.kdx.shell.droid.WindSystem.Crash, File?>>()
        try {
            d.start(1280, 800)
            val s = org.kdx.shell.droid.WindSession(sys, d, shared, org.kdx.shell.droid.WindSystem.Config(), 120,
                onCrash = { c, f -> crashes.put(c to f) }, onWindows = { l, _ -> windows.set(l) }, status = { st -> st?.let { log(it) } })
            val t0 = System.currentTimeMillis()
            s.start()
            println("drive C: ready in ${(System.currentTimeMillis() - t0) / 1000} s")
            val (rc, ver) = sys.query("WINEPREFIX=${org.kdx.shell.droid.WindSystem.PREFIX} windsystem-wine reg query 'HKCU\\Software\\Wine' /v Version 2>/dev/null")
            println(ver); assertTrue("reports Windows 10: $ver", ver.contains("win10"))
            assertTrue("D: is Android/WindSystem", File(sys.root, "root/.windsystem/dosdevices/d:").let { java.nio.file.Files.isSymbolicLink(it.toPath()) })
            s.run(org.kdx.shell.droid.WindSystem.BUILTIN.first { it.name == "Notepad" })
            var waited = 0
            while (windows.get().none { it.second.contains("Notepad") } && waited < (if (arch == "arm64") 900 else 60)) { Thread.sleep(1000); waited++ }
            println("taskbar windows: ${windows.get()}")
            assertTrue("Notepad in the taskbar", windows.get().any { it.second.contains("Notepad") })
            snapshot(d, "windsystem", 1500)
            val progs = org.kdx.shell.droid.WindSystem.installedPrograms(sys, shared)
            println("start menu programs: ${progs.map { it.name }}")
            progs.firstOrNull { it.name == "crashme" }?.let { p ->
                s.run(p)
                val (crash, report) = crashes.poll(if (arch == "arm64") 900L else 90L, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("no crash reported")
                println("CRASH: ${crash.summary}\n" + crash.details.take(12).joinToString("\n"))
                assertTrue(crash.summary.isNotBlank())
                assertTrue("report saved", report?.isFile == true)
            }
            s.shutdown()
        } finally { d.stop() }
    }

    /** WindSystem's self-check (shown when it can't start) runs and reports Wine's version. */
    @Test fun x_windSystemDiagnose() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.wind") != null && org.kdx.shell.droid.WindSystem.isInstalled(sys))
        org.kdx.shell.droid.WindSystem.ensureWrappers(sys)
        val d = org.kdx.shell.droid.WindSystem.diagnose(sys)
        println(d)
        assertTrue(d.contains("Device:") && d.contains("wine-10") && d.contains("(exit 0)"))
    }

    /** Windows' title bars in WindSystem: drag moves the window, the maximize button and a double-click maximize. */
    @Test fun y_windowFrames() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.wind") != null && org.kdx.shell.droid.WindSystem.isInstalled(sys))
        val shared = File(work, "WindSystem").apply { mkdirs() }
        val sys = DebianSystem(this.sys.root, this.sys.proot.copy(binds = this.sys.proot.binds + (shared.path to shared.path)), File(work, "home"), arch)
        val d = LinuxDisplay(sys, 44, desktop = true)
        val windows = java.util.concurrent.atomic.AtomicReference<List<Pair<String, String>>>(emptyList())
        try {
            d.start(1280, 800)
            val s = org.kdx.shell.droid.WindSession(sys, d, shared, org.kdx.shell.droid.WindSystem.Config(), 96,
                onCrash = { _, _ -> }, onWindows = { l, _ -> windows.set(l) }, status = {})
            s.start()
            s.run(org.kdx.shell.droid.WindSystem.BUILTIN.first { it.name == "Notepad" })
            var waited = 0
            while (windows.get().none { it.second.contains("Notepad") } && waited < 60) { Thread.sleep(1000); waited++ }
            val id = windows.get().first { it.second.contains("Notepad") }.first
            fun geo(): String = sys.query("DISPLAY=:44 xdotool getwindowgeometry $id").second.lines().filter { it.contains("Position") || it.contains("Geometry") }.joinToString(" ").trim()
            Thread.sleep(2000)
            val g0 = geo(); println("start:     $g0")
            val rfb = RfbClient(port = d.port); rfb.connect()
            val loop = Thread { runCatching { rfb.loop() } }.apply { isDaemon = true; start() }
            val pos = Regex("Position: (\\d+),(\\d+)").find(g0)!!.groupValues.drop(1).map { it.toInt() }
            val size = Regex("Geometry: (\\d+)x(\\d+)").find(g0)!!.groupValues.drop(1).map { it.toInt() }
            // the phone's way: touches → TouchTranslator (WindSystem's mouse-style touch) → VNC
            val t = org.kdx.shell.apps.TouchTranslator(slopPx = 20f, toFb = { it.x.toInt() to it.y.toInt() }, out = { x, y, m -> rfb.pointer(x, y, m) })
            t.touchMode = { false }
            t.onDoubleTap = { x, y -> println("double-tap at $x,$y → title bar: ${s.doubleTapAt(x, y)}") }
            fun o(x: Int, y: Int) = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())
            var now = 10_000L
            fun tap(x: Int, y: Int) { t.down(1, o(x, y), now); now += 60; t.up(1, o(x, y), now); now += 150; Thread.sleep(250) }
            fun posOf(g: String) = Regex("Position: (\\d+),(\\d+)").find(g)!!.groupValues.drop(1).map { it.toInt() }
            fun sizeOf(g: String) = Regex("Geometry: (\\d+)x(\\d+)").find(g)!!.groupValues.drop(1).map { it.toInt() }
            val tx = pos[0] + size[0] / 3; val ty = pos[1] + 14
            // 1. drag the title bar with one finger
            t.down(1, o(tx, ty), now); for (i in 1..20) { now += 30; t.move(1, o(tx + i * 10, ty + i * 8), now); Thread.sleep(40) }
            now += 30; t.up(1, o(tx + 200, ty + 160), now); now += 1000; Thread.sleep(1500)
            val g1 = geo(); println("dragged:   $g1")
            snapshot(d, "frames-dragged", 800)
            // 2. double-tap the title bar: maximize
            val p1 = posOf(g1)
            Thread.sleep(2000) // the window list (with positions) updates every 1.5 s
            tap(p1[0] + size[0] / 3, p1[1] + 28); tap(p1[0] + size[0] / 3, p1[1] + 28); Thread.sleep(2500)
            val g2 = geo(); println("double-tap: $g2")
            // 3. double-tap again: restore
            Thread.sleep(1500); tap(400, 20); tap(400, 20); Thread.sleep(2500)
            val g3 = geo(); println("restored:  $g3")
            // 4. the maximize button (touch-sized frames: buttons ~30 px wide)
            val p3 = posOf(g3); val s3 = sizeOf(g3)
            now += 1000; tap(p3[0] + s3[0] - 75, p3[1] + 28); Thread.sleep(2000)
            val g4 = geo(); println("button:    $g4")
            snapshot(d, "frames-maximized", 800)
            assertTrue("double-tap maximizes: $g2", g2.contains("1280x"))
            assertTrue("double-tap restores: $g3", !g3.contains("1280x"))
            assertTrue("maximize button: $g4", g4.contains("1280x"))
            rfb.close(); loop.join(1000)
            assertNotEquals("title bar drag moves the window", g0, g1)
            s.shutdown()
        } finally { d.stop() }
    }

    /** If Wine doesn't draw a window's title bar (Decorated not N), Openbox draws one: windows always have a title bar. */
    @Test fun z_titleBarFallback() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.wind") != null && org.kdx.shell.droid.WindSystem.isInstalled(sys))
        val shared = File(work, "WindSystem").apply { mkdirs() }
        val sys = DebianSystem(this.sys.root, this.sys.proot.copy(binds = this.sys.proot.binds + (shared.path to shared.path)), File(work, "home"), arch)
        val d = LinuxDisplay(sys, 45, desktop = true)
        val windows = java.util.concurrent.atomic.AtomicReference<List<Pair<String, String>>>(emptyList())
        try {
            d.start(1280, 800)
            val s = org.kdx.shell.droid.WindSession(sys, d, shared, org.kdx.shell.droid.WindSystem.Config(), 96,
                onCrash = { _, _ -> }, onWindows = { l, _ -> windows.set(l) }, status = {})
            s.start()
            // simulate the setting not taking effect: Wine leaves the frame to the window manager
            sys.run("WINEPREFIX=${org.kdx.shell.droid.WindSystem.PREFIX} windsystem-wine reg add 'HKCU\\Software\\Wine\\X11 Driver' /v Decorated /d Y /f >/dev/null 2>&1")
            s.run(org.kdx.shell.droid.WindSystem.BUILTIN.first { it.name == "Notepad" })
            var waited = 0
            while (windows.get().none { it.second.contains("Notepad") } && waited < 60) { Thread.sleep(1000); waited++ }
            Thread.sleep(2500)
            snapshot(d, "titlebar-fallback", 800)
            val id = windows.get().first { it.second.contains("Notepad") }.first
            // Openbox frames it: the client window then has a frame parent with an offset (title bar height)
            val (_, tree) = sys.query("DISPLAY=:45 xwininfo -id $id | grep -E 'Relative upper-left Y'")
            println("frame offset: $tree")
            assertTrue("Openbox title bar above the window: $tree", Regex("Y:\\s+(\\d+)").find(tree)!!.groupValues[1].toInt() > 10)
            s.shutdown()
        } finally {
            sys.run("WINEPREFIX=${org.kdx.shell.droid.WindSystem.PREFIX} windsystem-wine reg add 'HKCU\\Software\\Wine\\X11 Driver' /v Decorated /d N /f >/dev/null 2>&1")
            d.stop()
        }
    }

    /** DOSBox-X: installed in the background for older WindSystems, DOS programs in the DOS folder run in it. */
    @Test fun za_dosbox() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.wind") != null && org.kdx.shell.droid.WindSystem.isInstalled(sys))
        val shared = File(work, "WindSystem").apply { mkdirs() }
        // a tiny DOS program: prints a line, waits for a key
        val msg = "Hello from DOS in WindSystem!$".toByteArray()
        val code = byteArrayOf(0xB4.toByte(), 0x09, 0xBA.toByte(), 0x0C, 0x01, 0xCD.toByte(), 0x21, 0xB4.toByte(), 0x00, 0xCD.toByte(), 0x16, 0xC3.toByte())
        File(shared, "DOS").apply { mkdirs() }.resolve("HELLO.COM").writeBytes(code + msg)
        val sys = DebianSystem(this.sys.root, this.sys.proot.copy(binds = this.sys.proot.binds + (shared.path to shared.path)), File(work, "home"), arch).apply { extraEnv = this@DebianIntegrationTest.sys.extraEnv }
        val d = LinuxDisplay(sys, 46, desktop = true)
        val windows = java.util.concurrent.atomic.AtomicReference<List<Pair<String, String>>>(emptyList())
        try {
            d.start(1280, 800)
            val s = org.kdx.shell.droid.WindSession(sys, d, shared, org.kdx.shell.droid.WindSystem.Config(), 96,
                onCrash = { c, _ -> println("CRASH ${c.summary}") }, onWindows = { l, _ -> windows.set(l) }, status = {})
            s.start()
            var waited = 0
            while (!org.kdx.shell.droid.WindSystem.dosboxInstalled(sys) && waited < 600) { Thread.sleep(2000); waited += 2 }
            println("DOSBox-X installed: ${org.kdx.shell.droid.WindSystem.dosboxInstalled(sys)} after ${waited}s")
            assertTrue(org.kdx.shell.droid.WindSystem.dosboxInstalled(sys))
            val progs = org.kdx.shell.droid.WindSystem.installedPrograms(sys, shared)
            val hello = progs.first { it.category == "DOS programs" }
            println("DOS program in the start menu: ${hello.name} -> ${hello.command}")
            s.run(hello)
            waited = 0
            while (windows.get().none { it.second.contains("DOSBox", true) } && waited < 60) { Thread.sleep(1000); waited++ }
            println("taskbar: ${windows.get()}")
            Thread.sleep(3000)
            snapshot(d, "dosbox", 800)
            assertTrue("DOSBox-X window", windows.get().any { it.second.contains("DOSBox", true) })
            s.shutdown()
        } finally { d.stop() }
    }

    /** Storage packing: scan, clean up, pack and unpack WindSystem's drive C: and KDE Plasma – nothing lost. */
    @Test fun zb_storagePacking() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.pack") != null)
        val P = org.kdx.shell.droid.StoragePacker
        P.ensureTool(sys)
        fun mb(b: Long?) = "%.0f MB".format((b ?: 0) / 1048576.0)
        val before = P.scan(sys)
        println("scan: " + before.entries.joinToString { "${it.key}=${mb(it.value)}" })
        assertEquals(0, sys.run(P.CLEANUPS.first { it.id == "apt-cache" }.command, ::log))
        val ids = System.getProperty("kdx.pack").split(',')
        for (id in ids) {
            val sum = { sys.query(when (id) {
                "plasma" -> "droidsystem-pack size plasma; md5sum /usr/bin/plasmashell 2>&1"
                else -> "cd ${org.kdx.shell.droid.WindSystem.PREFIX} 2>/dev/null && find . -type f -print0 | sort -z | xargs -0 md5sum | md5sum"
            }).second.trim() }
            val orig = sum()
            val t0 = System.currentTimeMillis()
            assertEquals(0, sys.run("droidsystem-pack pack $id", ::log))
            println("$id: ${mb(before["pack:$id"])} → packed ${mb(P.packedSize(sys, id))} in ${(System.currentTimeMillis() - t0) / 1000} s")
            assertTrue(P.isPacked(sys, id))
            if (id == "plasma") {
                assertFalse("plasmashell is packed away", sys.hasCommand("usr/bin/plasmashell"))
                println("held while packed: " + sys.query("apt-mark showhold | wc -l").second.trim())
            } else assertFalse(File(sys.root, org.kdx.shell.droid.WindSystem.PREFIX.removePrefix("/")).exists())
            val t1 = System.currentTimeMillis()
            assertEquals(0, sys.run("droidsystem-pack unpack $id", ::log))
            println("$id unpacked in ${(System.currentTimeMillis() - t1) / 1000} s")
            assertFalse(P.isPacked(sys, id))
            assertEquals("everything back exactly", orig, sum())
            if (id == "plasma") assertEquals("no holds left", "0", sys.query("apt-mark showhold | wc -l").second.trim())
        }
    }

    /** Automatic saving with (faked) low space: cleans up, packs WindSystem's idle drive C:, which then unpacks fine. */
    @Test fun zc_autoSave() {
        assumeTrue(work != null && sys.isInstalled && System.getProperty("kdx.pack") != null && File(sys.root, "root/.windsystem").exists())
        val P = org.kdx.shell.droid.StoragePacker
        val GB = 1024L * 1024 * 1024
        val free = { if (P.isPacked(sys, "windsystem-c")) 3 * GB else GB } // "low" until drive C: is packed
        val r = P.autoSave(sys, free, 2 * GB, lastUsed = { 0L }, inUse = { it != "windsystem-c" }, log = ::log)
        println("auto save: $r")
        assertEquals(listOf("windsystem-c"), r.packed)
        assertTrue(P.isPacked(sys, "windsystem-c"))
        assertEquals(0, sys.run("droidsystem-pack unpack windsystem-c", ::log))
        assertTrue(File(sys.root, "root/.windsystem/system.reg").isFile)
    }
}
