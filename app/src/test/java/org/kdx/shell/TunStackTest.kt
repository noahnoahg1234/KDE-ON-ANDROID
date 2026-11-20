package org.kdx.shell

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.Socks5
import org.kdx.shell.droid.TunStack
import org.kdx.shell.droid.TunStack.Companion.ACK
import org.kdx.shell.droid.TunStack.Companion.FIN
import org.kdx.shell.droid.TunStack.Companion.PSH
import org.kdx.shell.droid.TunStack.Companion.RST
import org.kdx.shell.droid.TunStack.Companion.SYN
import org.kdx.shell.droid.TunStack.Companion.checksum
import org.kdx.shell.droid.TunStack.Companion.put16
import org.kdx.shell.droid.TunStack.Companion.put32
import org.kdx.shell.droid.TunStack.Companion.s32
import org.kdx.shell.droid.TunStack.Companion.u16
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TunStackTest {
    private val phone = byteArrayOf(10, 111, 223.toByte(), 1)
    private val server = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
    private val fromStack = LinkedBlockingQueue<ByteArray>()
    private val echo = ServerSocket(0).also { ss ->
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { it.getInputStream().copyTo(it.getOutputStream()) } } // echo, then close
            }
        }
    }
    private var connectedTo: String? = null
    private val stack = TunStack({ fromStack.put(it) }, { ip, port ->
        connectedTo = "${ip.hostAddress}:$port"; Socket("127.0.0.1", echo.localPort)
    })

    @After fun tearDown() { stack.close(); echo.close() }

    private class Seg(val flags: Int, val seq: Int, val ack: Int, val win: Int, val data: ByteArray)

    private fun next(timeout: Long = 5): Seg {
        val p = fromStack.poll(timeout, TimeUnit.SECONDS) ?: throw AssertionError("no packet from the stack")
        assertEquals("IP header checksum", 0, checksum(p, 0, 20, 0))
        var pseudo = 0L; for (i in 12 until 20 step 2) pseudo += u16(p, i); pseudo += 6 + p.size - 20
        assertEquals("TCP checksum", 0, checksum(p, 20, p.size - 20, pseudo))
        val off = ((p[32].toInt() shr 4) and 0xF) * 4
        return Seg(p[33].toInt() and 0x3F, s32(p, 24), s32(p, 28), u16(p, 34), p.copyOfRange(20 + off, p.size))
    }

    private fun send(flags: Int, seq: Int, ack: Int, data: ByteArray = ByteArray(0), port: Int = 40000) {
        val opt = if (flags and SYN != 0) byteArrayOf(2, 4, 0x05, 0x78) else ByteArray(0)
        val th = 20 + opt.size; val total = 20 + th + data.size
        val p = ByteArray(total)
        p[0] = 0x45; put16(p, 2, total); p[8] = 64; p[9] = 6; phone.copyInto(p, 12); server.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20, 0))
        put16(p, 20, port); put16(p, 22, 443); put32(p, 24, seq); put32(p, 28, ack)
        p[32] = ((th / 4) shl 4).toByte(); p[33] = flags.toByte(); put16(p, 34, 65535)
        opt.copyInto(p, 40); data.copyInto(p, 20 + th)
        stack.onPacket(p, p.size)
    }

    @Test fun fullConnectionThroughTheConnector() {
        send(SYN, 1000, 0)
        val synAck = next()
        assertEquals(SYN or ACK, synAck.flags)
        assertEquals(1001, synAck.ack)
        assertEquals("93.184.216.34:443", connectedTo)
        var mySeq = 1001
        var theirNext = synAck.seq + 1
        send(ACK, mySeq, theirNext)

        // 60 KB up, echoed back down in segments that must be acknowledged
        val sent = ByteArray(60_000) { (it * 7 + 3).toByte() }
        sent.toList().chunked(1400).forEach { c -> send(ACK or PSH, mySeq, theirNext, c.toByteArray()); mySeq += c.size }
        val got = java.io.ByteArrayOutputStream()
        var finSeen = false
        while (got.size() < sent.size || !finSeen) {
            val s = next()
            if (s.data.isNotEmpty()) {
                assertEquals("in order", theirNext, s.seq)
                got.write(s.data); theirNext += s.data.size
                send(ACK, mySeq, theirNext)
            }
            if (s.flags and FIN != 0) { finSeen = true; theirNext += 1; send(ACK, mySeq, theirNext) }
            if (got.size() == sent.size && !finSeen) send(FIN or ACK, mySeq, theirNext).also { mySeq += 1 } // we're done: close
        }
        assertArrayEquals(sent, got.toByteArray())
        Thread.sleep(300)
        assertEquals("connection cleaned up", 0, stack.connectionCount)
    }

    @Test fun refusedConnectionsAreReset() {
        val refusing = TunStack({ fromStack.put(it) }, { _, _ -> throw java.io.IOException("refused") })
        val p = ByteArray(40).apply {
            this[0] = 0x45; put16(this, 2, 40); this[9] = 6; phone.copyInto(this, 12); server.copyInto(this, 16)
            put16(this, 20, 40001); put16(this, 22, 80); put32(this, 24, 5); this[32] = 0x50; this[33] = SYN.toByte()
        }
        refusing.onPacket(p, p.size)
        val r = next()
        assertEquals(RST or ACK, r.flags); assertEquals(6, r.ack)
        refusing.close()
    }

    @Test fun ipv6AndOtherUdpAreDropped() {
        stack.onPacket(ByteArray(60).apply { this[0] = 0x60 }, 60)
        val udp = ByteArray(28).apply { this[0] = 0x45; put16(this, 2, 28); this[9] = 17; put16(this, 22, 443) }
        stack.onPacket(udp, udp.size)
        assertNull(fromStack.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun socks5ConnectsByNameWithLogin() {
        var requested: String? = null
        val proxy = ServerSocket(0)
        thread(isDaemon = true) {
            proxy.accept().use { c ->
                val i = java.io.DataInputStream(c.getInputStream()); val o = c.getOutputStream()
                i.readByte(); val n = i.readByte().toInt(); repeat(n) { i.readByte() }
                o.write(byteArrayOf(5, 2))
                i.readByte(); val u = ByteArray(i.readByte().toInt()).also { i.readFully(it) }
                val pw = ByteArray(i.readByte().toInt()).also { i.readFully(it) }
                o.write(byteArrayOf(1, if (String(u) == "me" && String(pw) == "pw") 0 else 1))
                i.readByte(); i.readByte(); i.readByte(); assertEquals(3, i.readByte().toInt())
                val host = ByteArray(i.readByte().toInt()).also { i.readFully(it) }
                requested = String(host) + ":" + i.readUnsignedShort()
                o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                Socket("127.0.0.1", echo.localPort).use { e ->
                    thread(isDaemon = true) { runCatching { c.getInputStream().copyTo(e.getOutputStream()) } }
                    e.getInputStream().let { ei -> val b = ByteArray(5); java.io.DataInputStream(ei).readFully(b); o.write(b) }
                }
            }
        }
        Socks5.connect(InetSocketAddress("127.0.0.1", proxy.localPort), "example.org", 443, "me", "pw").use { s ->
            s.getOutputStream().write("hello".toByteArray())
            val b = ByteArray(5); java.io.DataInputStream(s.getInputStream()).readFully(b)
            assertEquals("hello", String(b))
        }
        assertEquals("example.org:443", requested)
        proxy.close()
    }
}
