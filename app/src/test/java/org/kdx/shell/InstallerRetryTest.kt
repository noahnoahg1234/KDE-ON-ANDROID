package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.DebianSystem
import org.kdx.shell.droid.KdePlasma
import org.kdx.shell.droid.WindSystem
import java.io.File

/** KDE Plasma's and WindSystem's installers survive a broken download (phone networks drop) instead of giving up. */
class InstallerRetryTest {
    private fun sh(script: String, path: String? = null): Pair<Int, String> {
        val f = File.createTempFile("kdx-script", ".sh").apply { writeText(script) }
        val pb = ProcessBuilder("sh", f.path).redirectErrorStream(true)
        path?.let { pb.environment()["PATH"] = it + ":" + pb.environment()["PATH"] }
        val p = pb.start(); val out = p.inputStream.bufferedReader().readText(); val rc = p.waitFor()
        f.delete(); return rc to out
    }

    @Test fun aptInstallRetriesUntilItWorks() {
        val bin = kotlin.io.path.createTempDirectory("kdx-bin").toFile()
        val count = File(bin, "count")
        // a fake apt-get whose install fails twice (like a dropped connection), then works
        File(bin, "apt-get").apply {
            writeText("#!/bin/sh\n[ \"\$2\" = install ] || [ \"\$1\" = install ] || exit 0\nn=\$(cat ${count.path} 2>/dev/null || echo 0); n=\$((n+1)); echo \$n > ${count.path}\n[ \$n -ge 3 ]\n")
            setExecutable(true)
        }
        File(bin, "dpkg").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        File(bin, "sleep").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        val (rc, out) = sh(DebianSystem.APT_INSTALL_FN + "apt_install kde-stuff && echo INSTALLED\n", bin.path)
        assertEquals(out, 0, rc)
        assertTrue(out, out.contains("INSTALLED"))
        assertEquals("3 tries", "3", count.readText().trim())
        assertTrue(out.contains("try 1 of 4") && out.contains("try 2 of 4"))
        // a connection that never comes back: gives up after 4 tries with a clear message
        count.writeText("-100")
        val (rc2, out2) = sh(DebianSystem.APT_INSTALL_FN + "apt_install kde-stuff\n", bin.path)
        assertNotEquals(0, rc2)
        assertTrue(out2, out2.contains("failed 4 times"))
        bin.deleteRecursively()
    }

    @Test fun installersAreValidAndUseRetries() {
        for ((name, script, shell) in listOf(Triple("plasma-setup", KdePlasma.setupScript(), "sh"), Triple("windsystem-setup", WindSystem.setupScript(), "bash"))) {
            val f = File.createTempFile(name, ".sh").apply { writeText(script) }
            val p = ProcessBuilder(shell, "-n", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("$name: $out", 0, p.waitFor())
            assertTrue(name, script.contains("apt_install() {") && script.contains("\napt_install ") || script.contains("    apt_install "))
            f.delete()
        }
        assertTrue("Ubuntu's Plasma X11 session", KdePlasma.setupScript().contains("plasma-session-x11"))
    }
}
