package org.kdx.shell.droid

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Ad/tracker domains. A query is blocked if the name or any parent domain is listed (ads.example.com is blocked by
 * example.com) – unless the user allowed it.
 */
class Blocklist(domains: Collection<String>, val allow: Set<String> = emptySet(), val source: String = "") {
    private val blocked = HashSet<String>(domains.size * 2).apply { domains.forEach { add(it.lowercase().trimEnd('.')) } }
    val size get() = blocked.size

    fun isBlocked(name: String): Boolean {
        var d = name.lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        if (matches(d, allow)) return false
        while (true) {
            if (d in blocked) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    private fun matches(name: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var d = name
        while (true) {
            if (d in set) return true
            val dot = d.indexOf('.'); if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    companion object {
        /** Reads a hosts file ("0.0.0.0 ads.example.com") or a plain domain list; # comments ignored. */
        fun parse(input: InputStream): List<String> = input.bufferedReader().useLines { lines ->
            lines.mapNotNull { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@mapNotNull null
                val parts = line.split(Regex("\\s+"))
                val d = (if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::")) parts[1] else parts[0])
                    .lowercase().trimEnd('.')
                d.takeIf { it.contains('.') && it.matches(Regex("[a-z0-9_.-]+")) && it != "0.0.0.0" && !it.startsWith("localhost") }
            }.toList()
        }

        fun parseGzip(input: InputStream) = parse(GZIPInputStream(input))
    }
}

/**
 * Minimal DNS-over-UDP packet handling for the ad blocker's VPN: parses IPv4/IPv6 + UDP + DNS queries coming out of
 * the tun interface, answers blocked names with NXDOMAIN, and wraps upstream replies back into IP packets.
 */
object DnsPackets {
    /** A DNS query captured from the tun device. */
    class Query(
        val ipVersion: Int, val srcAddr: ByteArray, val dstAddr: ByteArray,
        val srcPort: Int, val dstPort: Int, val dns: ByteArray, val name: String,
    )

    /** Parses an IP packet; returns the DNS query in it, or null if it isn't a UDP DNS query. */
    fun parse(p: ByteArray, len: Int): Query? {
        if (len < 28) return null
        val v = (p[0].toInt() shr 4) and 0xF
        val (src, dst, udp) = when (v) {
            4 -> {
                val ihl = (p[0].toInt() and 0xF) * 4
                if (p[9].toInt() != 17 || len < ihl + 8) return null // UDP only
                Triple(p.copyOfRange(12, 16), p.copyOfRange(16, 20), ihl)
            }
            6 -> {
                if (len < 48 || p[6].toInt() != 17) return null // no extension headers expected for DNS
                Triple(p.copyOfRange(8, 24), p.copyOfRange(24, 40), 40)
            }
            else -> return null
        }
        val sp = u16(p, udp); val dp = u16(p, udp + 2)
        if (dp != 53) return null
        val udpLen = u16(p, udp + 4).coerceAtMost(len - udp)
        if (udpLen < 8 + 12) return null
        val dns = p.copyOfRange(udp + 8, udp + udpLen)
        val name = qname(dns) ?: return null
        return Query(v, src, dst, sp, dp, dns, name)
    }

    /** The first question's name, e.g. "ads.example.com". */
    fun qname(dns: ByteArray): String? {
        if (dns.size < 12 || u16(dns, 4) < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (i < dns.size) {
            val l = dns[i].toInt() and 0xFF
            if (l == 0) return sb.toString()
            if (l and 0xC0 != 0 || i + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((dns[i + k].toInt() and 0xFF).toChar())
            i += 1 + l
        }
        return null
    }

    /** An NXDOMAIN ("no such domain") answer to [query] – how blocked names look to apps. */
    fun nxdomain(query: ByteArray): ByteArray {
        // header + the question section only
        var i = 12
        while (i < query.size && query[i].toInt() != 0) i += (query[i].toInt() and 0xFF) + 1
        val qEnd = (i + 1 + 4).coerceAtMost(query.size)
        val r = query.copyOfRange(0, qEnd)
        r[2] = ((query[2].toInt() and 0x01) or 0x80).toByte() // QR=1, keep RD
        r[3] = 0x83.toByte()                                   // RA=1, RCODE=3 (NXDOMAIN)
        r[4] = 0; r[5] = 1                                      // QDCOUNT 1
        for (k in 6 until 12) r[k] = 0                          // AN/NS/AR = 0
        return r
    }

    /** Wraps [dns] as the reply to [q]: addresses and ports swapped, lengths and checksums filled in. */
    fun reply(q: Query, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        return if (q.ipVersion == 4) {
            val p = ByteArray(20 + udpLen)
            p[0] = 0x45; put16(p, 2, p.size); p[8] = 64; p[9] = 17
            System.arraycopy(q.dstAddr, 0, p, 12, 4); System.arraycopy(q.srcAddr, 0, p, 16, 4)
            put16(p, 10, checksum(p, 0, 20, 0))
            udp(p, 20, q, dns)
            put16(p, 26, udpChecksum(p, 20, udpLen, pseudo4(q.dstAddr, q.srcAddr, udpLen)))
            p
        } else {
            val p = ByteArray(40 + udpLen)
            p[0] = 0x60; put16(p, 4, udpLen); p[6] = 17; p[7] = 64
            System.arraycopy(q.dstAddr, 0, p, 8, 16); System.arraycopy(q.srcAddr, 0, p, 24, 16)
            udp(p, 40, q, dns)
            put16(p, 46, udpChecksum(p, 40, udpLen, pseudo6(q.dstAddr, q.srcAddr, udpLen)))
            p
        }
    }

    private fun udp(p: ByteArray, off: Int, q: Query, dns: ByteArray) {
        put16(p, off, q.dstPort); put16(p, off + 2, q.srcPort); put16(p, off + 4, 8 + dns.size)
        System.arraycopy(dns, 0, p, off + 8, dns.size)
    }

    private fun pseudo4(src: ByteArray, dst: ByteArray, len: Int): Long {
        var s = 0L
        for (a in listOf(src, dst)) for (i in 0 until 4 step 2) s += u16(a, i)
        return s + 17 + len
    }

    private fun pseudo6(src: ByteArray, dst: ByteArray, len: Int): Long {
        var s = 0L
        for (a in listOf(src, dst)) for (i in 0 until 16 step 2) s += u16(a, i)
        return s + 17 + len
    }

    private fun udpChecksum(p: ByteArray, off: Int, len: Int, pseudo: Long): Int =
        checksum(p, off, len, pseudo).let { if (it == 0) 0xFFFF else it }

    /** Internet checksum (RFC 1071) over p[off, off+len) plus an initial sum. */
    fun checksum(p: ByteArray, off: Int, len: Int, initial: Long): Int {
        var s = initial
        var i = off
        while (i + 1 < off + len) { s += u16(p, i); i += 2 }
        if (i < off + len) s += (p[i].toInt() and 0xFF) shl 8
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
}

/** Where the ad blocker keeps its updated list and settings. */
object AdBlockFiles {
    fun listFile(filesDir: File) = File(filesDir, "adblock/hosts.txt")
    const val UPDATE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
}

/**
 * The ad blocker's per-packet logic, separate from the Android VPN plumbing so it can be tested: a DNS query for a
 * blocked name is answered at once with NXDOMAIN; anything else is handed to [forward] (which asks the real DNS
 * server) and its answer wrapped back into a packet for the app.
 */
class DnsEngine(
    var list: Blocklist,
    private val forward: (ByteArray) -> ByteArray?,
    private val onBlocked: (String) -> Unit = {},
) {
    /** Returns the reply packet for [packet], or null when it isn't a DNS query (or the upstream didn't answer). */
    fun handle(packet: ByteArray, len: Int = packet.size): ByteArray? {
        val q = DnsPackets.parse(packet, len) ?: return null
        if (list.isBlocked(q.name)) { onBlocked(q.name); return DnsPackets.reply(q, DnsPackets.nxdomain(q.dns)) }
        val answer = forward(q.dns) ?: return null
        return DnsPackets.reply(q, answer)
    }

    /** True when [packet] is a DNS query that will be blocked (lets the VPN answer inline, without a thread). */
    fun isBlockedQuery(packet: ByteArray, len: Int): Boolean = DnsPackets.parse(packet, len)?.let { list.isBlocked(it.name) } ?: false
}
