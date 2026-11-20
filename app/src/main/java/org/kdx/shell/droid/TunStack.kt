package org.kdx.shell.droid

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small user-space TCP/IP endpoint for a VPN interface ("tun2socks").
 *
 * Every IPv4 packet the phone sends goes into [onPacket]. TCP connections are accepted here and each one is carried
 * over a [Connector] socket (normally a SOCKS5 connection through Tor), so the remote server only ever sees the proxy's
 * address. UDP to port 53 (DNS) goes to [dns]; all other UDP, ICMP and all IPv6 is dropped so nothing leaks around the
 * proxy (apps fall back to TCP over IPv4, e.g. QUIC → HTTPS). Packets for the phone are written with [out].
 */
class TunStack(
    private val out: (ByteArray) -> Unit,
    private val connector: Connector,
    private val dns: ((ByteArray) -> ByteArray?)? = null,
) {
    fun interface Connector { @Throws(IOException::class) fun connect(ip: InetAddress, port: Int): Socket }

    private val conns = ConcurrentHashMap<String, Conn>()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "kdx-tun").apply { isDaemon = true } }
    private val ipId = AtomicInteger()
    @Volatile private var closed = false
    private val timer = Thread({ timerLoop() }, "kdx-tun-timer").apply { isDaemon = true; start() }

    val connectionCount get() = conns.size
    val bytesUp = java.util.concurrent.atomic.AtomicLong()
    val bytesDown = java.util.concurrent.atomic.AtomicLong()

    fun close() {
        closed = true
        conns.values.forEach { it.destroy(sendReset = false) }
        conns.clear()
        pool.shutdownNow()
        timer.interrupt()
    }

    /** Handles one packet from the VPN interface. */
    fun onPacket(b: ByteArray, len: Int) {
        if (len < 20 || (b[0].toInt() shr 4) != 4) return // IPv6 and garbage: dropped (no leaks)
        val ihl = (b[0].toInt() and 0xF) * 4
        val total = minOf(u16(b, 2), len)
        if (total < ihl + 8) return
        when (b[9].toInt() and 0xFF) {
            6 -> onTcp(b, ihl, total)
            17 -> if (u16(b, ihl + 2) == 53 && dns != null) {
                val copy = b.copyOf(total)
                pool.execute { runCatching { dns!!.invoke(copy) }.getOrNull()?.let { write(it) } }
            }
        }
    }

    // ------------------------------------------------------------------ TCP
    private fun onTcp(b: ByteArray, ihl: Int, total: Int) {
        val t = ihl
        val src = b.copyOfRange(12, 16); val dst = b.copyOfRange(16, 20)
        val sport = u16(b, t); val dport = u16(b, t + 2)
        val seq = s32(b, t + 4); val ack = s32(b, t + 8)
        val off = ((b[t + 12].toInt() shr 4) and 0xF) * 4
        val flags = b[t + 13].toInt() and 0x3F
        val win = u16(b, t + 14)
        if (t + off > total) return
        val payloadOff = t + off; val payloadLen = total - payloadOff
        val key = "${ip(src)}:$sport>${ip(dst)}:$dport"
        val c = conns[key]

        if (flags and SYN != 0 && flags and ACK == 0) {
            if (c == null) {
                if (closed) return
                val conn = Conn(key, src, sport, dst, dport, seq + 1, win, mssOption(b, t + 20, t + off))
                conns[key] = conn
                pool.execute { conn.open() }
            } else c.synAgain()
            return
        }
        if (c == null) {
            if (flags and RST == 0) write(tcp(dst, dport, src, sport, if (flags and ACK != 0) ack else 0,
                seq + payloadLen + (if (flags and FIN != 0) 1 else 0), RST or ACK, 0))
            return
        }
        if (flags and RST != 0) { c.destroy(sendReset = false); return }
        c.onSegment(seq, ack, flags, win, b, payloadOff, payloadLen)
    }

    private inner class Conn(
        val key: String, val cIp: ByteArray, val cPort: Int, val sIp: ByteArray, val sPort: Int,
        var rcvNext: Int, var cWin: Int, cMss: Int,
    ) {
        val lock = Object()
        val mss = minOf(cMss, 1360)
        val isn = java.util.concurrent.ThreadLocalRandom.current().nextInt()
        var sndUna = isn          // oldest byte the phone hasn't acknowledged (starts at our SYN)
        var sndNext = isn + 1     // next sequence number we send
        var established = false
        var synSent = false
        var buf = ByteArray(65536) // data sent but not yet acknowledged, starting at sequence sndUna (+1 before established)
        var bufLen = 0
        var finSent = false; var finAcked = false; var clientFin = false
        var lastProgress = System.currentTimeMillis()
        var lastActivity = lastProgress
        @Volatile var socket: Socket? = null
        @Volatile var dead = false
        val toServer = LinkedBlockingQueue<ByteArray>()
        val queued = AtomicInteger()
        var advertisedSmall = false

        /** Connects through the proxy; only then answers the SYN, so a refused connection becomes a reset. */
        fun open() {
            val s = runCatching { connector.connect(InetAddress.getByAddress(sIp), sPort) }.getOrNull()
            if (s == null || dead) {
                s?.let { runCatching { it.close() } }
                conns.remove(key, this)
                if (!dead) write(tcp(sIp, sPort, cIp, cPort, 0, rcvNext, RST or ACK, 0))
                dead = true
                return
            }
            socket = s
            synchronized(lock) { synSent = true; sendSynAck() }
        }

        fun synAgain() = synchronized(lock) { if (synSent && !established) sendSynAck() }

        private fun sendSynAck() {
            write(tcp(sIp, sPort, cIp, cPort, isn, rcvNext, SYN or ACK, window(),
                options = byteArrayOf(2, 4, (mss shr 8).toByte(), mss.toByte())))
        }

        fun window() = (65535 - queued.get()).coerceIn(0, 65535)

        fun onSegment(seq: Int, ack: Int, flags: Int, win: Int, b: ByteArray, off: Int, len: Int) {
            var start = false
            var close = false
            synchronized(lock) {
                lastActivity = System.currentTimeMillis()
                if (flags and ACK != 0) {
                    if (!established && synSent && ack == isn + 1) {
                        established = true; sndUna = isn + 1; start = true
                    } else if (established) {
                        val n = ack - sndUna
                        val maxAck = bufLen + if (finSent) 1 else 0
                        if (n in 1..maxAck) {
                            val data = minOf(n, bufLen)
                            System.arraycopy(buf, data, buf, 0, bufLen - data); bufLen -= data
                            sndUna = ack; lastProgress = lastActivity
                            if (finSent && n == data + 1) finAcked = true
                        }
                    }
                    cWin = win
                    lock.notifyAll()
                }
                if (!established) return
                if (len > 0) {
                    if (seq == rcvNext && !clientFin) {
                        toServer.put(b.copyOfRange(off, off + len)); queued.addAndGet(len)
                        rcvNext += len; bytesUp.addAndGet(len.toLong())
                    }
                    if (window() < 16384) advertisedSmall = true
                    write(tcp(sIp, sPort, cIp, cPort, sndNext, rcvNext, ACK, window()))
                }
                if (flags and FIN != 0 && seq + len == rcvNext && !clientFin) {
                    clientFin = true; rcvNext += 1
                    toServer.put(EOF)
                    write(tcp(sIp, sPort, cIp, cPort, sndNext, rcvNext, ACK, window()))
                }
                if (clientFin && finAcked) close = true
            }
            if (start) { pool.execute { writerLoop() }; pool.execute { readerLoop() } }
            if (close) destroy(sendReset = false)
        }

        /** Phone → server. */
        private fun writerLoop() {
            val s = socket ?: return
            try {
                val o: OutputStream = s.getOutputStream()
                while (!dead) {
                    val chunk = toServer.poll(30, TimeUnit.SECONDS) ?: continue
                    if (chunk === EOF) { runCatching { s.shutdownOutput() }; return }
                    o.write(chunk); o.flush()
                    val left = queued.addAndGet(-chunk.size)
                    if (advertisedSmall && left < 16384) synchronized(lock) { // the phone may be waiting for room
                        advertisedSmall = false
                        write(tcp(sIp, sPort, cIp, cPort, sndNext, rcvNext, ACK, window()))
                    }
                }
            } catch (e: Exception) { if (!dead) destroy(sendReset = true) }
        }

        /** Server → phone, never sending more than the phone's window allows. */
        private fun readerLoop() {
            val s = socket ?: return
            val tmp = ByteArray(32768)
            try {
                val i: InputStream = s.getInputStream()
                while (!dead) {
                    var room: Int
                    synchronized(lock) {
                        while (!dead && (bufLen >= minOf(cWin, buf.size) || cWin == 0)) lock.wait(250)
                        room = minOf(cWin, buf.size) - bufLen
                    }
                    if (dead) return
                    val n = i.read(tmp, 0, minOf(room, tmp.size))
                    if (n < 0) break
                    if (n == 0) continue
                    bytesDown.addAndGet(n.toLong())
                    synchronized(lock) {
                        System.arraycopy(tmp, 0, buf, bufLen, n)
                        var p = 0
                        while (p < n) {
                            val l = minOf(mss, n - p)
                            write(tcp(sIp, sPort, cIp, cPort, sndNext, rcvNext, ACK or PSH, window(), tmp, p, l))
                            sndNext += l; p += l
                        }
                        bufLen += n
                        if (bufLen == n) lastProgress = System.currentTimeMillis()
                    }
                }
                var close = false
                synchronized(lock) { // server finished: FIN after all data
                    if (!finSent) {
                        write(tcp(sIp, sPort, cIp, cPort, sndNext, rcvNext, FIN or ACK, window()))
                        sndNext += 1; finSent = true
                    }
                    close = clientFin && finAcked
                }
                if (close) destroy(sendReset = false)
            } catch (e: Exception) { if (!dead) destroy(sendReset = true) }
        }

        /** Called by the timer: resends what the phone hasn't acknowledged (the interface can drop packets). */
        fun tick(now: Long) {
            var expire = false
            synchronized(lock) {
                if (dead) return
                val idle = now - lastActivity
                if (idle > (if (finSent || clientFin) 60_000 else 600_000)) expire = true
                else if (established && (bufLen > 0 || (finSent && !finAcked)) && now - lastProgress > 1000) {
                    lastProgress = now
                    var p = 0
                    val limit = minOf(bufLen, maxOf(cWin, mss))
                    while (p < limit) {
                        val l = minOf(mss, limit - p)
                        write(tcp(sIp, sPort, cIp, cPort, sndUna + p, rcvNext, ACK or PSH, window(), buf, p, l)); p += l
                    }
                    if (finSent && !finAcked && limit == bufLen) write(tcp(sIp, sPort, cIp, cPort, sndUna + bufLen, rcvNext, FIN or ACK, window()))
                }
            }
            if (expire) destroy(sendReset = true)
        }

        fun destroy(sendReset: Boolean) {
            val first = synchronized(lock) { val f = !dead; dead = true; lock.notifyAll(); f }
            conns.remove(key, this)
            runCatching { socket?.close() }
            toServer.offer(EOF)
            if (sendReset && first && !closed) write(tcp(sIp, sPort, cIp, cPort, sndNext, rcvNext, RST or ACK, 0))
        }
    }

    private fun timerLoop() {
        while (!closed) {
            try { Thread.sleep(250) } catch (e: InterruptedException) { return }
            val now = System.currentTimeMillis()
            conns.values.forEach { runCatching { it.tick(now) } }
        }
    }

    private fun write(p: ByteArray) { if (!closed) synchronized(this) { runCatching { out(p) } } }

    private fun mssOption(b: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            val kind = b[i].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            if (i + 1 >= to) break
            val l = b[i + 1].toInt() and 0xFF
            if (kind == 2 && l == 4 && i + 3 < to) return u16(b, i + 2)
            if (l < 2) break
            i += l
        }
        return 536
    }

    /** Builds an IPv4 TCP packet. */
    private fun tcp(
        src: ByteArray, sport: Int, dst: ByteArray, dport: Int, seq: Int, ack: Int, flags: Int, win: Int,
        data: ByteArray? = null, off: Int = 0, len: Int = 0, options: ByteArray = ByteArray(0),
    ): ByteArray {
        val th = 20 + options.size
        val total = 20 + th + len
        val p = ByteArray(total)
        p[0] = 0x45; put16(p, 2, total); put16(p, 4, ipId.incrementAndGet()); p[6] = 0x40; p[8] = 64; p[9] = 6
        src.copyInto(p, 12); dst.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20, 0))
        val t = 20
        put16(p, t, sport); put16(p, t + 2, dport); put32(p, t + 4, seq); put32(p, t + 8, ack)
        p[t + 12] = ((th / 4) shl 4).toByte(); p[t + 13] = flags.toByte(); put16(p, t + 14, win)
        options.copyInto(p, t + 20)
        if (data != null && len > 0) System.arraycopy(data, off, p, t + th, len)
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += u16(p, i)
        pseudo += 6 + th + len
        put16(p, t + 16, checksum(p, t, th + len, pseudo))
        return p
    }

    companion object {
        const val FIN = 1; const val SYN = 2; const val RST = 4; const val PSH = 8; const val ACK = 16
        private val EOF = ByteArray(0)

        fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun s32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)
        fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
        fun put32(b: ByteArray, i: Int, v: Int) { put16(b, i, v ushr 16); put16(b, i + 2, v and 0xFFFF) }
        fun ip(a: ByteArray) = a.joinToString(".") { (it.toInt() and 0xFF).toString() }

        fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
            var sum = initial
            var i = off
            while (i + 1 < off + len) { sum += u16(b, i); i += 2 }
            if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }
    }
}

/** A SOCKS5 client (RFC 1928, with optional username/password from RFC 1929). */
object Socks5 {
    /** Opens a connection to [host]:[port] through the SOCKS5 proxy at [proxy]. [host] may be a name (resolved by the proxy). */
    fun connect(
        proxy: InetSocketAddress, host: String, port: Int, user: String? = null, pass: String? = null,
        protect: (Socket) -> Unit = {}, timeoutMs: Int = 60_000,
    ): Socket {
        val s = Socket()
        try {
            protect(s)
            s.connect(proxy, 15_000)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val o = s.getOutputStream(); val i = s.getInputStream()
            val auth = !user.isNullOrEmpty()
            o.write(if (auth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); o.flush()
            val m = readN(i, 2)
            if (m[0].toInt() != 5) throw IOException("Not a SOCKS5 proxy")
            when (m[1].toInt() and 0xFF) {
                0 -> {}
                2 -> {
                    val u = user!!.toByteArray(); val pw = (pass ?: "").toByteArray()
                    o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw); o.flush()
                    if (readN(i, 2)[1].toInt() != 0) throw IOException("The proxy refused the username or password")
                }
                else -> throw IOException("The proxy needs a login KDX doesn't support")
            }
            val ip4 = host.split('.').takeIf { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }
            val req = java.io.ByteArrayOutputStream().apply {
                write(byteArrayOf(5, 1, 0))
                if (ip4 != null) { write(1); ip4.forEach { write(it.toInt()) } }
                else { val h = host.toByteArray(); write(3); write(h.size); write(h) }
                write(port shr 8); write(port and 0xFF)
            }.toByteArray()
            o.write(req); o.flush()
            val r = readN(i, 4)
            if (r[1].toInt() != 0) throw IOException("Proxy: " + when (r[1].toInt()) {
                2 -> "connection not allowed"; 3 -> "network unreachable"; 4 -> "host unreachable"; 5 -> "connection refused"
                6 -> "timed out"; else -> "error ${r[1]}" })
            when (r[3].toInt()) { 1 -> readN(i, 6); 4 -> readN(i, 18); 3 -> readN(i, (i.read() and 0xFF) + 2); else -> throw IOException("Bad SOCKS reply") }
            s.soTimeout = 0
            return s
        } catch (e: Exception) { runCatching { s.close() }; throw if (e is IOException) e else IOException(e.message, e) }
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var p = 0
        while (p < n) { val r = i.read(b, p, n - p); if (r < 0) throw IOException("The proxy closed the connection"); p += r }
        return b
    }

    /** Sends one DNS query over TCP through the proxy (for proxies without their own DNS service). */
    fun dnsOverTcp(proxy: InetSocketAddress, server: String, query: ByteArray, user: String? = null, pass: String? = null): ByteArray? =
        runCatching {
            connect(proxy, server, 53, user, pass, timeoutMs = 10_000).use { s ->
                s.soTimeout = 10_000
                val o = s.getOutputStream()
                o.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()) + query); o.flush()
                val i = s.getInputStream()
                val l = readN(i, 2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
                readN(i, l)
            }
        }.getOrNull()
}
