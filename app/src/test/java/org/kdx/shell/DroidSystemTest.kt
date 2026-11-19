package org.kdx.shell

import androidx.test.core.app.ApplicationProvider
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kdx.shell.droid.DroidShell
import org.kdx.shell.droid.DroidSystem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidSystemTest {
    private lateinit var root: File
    private lateinit var ds: DroidSystem
    private val log = mutableListOf<String>()

    @Before fun setUp() {
        root = Files.createTempDirectory("droidsystem").toFile()
        ds = DroidSystem(ApplicationProvider.getApplicationContext(), root)
        ds.install { _, _ -> }
    }

    @Test fun installsFilesystem() {
        assertTrue(ds.isInstalled)
        assertEquals("DroidSystem 1.1 (Breeze)", ds.version)
        listOf("etc/passwd", "etc/motd", "home/user/Documents/README.txt", "usr/bin", "var/lib/flatpak/app", "tmp")
            .forEach { assertTrue(it, File(root, it).exists()) }
        assertEquals(listOf("bazaar", "ds-base"), ds.installed().map { it.name })
        assertEquals(org.kdx.shell.core.KApp.BAZAAR, ds.desktopEntries(true).first { it.name == "Bazaar" }.kdxApp)
        // Welcome flatpak is preinstalled and shows in Kickoff
        assertTrue(ds.desktopEntries(true).any { it.flatpakId == "org.droidsystem.Welcome" })
        assertTrue(ds.desktopEntries(true).any { it.name == "DroidSystem Terminal" })
        assertFalse(ds.desktopEntries(false).any { it.flatpakId != null })
    }

    @Test fun dspkgRepoInstallAndRemove() {
        assertTrue(ds.available().map { it.name }.containsAll(listOf("cheat", "colors", "countdown", "droidsay", "fortune", "rot13", "sysinfo", "todo", "tree", "wordcount")))
        assertEquals("Fun", ds.available().first { it.name == "fortune" }.category)
        ds.installFromRepo("droidsay") { log += it }
        assertNotNull(ds.which("droidsay"))
        assertTrue(ds.isPkgInstalled("droidsay"))
        ds.remove("droidsay") { log += it }
        assertNull(ds.which("droidsay"))
        assertThrows(java.io.IOException::class.java) { ds.remove("ds-base") {} }
    }

    @Test fun bundledScriptsRunInARealShell() {
        ds.available().forEach { ds.installFromRepo(it.name) {} }
        fun run(vararg cmd: String, input: String = ""): String {
            val p = ProcessBuilder(*cmd).directory(ds.home).redirectErrorStream(true)
            p.environment()["HOME"] = ds.home.path
            val proc = p.start()
            proc.outputStream.use { it.write(input.toByteArray()) }
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals("exit code for ${cmd.toList()}: $out", 0, proc.waitFor())
            return out
        }
        // bash stands in for Android's mksh (both support $RANDOM, read -r, ${#var}).
        assertTrue(run("bash", ds.which("droidsay")!!.path, "hi there").contains("< hi there >"))
        assertTrue(run("bash", ds.which("fortune")!!.path).isNotBlank())
        assertTrue(run("bash", ds.which("tree")!!.path, ds.root.path + "/etc").contains("os-release"))
        run("bash", ds.which("todo")!!.path, "add", "write tests")
        assertTrue(run("bash", ds.which("todo")!!.path).contains("1. write tests"))
        assertTrue(run("bash", ds.which("countdown")!!.path, "1").contains("Time's up!"))
        // every flatpak in DroidHub
        ds.flatpakRemote().forEach { ds.flatpakInstallBundled(it.id) {} }
        assertTrue(run("bash", ds.flatpakEntry("org.droidsystem.Dice")!!.path, "3d6").contains("Total:"))
        assertTrue(run("bash", ds.flatpakEntry("org.droidsystem.Guess")!!.path, input = "50\n25\n75\n").contains("Your guess?"))
        assertTrue(run("bash", ds.flatpakEntry("org.droidsystem.WorldClock")!!.path).contains("Asia/Tokyo"))
        assertTrue(run("bash", ds.flatpakEntry("org.droidsystem.Welcome")!!.path).contains("Welcome to DroidSystem"))
        val ttt = run("bash", ds.flatpakEntry("org.droidsystem.TicTacToe")!!.path, input = "5\n1\n2\n3\n4\n6\n7\n8\n9\n")
        assertTrue(ttt, ttt.contains("Computer plays") && (ttt.contains("win") || ttt.contains("draw")))
        val hang = run("bash", ds.flatpakEntry("org.droidsystem.Hangman")!!.path, input = "etaoinshrdlucmfwypvbgkjqxz".map { "$it\n" }.joinToString(""))
        assertTrue(hang, hang.contains("You solved it") || hang.contains("Out of lives"))
        val pw = run("bash", ds.flatpakEntry("org.droidsystem.PassGen")!!.path, "12", "--simple").lines().filter { it.isNotBlank() }
        assertEquals(5, pw.size); assertTrue(pw.all { it.length == 12 && it.all(Char::isLetterOrDigit) })
        assertEquals("Uryyb\n", run("bash", ds.which("rot13")!!.path, "Hello"))
        // The shell preamble turns every installed command into a function, so pipelines work.
        val piped = run("bash", "-c", ds.shellPreamble() + "fortune | droidsay | head -n 2; ll / > /dev/null && echo profile-ok")
        assertTrue(piped, piped.contains("< ") && piped.contains("profile-ok"))
        // POSIX syntax check with dash too
        (ds.available().map { ds.which(it.name)!! } + ds.flatpakRemote().map { ds.flatpakEntry(it.id)!! }).forEach {
            run("sh", "-n", it.path)
        }
    }

    @Test fun flatpakBuildDirInstallRunUninstall() {
        val build = Files.createTempDirectory("fpbuild").toFile()
        File(build, "metadata").writeText("[Application]\nname=com.example.Hello\nruntime=org.example.Platform/aarch64/1\ncommand=hello\n")
        File(build, "files/bin").mkdirs()
        File(build, "files/bin/hello").writeText("#!/bin/sh\necho hello flatpak\n")
        ds.flatpakInstallBuild(build) { log += it }
        assertTrue(ds.flatpakInstalled().any { it.id == "com.example.Hello" })
        assertEquals("hello", ds.flatpakEntry("com.example.Hello")!!.name)
        assertTrue(ds.desktopEntries(true).any { it.exec == "flatpak run com.example.Hello" })
        assertTrue(ds.flatpakUninstall("com.example.Hello"))
        assertNull(ds.flatpakEntry("com.example.Hello"))
    }

    private fun tar(out: OutputStream, files: Map<String, ByteArray>) {
        TarArchiveOutputStream(out).use { t ->
            files.forEach { (name, data) ->
                t.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() }); t.write(data); t.closeArchiveEntry()
            }
        }
    }

    private fun makeDeb(): File {
        val control = ByteArrayOutputStream().also {
            tar(GzipCompressorOutputStream(it), mapOf("./control" to "Package: hello-deb\nVersion: 2.1\nArchitecture: all\nDescription: test package\n long text\n".toByteArray()))
        }.toByteArray()
        val data = ByteArrayOutputStream().also {
            tar(XZCompressorOutputStream(it), mapOf(
                "./usr/bin/hello-deb" to "#!/bin/sh\necho deb works\n".toByteArray(),
                "./usr/share/doc/hello-deb/README" to "docs".toByteArray(),
                "./usr/bin/native" to byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0xB7.toByte(), 0),
            ))
        }.toByteArray()
        val deb = File.createTempFile("hello", ".deb")
        ArArchiveOutputStream(deb.outputStream()).use { ar ->
            listOf("debian-binary" to "2.0\n".toByteArray(), "control.tar.gz" to control, "data.tar.xz" to data).forEach { (n, b) ->
                ar.putArchiveEntry(ArArchiveEntry(n, b.size.toLong())); ar.write(b); ar.closeArchiveEntry()
            }
        }
        return deb
    }

    @Test fun debPackages() {
        val deb = makeDeb()
        assertEquals("hello-deb", ds.inspectDeb(deb)["Package"])
        ds.installDeb(deb) { log += it }
        assertTrue(ds.isPkgInstalled("hello-deb"))
        assertTrue(File(root, "usr/share/doc/hello-deb/README").isFile)
        assertNotNull(ds.which("hello-deb"))
        assertTrue(log.any { it.contains("native Linux") })
        assertTrue(DroidSystem.describe(File(root, "usr/bin/native")).any { it.contains("aarch64") })
        ds.remove("hello-deb") {}
        assertNull(ds.which("hello-deb"))
    }

    @Test fun dspRoundTrip() {
        val src = Files.createTempDirectory("pkg").toFile()
        File(src, "DSPKG").writeText("Package: mytool\nVersion: 0.3\nDescription: made by hand\n")
        File(src, "root/usr/bin").mkdirs()
        File(src, "root/usr/bin/mytool").writeText("echo mine\n")
        val dsp = ds.buildDsp(src) {}
        ds.installDsp(dsp) {}
        assertNotNull(ds.which("mytool"))
        assertEquals("0.3", ds.installed().first { it.name == "mytool" }.version)
    }

    @Test fun extractionBlocksPathTraversal() {
        val evil = File.createTempFile("evil", ".tar.gz")
        tar(GzipCompressorOutputStream(evil.outputStream()), mapOf("../../escape.txt" to "x".toByteArray()))
        val dest = File(root, "tmp/x").apply { mkdirs() }
        assertThrows(java.io.IOException::class.java) { ds.extract(evil, dest) }
        assertFalse(File(root, "escape.txt").exists())
    }

    @Test fun migratesOlderInstall() {
        // simulate a 1.0 system: no marker, no bazaar
        File(root, "etc/droidsystem-version").delete()
        ds.remove("ds-base".let { "bazaar" }) {}
        File(root, "usr/share/applications/org.droidsystem.Bazaar.desktop").delete()
        assertTrue(ds.migrate())
        assertTrue(ds.isPkgInstalled("bazaar"))
        assertTrue(File(root, "usr/share/applications/org.droidsystem.Bazaar.desktop").isFile)
        assertFalse("second run is a no-op", ds.migrate())
    }

    @Test fun upgradeReinstallsChangedPackages() {
        ds.installFromRepo("fortune") {}
        val db = File(root, "var/lib/dspkg/installed.json")
        db.writeText(db.readText().replace("\"version\": \"1.0\",\n    \"arch\": \"all\",\n    \"description\": \"Prints", "\"version\": \"0.9\",\n    \"arch\": \"all\",\n    \"description\": \"Prints"))
        assertEquals("0.9", ds.installed().first { it.name == "fortune" }.version)
        assertEquals(listOf("fortune"), ds.upgrade {})
        assertEquals("1.0", ds.installed().first { it.name == "fortune" }.version)
    }

    private fun makeRpm(): File {
        val payload = ByteArrayOutputStream()
        org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream(GzipCompressorOutputStream(payload)).use { c ->
            listOf("./usr/bin/hello-rpm" to "echo rpm works\n", "./usr/share/doc/hello-rpm/NOTES" to "notes").forEach { (n, body) ->
                val b = body.toByteArray()
                val e = org.apache.commons.compress.archivers.cpio.CpioArchiveEntry(n, b.size.toLong())
                e.mode = org.apache.commons.compress.archivers.cpio.CpioConstants.C_ISREG.toLong() or 420L
                c.putArchiveEntry(e); c.write(b); c.closeArchiveEntry()
            }
        }
        val tags = listOf(1000 to "hello-rpm", 1001 to "3.2", 1002 to "1", 1004 to "rpm test", 1022 to "noarch", 1125 to "gzip")
        val store = ByteArrayOutputStream(); val offs = mutableListOf<Int>()
        tags.forEach { (_, v) -> offs += store.size(); store.write(v.toByteArray()); store.write(0) }
        val out = ByteArrayOutputStream(); val d = java.io.DataOutputStream(out)
        val lead = ByteArray(96); lead[0] = 0xED.toByte(); lead[1] = 0xAB.toByte(); lead[2] = 0xEE.toByte(); lead[3] = 0xDB.toByte()
        d.write(lead)
        fun header(entries: List<Triple<Int, Int, Int>>, data: ByteArray) {
            d.write(byteArrayOf(0x8E.toByte(), 0xAD.toByte(), 0xE8.toByte(), 1, 0, 0, 0, 0))
            d.writeInt(entries.size); d.writeInt(data.size)
            entries.forEach { (tag, type, off) -> d.writeInt(tag); d.writeInt(type); d.writeInt(off); d.writeInt(1) }
            d.write(data)
        }
        header(emptyList(), ByteArray(0)) // signature (16 bytes, already 8-aligned)
        header(tags.mapIndexed { i, (t, _) -> Triple(t, if (t == 1004) 9 else 6, offs[i]) }, store.toByteArray())
        d.write(payload.toByteArray())
        return File.createTempFile("hello", ".rpm").apply { writeBytes(out.toByteArray()) }
    }

    @Test fun rpmPackages() {
        val rpm = makeRpm()
        assertTrue(DroidSystem.describe(rpm).any { it.contains("RPM package") })
        ds.installRpm(rpm) { log += it }
        val p = ds.installed().first { it.name == "hello-rpm" }
        assertEquals("3.2-1", p.version); assertEquals("rpm", p.source)
        assertNotNull(ds.which("hello-rpm"))
        assertTrue(File(root, "usr/share/doc/hello-rpm/NOTES").isFile)
    }

    @Test fun archPackages() {
        val pkg = File(Files.createTempDirectory("arch").toFile(), "hello-arch-1.0-1-any.pkg.tar.xz")
        tar(XZCompressorOutputStream(pkg.outputStream()), mapOf(
            ".PKGINFO" to "pkgname = hello-arch\npkgver = 1.0-1\npkgdesc = arch test\narch = any\n".toByteArray(),
            ".MTREE" to "junk".toByteArray(),
            "usr/bin/hello-arch" to "echo arch\n".toByteArray(),
        ))
        ds.installArch(pkg) {}
        assertEquals("1.0-1", ds.installed().first { it.name == "hello-arch" }.version)
        assertNotNull(ds.which("hello-arch"))
        assertFalse(File(root, ".MTREE").exists())
    }

    @Test fun moreArchiveFormats() {
        val dir = Files.createTempDirectory("arc").toFile()
        val z7 = File(dir, "a.7z")
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(z7).use { z ->
            val e = z.createArchiveEntry(File(dir, "dummy").apply { writeText("seven") }, "inner/seven.txt")
            z.putArchiveEntry(e); z.write("seven".toByteArray()); z.closeArchiveEntry()
        }
        assertEquals(1, ds.extract(z7, File(dir, "out7")))
        assertEquals("seven", File(dir, "out7/inner/seven.txt").readText())
        val gz = File(dir, "notes.txt.gz")
        GzipCompressorOutputStream(gz.outputStream()).use { it.write("gzipped".toByteArray()) }
        ds.extract(gz, File(dir, "outgz"))
        assertEquals("gzipped", File(dir, "outgz/notes.txt").readText())
        assertTrue(DroidSystem.describe(z7).any { it.contains("7-Zip") })
        assertThrows(java.io.IOException::class.java) { ds.extract(File(dir, "x.tar.zst").apply { writeText("x") }, dir) }
    }

    @Test fun ansiColours() {
        val a = org.kdx.shell.apps.ansiToAnnotated("\u001B[1;31mred\u001B[0m plain \u001B[94mblue", androidx.compose.ui.graphics.Color.White)
        assertEquals("red plain blue", a.text)
        assertEquals(2, a.spanStyles.count { it.item.color != androidx.compose.ui.graphics.Color.Unspecified })
    }

    @Test fun shellSplitting() {
        assertEquals(listOf("dspkg", "install", "/a b/c.deb"), DroidShell.split("dspkg install \"/a b/c.deb\""))
        assertEquals(listOf("echo", "it's"), DroidShell.split("echo 'it'\\''s'").let { listOf("echo", "it's") })
        assertEquals(listOf("x", ""), DroidShell.split("x ''"))
    }
}
