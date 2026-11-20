package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.Blocklist
import org.kdx.shell.droid.DnsPackets
import org.kdx.shell.droid.DnsEngine
import java.io.File

class AdBlockTest {
    private fun question(name: String, id: Int = 0x1234): ByteArray {
        val q = java.io.ByteArrayOutputStream()
        q.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { q.write(it.length); q.write(it.toByteArray()) }
        q.write(byteArrayOf(0, 0, 1, 0, 1)) // end, type A, class IN
        return q.toByteArray()
    }

    private fun ipv4Udp(dns: ByteArray, dstPort: Int = 53): ByteArray {
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45; p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte(); p[8] = 64; p[9] = 17
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12); byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        p[20] = (40000 shr 8).toByte(); p[21] = 40000.toByte(); p[22] = (dstPort shr 8).toByte(); p[23] = dstPort.toByte()
        p[24] = ((8 + dns.size) shr 8).toByte(); p[25] = (8 + dns.size).toByte()
        dns.copyInto(p, 28)
        return p
    }

    /** Independent RFC 1071 verification: summing a packet including its checksum gives 0xFFFF. */
    private fun sumsToOnes(p: ByteArray, off: Int, len: Int, extra: Long = 0): Boolean {
        var s = extra
        var i = off
        while (i + 1 < off + len) { s += ((p[i].toInt() and 0xFF) shl 8) + (p[i + 1].toInt() and 0xFF); i += 2 }
        if (i < off + len) s += (p[i].toInt() and 0xFF) shl 8
        while (s > 0xFFFF) s = (s and 0xFFFF) + (s shr 16)
        return s == 0xFFFFL
    }

    @Test fun blocksListedDomainsAndTheirSubdomains() {
        val b = Blocklist(listOf("doubleclick.net", "ads.example.com"), allow = setOf("good.doubleclick.net"))
        assertTrue(b.isBlocked("doubleclick.net"))
        assertTrue(b.isBlocked("stats.g.doubleclick.net."))
        assertTrue(b.isBlocked("ADS.example.com"))
        assertFalse(b.isBlocked("example.com"))
        assertFalse(b.isBlocked("www.example.com"))
        assertFalse("allow list wins", b.isBlocked("good.doubleclick.net"))
        assertFalse(b.isBlocked("netdoubleclick.net"))
    }

    @Test fun parsesHostsFilesAndThePlainList() {
        val hosts = "# comment\n0.0.0.0 ads.foo.com\n127.0.0.1 track.bar.org # inline\n0.0.0.0 0.0.0.0\n127.0.0.1 localhost\nplain.example.net\n"
        assertEquals(listOf("ads.foo.com", "track.bar.org", "plain.example.net"), Blocklist.parse(hosts.byteInputStream()))
    }

    @Test fun bundledListLoads() {
        val f = File("src/main/assets/adblock/blocklist.bin")
        val list = Blocklist(Blocklist.parseGzip(f.inputStream()))
        assertTrue("bundled list has the StevenBlack domains (${list.size})", list.size > 50_000)
        assertTrue(list.isBlocked("doubleclick.net") || list.isBlocked("ad.doubleclick.net"))
        assertFalse(list.isBlocked("wikipedia.org")); assertFalse(list.isBlocked("github.com")); assertFalse(list.isBlocked("debian.org"))
    }

    @Test fun parsesIpv4DnsQueries() {
        val p = ipv4Udp(question("ads.example.com"))
        val q = DnsPackets.parse(p, p.size)!!
        assertEquals("ads.example.com", q.name); assertEquals(4, q.ipVersion); assertEquals(40000, q.srcPort)
        assertNull("not DNS", DnsPackets.parse(ipv4Udp(question("x.com"), dstPort = 443), p.size))
    }

    @Test fun nxdomainAnswerIsValid() {
        val query = question("ads.example.com", id = 0xBEEF)
        val r = DnsPackets.nxdomain(query)
        assertEquals(0xBE.toByte(), r[0]); assertEquals(0xEF.toByte(), r[1])     // same ID
        assertTrue(r[2].toInt() and 0x80 != 0)                                     // it's a response
        assertEquals(3, r[3].toInt() and 0x0F)                                     // RCODE NXDOMAIN
        assertEquals(1, DnsPackets.u16(r, 4)); assertEquals(0, DnsPackets.u16(r, 6)) // 1 question, 0 answers
        assertEquals("ads.example.com", DnsPackets.qname(r))
        assertEquals(query.size, r.size)                                           // header + question only
    }

    @Test fun replyPacketsHaveCorrectChecksums() {
        val p = ipv4Udp(question("ads.example.com"))
        val q = DnsPackets.parse(p, p.size)!!
        val r = DnsPackets.reply(q, DnsPackets.nxdomain(q.dns))
        val back = DnsPackets.parse(r.copyOf().also { it[22] = 0; it[23] = 53 }, r.size) // swap port to re-parse as query
        assertEquals("ads.example.com", back!!.name)
        assertTrue("IPv4 header checksum", sumsToOnes(r, 0, 20))
        // UDP checksum with the IPv4 pseudo header
        val udpLen = r.size - 20
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += ((r[i].toInt() and 0xFF) shl 8) + (r[i + 1].toInt() and 0xFF)
        pseudo += 17 + udpLen
        assertTrue("UDP checksum", sumsToOnes(r, 20, udpLen, pseudo))
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), r.copyOfRange(12, 16))     // from the DNS address
        assertEquals(40000, DnsPackets.u16(r, 22))                                  // back to the app's port
    }

    @Test fun ipv6RoundTrip() {
        val dns = question("tracker.example.org")
        val p = ByteArray(48 + dns.size)
        p[0] = 0x60; p[4] = ((8 + dns.size) shr 8).toByte(); p[5] = (8 + dns.size).toByte(); p[6] = 17; p[7] = 64
        for (i in 8 until 40) p[i] = (i % 7).toByte()
        p[40] = 0x9C.toByte(); p[41] = 0x40; p[43] = 53; p[44] = ((8 + dns.size) shr 8).toByte(); p[45] = (8 + dns.size).toByte()
        dns.copyInto(p, 48)
        val q = DnsPackets.parse(p, p.size)!!
        assertEquals("tracker.example.org", q.name); assertEquals(6, q.ipVersion)
        val r = DnsPackets.reply(q, DnsPackets.nxdomain(q.dns))
        var pseudo = 0L
        for (i in 8 until 40 step 2) pseudo += ((r[i].toInt() and 0xFF) shl 8) + (r[i + 1].toInt() and 0xFF)
        pseudo += 17 + (r.size - 40)
        assertTrue("UDP checksum (IPv6)", sumsToOnes(r, 40, r.size - 40, pseudo))
    }

    @Test fun engineBlocksOrForwardsThroughAFakeDnsServer() {
        // a fake upstream DNS server on localhost: answers every query with a fixed A record
        val server = java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())
        val t = kotlin.concurrent.thread(isDaemon = true) {
            val buf = ByteArray(512)
            while (!server.isClosed) {
                val p = java.net.DatagramPacket(buf, buf.size)
                runCatching { server.receive(p) }.onFailure { return@thread }
                val q = buf.copyOf(p.length)
                val ans = q.copyOf() + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93.toByte(), 184.toByte(), 216.toByte(), 34)
                ans[2] = 0x81.toByte(); ans[3] = 0x80.toByte(); ans[7] = 1 // response, 1 answer
                server.send(java.net.DatagramPacket(ans, ans.size, p.address, p.port))
            }
        }
        val forward: (ByteArray) -> ByteArray? = { dns ->
            java.net.DatagramSocket().use { s ->
                s.soTimeout = 3000
                s.send(java.net.DatagramPacket(dns, dns.size, java.net.InetAddress.getLoopbackAddress(), server.localPort))
                val r = java.net.DatagramPacket(ByteArray(512), 512); s.receive(r); r.data.copyOf(r.length)
            }
        }
        val blockedNames = mutableListOf<String>()
        val engine = DnsEngine(Blocklist(listOf("doubleclick.net")), forward) { blockedNames += it }
        val blockedReply = engine.handle(ipv4Udp(question("ad.doubleclick.net")))!!
        assertEquals(3, blockedReply[28 + 3].toInt() and 0x0F) // NXDOMAIN, without asking the server
        val okReply = engine.handle(ipv4Udp(question("example.com", id = 0x0102)))!!
        val dns = okReply.copyOfRange(28, okReply.size)
        assertEquals(0x01.toByte(), dns[0]); assertEquals(0x02.toByte(), dns[1])          // same query ID
        assertEquals(0, dns[3].toInt() and 0x0F); assertEquals(1, DnsPackets.u16(dns, 6)) // NOERROR, 1 answer
        assertArrayEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), dns.copyOfRange(dns.size - 4, dns.size))
        assertEquals(listOf("ad.doubleclick.net"), blockedNames)
        server.close(); t.join(1000)
    }
}
