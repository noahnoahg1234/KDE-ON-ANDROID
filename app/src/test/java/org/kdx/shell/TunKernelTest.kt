package org.kdx.shell

import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.kdx.shell.droid.TunStack
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Opt-in: the Linux kernel's own TCP talks to [TunStack] through a real tun device (needs root):
 *   ./gradlew testDebugUnitTest --tests '*TunKernelTest*' -Pkdx.tunRelay=$PWD/tools/tunrelay.py
 */
class TunKernelTest {
    @Test fun curlDownloadsThroughTheStack() {
        val relay = System.getProperty("kdx.tunRelay")
        assumeTrue("set -Pkdx.tunRelay", relay != null)
        val body = ByteArray(8 * 1024 * 1024) { (it * 31 + it / 977).toByte() }
        val http = java.net.ServerSocket(0)
        thread(isDaemon = true) {
            while (!http.isClosed) {
                val c = runCatching { http.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { c.use { s ->
                    val i = java.io.DataInputStream(java.io.BufferedInputStream(s.getInputStream()))
                    var len = 0
                    while (true) { val l = readLine(i); if (l.isEmpty()) break; if (l.startsWith("Content-Length:", true)) len = l.substringAfter(':').trim().toInt() }
                    val up = ByteArray(len).also { i.readFully(it) }
                    val reply = if (len > 0) MessageDigest.getInstance("SHA-256").digest(up).joinToString("") { "%02x".format(it) }.toByteArray() else body
                    s.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(reply); flush() }
                } }
            }
        }
        val p = ProcessBuilder("python3", relay).start()
        val toTun = p.outputStream
        val stack = TunStack({ pkt ->
            synchronized(toTun) { toTun.write(byteArrayOf((pkt.size shr 8).toByte(), pkt.size.toByte())); toTun.write(pkt); toTun.flush() }
        }, { ip, port -> assertEquals("10.66.0.2", ip.hostAddress); assertEquals(80, port); Socket("127.0.0.1", http.localPort) })
        try {
            assertEquals("ready", p.errorStream.bufferedReader().readLine())
            thread(isDaemon = true) {
                val i = DataInputStream(p.inputStream)
                runCatching { while (true) { val n = i.readUnsignedShort(); val b = ByteArray(n); i.readFully(b); stack.onPacket(b, n) } }
            }
            val out = java.io.File.createTempFile("kdx", ".bin")
            val t0 = System.nanoTime()
            val curl = ProcessBuilder("curl", "-sS", "--noproxy", "*", "-m", "60", "-o", out.path, "http://10.66.0.2/").redirectErrorStream(true).start()
            val msg = curl.inputStream.bufferedReader().readText()
            assertEquals("curl: $msg", 0, curl.waitFor())
            val secs = (System.nanoTime() - t0) / 1e9
            println("8 MB through TunStack in %.2f s (%.1f MB/s), up %d B, down %d B".format(secs, 8 / secs, stack.bytesUp.get(), stack.bytesDown.get()))
            val md = { b: ByteArray -> MessageDigest.getInstance("SHA-256").digest(b).toList() }
            assertEquals(md(body), md(out.readBytes()))
            out.delete()
            // upload: 6 MB from curl, the server answers with its SHA-256
            val upload = ByteArray(6 * 1024 * 1024) { (it * 13 + it / 501).toByte() }
            val upFile = java.io.File.createTempFile("kdx", ".up").apply { writeBytes(upload) }
            val c2 = ProcessBuilder("curl", "-sS", "--noproxy", "*", "-m", "60", "-H", "Expect:", "--data-binary", "@${upFile.path}", "http://10.66.0.2/")
                .redirectErrorStream(true).start()
            val answer = c2.inputStream.bufferedReader().readText()
            assertEquals(0, c2.waitFor())
            assertEquals(MessageDigest.getInstance("SHA-256").digest(upload).joinToString("") { "%02x".format(it) }, answer)
            upFile.delete()
            Thread.sleep(500)
            assertEquals("all connections closed", 0, stack.connectionCount)
        } finally { stack.close(); p.destroy(); http.close() }
    }

    private fun readLine(i: java.io.DataInputStream): String {
        val sb = StringBuilder()
        while (true) { val c = i.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
        return sb.toString()
    }
}
