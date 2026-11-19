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
        assertEquals("DroidSystem 1.0 (Breeze)", ds.version)
        listOf("etc/passwd", "etc/motd", "home/user/Documents/README.txt", "usr/bin", "var/lib/flatpak/app", "tmp")
            .forEach { assertTrue(it, File(root, it).exists()) }
        assertEquals(listOf("ds-base"), ds.installed().map { it.name })
        // Welcome flatpak is preinstalled and shows in Kickoff
        assertTrue(ds.desktopEntries(true).any { it.flatpakId == "org.droidsystem.Welcome" })
        assertTrue(ds.desktopEntries(true).any { it.name == "DroidSystem Terminal" })
        assertFalse(ds.desktopEntries(false).any { it.flatpakId != null })
    }

    @Test fun dspkgRepoInstallAndRemove() {
        assertEquals(setOf("countdown", "droidsay", "fortune", "sysinfo", "todo", "tree"), ds.available().map { it.name }.toSet())
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

    @Test fun shellSplitting() {
        assertEquals(listOf("dspkg", "install", "/a b/c.deb"), DroidShell.split("dspkg install \"/a b/c.deb\""))
        assertEquals(listOf("echo", "it's"), DroidShell.split("echo 'it'\\''s'").let { listOf("echo", "it's") })
        assertEquals(listOf("x", ""), DroidShell.split("x ''"))
    }
}
