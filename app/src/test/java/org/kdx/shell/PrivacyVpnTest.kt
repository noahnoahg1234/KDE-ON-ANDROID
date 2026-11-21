package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.LinuxPrivacy
import org.kdx.shell.droid.TorControl

class PrivacyVpnTest {
    @Test fun torControlRepliesAreParsed() {
        val replies = "250 OK\r\n" +
            "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors SUMMARY=\"Loading\"\r\n250 OK\r\n" +
            "250+ns/all=\r\nr relay1\r\nr relay2\r\n.\r\n250 OK\r\n"
        val sent = java.io.ByteArrayOutputStream()
        val r = TorControl.talk(replies.reader().buffered(), sent, listOf("AUTHENTICATE", "GETINFO status/bootstrap-phase", "GETINFO ns/all"))
        assertEquals("AUTHENTICATE\r\nGETINFO status/bootstrap-phase\r\nGETINFO ns/all\r\n", sent.toString())
        assertEquals(emptyList<String>(), r[0])
        assertEquals(45, TorControl.parseProgress(r[1][0]))
        assertEquals("\nr relay1\nr relay2", r[2][0])
    }

    @Test(expected = java.io.IOException::class) fun torControlErrorsThrow() {
        TorControl.talk("515 Authentication failed\r\n".reader().buffered(), java.io.ByteArrayOutputStream(), listOf("AUTHENTICATE"))
    }

    @Test fun firefoxPolicyIsValidJsonAndForcesTheProxy() {
        val p = org.json.JSONObject(LinuxPrivacy.firefoxPolicies("127.0.0.1:19050")).getJSONObject("policies")
        val proxy = p.getJSONObject("Proxy")
        assertEquals("manual", proxy.getString("Mode"))
        assertEquals("127.0.0.1:19050", proxy.getString("SOCKSProxy"))
        assertTrue(proxy.getBoolean("UseProxyForDNS"))
        assertFalse(p.getJSONObject("DNSOverHTTPS").getBoolean("Enabled"))
    }
}

class KdePlasmaTest {
    @Test fun setupScriptIsValidShell() {
        val f = java.io.File.createTempFile("plasma-setup", ".sh").apply { writeText(org.kdx.shell.droid.KdePlasma.setupScript()) }
        val p = ProcessBuilder("sh", "-n", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals("sh -n: $out", 0, p.waitFor())
        val s = f.readText()
        assertTrue(s.contains("kbuildsycoca6") && s.contains("apt-get -q install"))
        f.delete()
    }

    @Test fun dpkgSkipsTranslationsAndDocs() {
        val c = org.kdx.shell.droid.DebianSystem.DPKG_CONFIG
        assertTrue(c.startsWith("force-unsafe-io\n"))
        assertTrue(c.lines().containsAll(listOf("path-exclude=/usr/share/locale/*", "path-exclude=/usr/share/doc/*", "path-exclude=/usr/share/man/*")))
    }
}
