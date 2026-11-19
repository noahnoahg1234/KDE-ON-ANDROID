package org.kdx.shell

import org.junit.Assert.*
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
import java.io.File

/**
 * Builds a real DroidSystem 2 (Debian) on this machine with the host's proot and exercises it.
 * Slow and networked, so it only runs with -Dkdx.integration=/path/to/workdir.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DebianIntegrationTest {
    private val work = System.getProperty("kdx.integration")?.let { File(it) }
    private val sys by lazy {
        DebianSystem(File(work, "rootfs"), ProotConfig("/usr/bin/proot"), File(work, "home"), "amd64",
            mirror = "http://deb.debian.org/debian", securityMirror = "http://deb.debian.org/debian-security")
    }
    private fun log(s: String) = println("  | $s")

    @Test fun a_bootstrap() {
        assumeTrue(work != null)
        if (sys.isInstalled) return
        var last = ""
        sys.bootstrap(DebianRepo(mirror = "http://deb.debian.org/debian", arch = "amd64"), File(work, "cache"), { p, m -> if (m != last) { println("[%3.0f%%] %s".format(p * 100, m)); last = m } }, ::log)
        assertTrue(sys.isInstalled)
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
}
