package org.kdx.shell.droid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal RFB (VNC) client for the local X display that DroidSystem's Linux apps draw on.
 * Supports RFB 3.3–3.8, "None" security, Raw + CopyRect + DesktopSize encodings, pointer, keys and clipboard.
 * The framebuffer is kept as ARGB ints so it can be copied straight into an Android Bitmap.
 */
class RfbClient(private val host: String = "127.0.0.1", private val port: Int) {
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var pixels = IntArray(0); private set
    @Volatile var name = ""; private set
    @Volatile var running = false; private set
    private lateinit var sock: Socket
    private lateinit var input: DataInputStream
    private lateinit var out: DataOutputStream
    private val lock = Any()

    /** Called (on the reader thread) after each framebuffer update with the dirty rectangle union. */
    var onFrame: () -> Unit = {}
    var onResize: (Int, Int) -> Unit = { _, _ -> }
    var onClipboard: (String) -> Unit = {}

    fun connect(timeoutMs: Int = 3000) {
        sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(host, port), timeoutMs)
        input = DataInputStream(sock.getInputStream().buffered(1 shl 16))
        out = DataOutputStream(sock.getOutputStream().buffered())
        // version
        val ver = ByteArray(12).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        val minor = ver.substring(8, 11).toIntOrNull() ?: 3
        val use = if (minor >= 8) 8 else if (minor >= 7) 7 else 3
        out.write("RFB 003.00$use\n".toByteArray()); out.flush()
        // security
        if (use >= 7) {
            val n = input.readUnsignedByte()
            if (n == 0) throw IOException("VNC server refused: " + reason())
            val types = ByteArray(n).also { input.readFully(it) }.map { it.toInt() and 0xFF }
            if (1 !in types) throw IOException("VNC server needs a password (types $types)")
            out.writeByte(1); out.flush()
            if (use == 8 && input.readInt() != 0) throw IOException("VNC security failed: " + reason())
        } else {
            val t = input.readInt()
            if (t == 0) throw IOException("VNC server refused: " + reason())
            if (t != 1) throw IOException("VNC server needs a password")
        }
        // init
        out.writeByte(1); out.flush() // shared
        width = input.readUnsignedShort(); height = input.readUnsignedShort()
        input.skipBytes(16) // server pixel format – we set our own
        name = ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
        pixels = IntArray(width * height)
        // 32bpp, depth 24, little endian, true colour, rgb max 255, shifts R16 G8 B0 => 0x00RRGGBB
        out.writeByte(0); out.write(ByteArray(3))
        out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
        out.writeShort(255); out.writeShort(255); out.writeShort(255)
        out.writeByte(16); out.writeByte(8); out.writeByte(0); out.write(ByteArray(3))
        val encodings = intArrayOf(1 /* CopyRect */, 0 /* Raw */, -223 /* DesktopSize */)
        out.writeByte(2); out.writeByte(0); out.writeShort(encodings.size); encodings.forEach { out.writeInt(it) }
        requestUpdate(false)
        running = true
    }

    private fun reason(): String = runCatching { ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8) }.getOrDefault("")

    fun requestUpdate(incremental: Boolean) = send {
        out.writeByte(3); out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0); out.writeShort(0); out.writeShort(width); out.writeShort(height)
    }

    /** Reads server messages until the connection closes. Run on a background thread. */
    fun loop() {
        try {
            while (running) {
                when (val type = input.readUnsignedByte()) {
                    0 -> { readUpdate(); onFrame(); requestUpdate(true) }
                    1 -> { input.skipBytes(3); val n = input.readUnsignedShort(); input.skipBytes(n * 6) } // colour map
                    2 -> {} // bell
                    3 -> { input.skipBytes(3); onClipboard(ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)) }
                    else -> throw IOException("Unknown VNC message $type")
                }
            }
        } finally { close() }
    }

    private fun readUpdate() {
        input.skipBytes(1)
        val n = input.readUnsignedShort()
        repeat(n) {
            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
            when (val enc = input.readInt()) {
                0 -> { // Raw
                    val row = ByteArray(w * 4)
                    for (j in 0 until h) {
                        input.readFully(row)
                        val base = (y + j) * width + x
                        if (y + j >= height) continue
                        var k = 0
                        for (i in 0 until minOf(w, width - x)) {
                            pixels[base + i] = (0xFF shl 24) or ((row[k + 2].toInt() and 0xFF) shl 16) or
                                ((row[k + 1].toInt() and 0xFF) shl 8) or (row[k].toInt() and 0xFF)
                            k += 4
                        }
                    }
                }
                1 -> { // CopyRect
                    val sx = input.readUnsignedShort(); val sy = input.readUnsignedShort()
                    val tmp = IntArray(w * h)
                    for (j in 0 until h) System.arraycopy(pixels, (sy + j) * width + sx, tmp, j * w, w)
                    for (j in 0 until h) System.arraycopy(tmp, j * w, pixels, (y + j) * width + x, w)
                }
                -223 -> { // DesktopSize
                    width = w; height = h; pixels = IntArray(w * h); onResize(w, h)
                }
                else -> throw IOException("Unsupported encoding $enc")
            }
        }
    }

    // ------------------------------------------------------------------ input
    private inline fun send(block: () -> Unit) {
        synchronized(lock) { runCatching { block(); out.flush() } }
    }

    /** buttons: bit0 left, bit1 middle, bit2 right, bit3/4 wheel up/down. */
    fun pointer(x: Int, y: Int, buttons: Int) = send {
        out.writeByte(5); out.writeByte(buttons)
        out.writeShort(x.coerceIn(0, maxOf(0, width - 1))); out.writeShort(y.coerceIn(0, maxOf(0, height - 1)))
    }

    fun key(keysym: Int, down: Boolean) = send {
        out.writeByte(4); out.writeByte(if (down) 1 else 0); out.writeShort(0); out.writeInt(keysym)
    }

    fun tapKey(keysym: Int) { key(keysym, true); key(keysym, false) }

    /** Types text by sending each character's keysym. */
    fun type(text: String) = text.forEach { ch ->
        when (ch) {
            '\n' -> tapKey(KEY_RETURN)
            '\t' -> tapKey(KEY_TAB)
            else -> tapKey(keysymFor(ch))
        }
    }

    fun clipboard(text: String) = send {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        out.writeByte(6); out.write(ByteArray(3)); out.writeInt(b.size); out.write(b)
    }

    fun close() {
        running = false
        runCatching { sock.close() }
    }

    companion object {
        const val KEY_BACKSPACE = 0xff08
        const val KEY_TAB = 0xff09
        const val KEY_RETURN = 0xff0d
        const val KEY_ESCAPE = 0xff1b
        const val KEY_DELETE = 0xffff
        const val KEY_HOME = 0xff50
        const val KEY_LEFT = 0xff51
        const val KEY_UP = 0xff52
        const val KEY_RIGHT = 0xff53
        const val KEY_DOWN = 0xff54
        const val KEY_PAGE_UP = 0xff55
        const val KEY_PAGE_DOWN = 0xff56
        const val KEY_END = 0xff57
        const val KEY_SHIFT = 0xffe1
        const val KEY_CTRL = 0xffe3
        const val KEY_ALT = 0xffe9
        const val KEY_SUPER = 0xffeb
        fun keysymFor(ch: Char): Int = if (ch.code in 0x20..0x7e || ch.code in 0xa0..0xff) ch.code else 0x01000000 or ch.code
    }
}
