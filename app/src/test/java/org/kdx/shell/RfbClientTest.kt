package org.kdx.shell

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.kdx.shell.droid.RfbClient
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A tiny fake VNC server: checks that input from the caller's thread arrives, written on the sender thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RfbClientTest {
    @Test fun inputIsSentOffTheCallingThreadAndArrives() {
        val server = ServerSocket(0)
        val received = LinkedBlockingQueue<IntArray>()
        thread(isDaemon = true) {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream()); val o = DataOutputStream(s.getOutputStream())
                o.write("RFB 003.008\n".toByteArray()); o.flush()
                i.readFully(ByteArray(12))
                o.writeByte(1); o.writeByte(1); o.flush()            // one security type: None
                i.readUnsignedByte()                                 // client picks it
                o.writeInt(0); o.flush()                             // security OK
                i.readUnsignedByte()                                 // ClientInit
                o.writeShort(64); o.writeShort(48); o.write(ByteArray(16)); o.writeInt(4); o.write("test".toByteArray()); o.flush()
                while (true) {
                    when (val t = runCatching { i.readUnsignedByte() }.getOrElse { return@thread }) {
                        0 -> i.readFully(ByteArray(19))                              // SetPixelFormat
                        2 -> { i.readUnsignedByte(); repeat(i.readUnsignedShort()) { i.readInt() } } // SetEncodings
                        3 -> i.readFully(ByteArray(9))                               // FramebufferUpdateRequest
                        5 -> received += intArrayOf(i.readUnsignedByte(), i.readUnsignedShort(), i.readUnsignedShort()) // pointer
                        4 -> { i.readFully(ByteArray(7)) }                           // key
                        else -> error("unexpected message $t")
                    }
                }
            }
        }
        val rfb = RfbClient(port = server.localPort)
        rfb.connect()
        // the initial "claim the pointer" event
        assertArrayEquals(intArrayOf(0, 63, 47), received.poll(5, TimeUnit.SECONDS))
        rfb.pointer(10, 20, 1)
        rfb.pointer(10, 20, 0)
        assertArrayEquals(intArrayOf(1, 10, 20), received.poll(5, TimeUnit.SECONDS))
        assertArrayEquals(intArrayOf(0, 10, 20), received.poll(5, TimeUnit.SECONDS))
        assertEquals("rfb-send", rfb.lastSendThread)
        rfb.close(); server.close()
    }
}
