package org.kdx.shell.droid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal RFB (VNC) client for the local X display that DroidSystem's Linux apps draw on.
 * Supports RFB 3.3–3.8, "None" security, Raw + CopyRect + DesktopSize encodings, pointer, keys and clipboard.
 *
 * Built for 60 fps: the framebuffer is kept as bytes in Android's own ARGB_8888 memory order (R,G,B,A), so pixel
 * rows are read straight into it and the whole frame goes to a Bitmap with one native copy; with Continuous
 * Updates (+ Fence for pacing) the server pushes frames as they change instead of waiting for requests.
 */
class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val useContinuous: Boolean = true,
    private val useFence: Boolean = true,
    /** Ask for the X server's own pixel layout (B,G,R,pad bytes): no conversion on the server side. */
    val serverOrder: Boolean = false,
) {
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    /** Framebuffer, 4 bytes per pixel in R,G,B,A order (= Android Bitmap.Config.ARGB_8888 memory layout). */
    @Volatile var buffer = ByteArray(0); private set
    /** The framebuffer as ARGB ints (a converted copy – for tests and screenshots). */
    val pixels: IntArray
        get() {
            val b = buffer
            return IntArray(b.size / 4) { i ->
                val k = i * 4
                (0xFF shl 24) or ((b[k].toInt() and 0xFF) shl 16) or ((b[k + 1].toInt() and 0xFF) shl 8) or (b[k + 2].toInt() and 0xFF)
            }
        }
    /** Frames received so far (for measuring the frame rate). */
    @Volatile var frames = 0L; private set
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
        buffer = ByteArray(width * height * 4)
        // 32bpp, depth 24, little endian, true colour, shifts R0 G8 B16 => bytes R,G,B,pad – Android's RGBA order
        out.writeByte(0); out.write(ByteArray(3))
        out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
        out.writeShort(255); out.writeShort(255); out.writeShort(255)
        if (serverOrder) { out.writeByte(16); out.writeByte(8); out.writeByte(0) } else { out.writeByte(0); out.writeByte(8); out.writeByte(16) }
        out.write(ByteArray(3))
        // Cursor (-239): the server sends the pointer shape separately instead of drawing it into the picture –
        // we never draw it, which hides the mouse pointer (touch screens don't need one).
        // ExtendedDesktopSize (-308): lets us resize the X screen to fit the KDX window.
        // ContinuousUpdates (-313) + Fence (-312): the server pushes frames (paced by fences) without requests.
        val encodings = listOfNotNull(1 /* CopyRect */, 0 /* Raw */, -223 /* DesktopSize */, -308 /* ExtendedDesktopSize */,
            -239 /* Cursor */, (-313).takeIf { useContinuous } /* ContinuousUpdates */, (-312).takeIf { useFence } /* Fence */)
        out.writeByte(2); out.writeByte(0); out.writeShort(encodings.size); encodings.forEach { out.writeInt(it) }
        requestUpdate(false)
        running = true
        // TigerVNC draws the pointer into the picture while it sits where the viewer didn't put it; claiming the
        // pointer position up front means only the (discarded) cursor shape is sent, so no pointer is visible.
        pointer(width - 1, height - 1, 0)
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
                    0 -> { readUpdate(); frames++; onFrame(); if (!continuous) requestUpdate(true) }
                    150 -> { // EndOfContinuousUpdates: the server supports them – switch them on
                        if (!continuous) { continuous = true; enableContinuous() }
                    }
                    248 -> { // ServerFence: answer it, so the server can pace frames to what we keep up with
                        input.skipBytes(3)
                        val flags = input.readInt()
                        val payload = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                        if (flags and FENCE_REQUEST != 0) send {
                            out.writeByte(248); out.write(ByteArray(3))
                            out.writeInt(flags and (FENCE_BLOCK_BEFORE or FENCE_BLOCK_AFTER or FENCE_SYNC_NEXT))
                            out.writeByte(payload.size); out.write(payload)
                        }
                    }
                    1 -> { input.skipBytes(3); val n = input.readUnsignedShort(); input.skipBytes(n * 6) } // colour map
                    2 -> {} // bell
                    3 -> { input.skipBytes(3); onClipboard(ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)) }
                    else -> throw IOException("Unknown VNC message $type")
                }
            }
        } finally { close() }
    }

    /** True once the server pushes frames by itself (Continuous Updates). */
    @Volatile var continuous = false; private set

    private fun enableContinuous() = send {
        out.writeByte(150); out.writeByte(1)
        out.writeShort(0); out.writeShort(0); out.writeShort(width); out.writeShort(height)
    }

    /** True once the server said it supports client-requested resizing. */
    @Volatile var canResize = false; private set
    private var screenId = 0

    private fun resizeTo(w: Int, h: Int) {
        width = w; height = h; buffer = ByteArray(w * h * 4); onResize(w, h)
        if (continuous) enableContinuous() // continuous updates cover a fixed area: extend it to the new size
        // X clamps the pointer into the new screen; re-claim its position so it isn't drawn
        pointer(minOf(lastX, w - 1), minOf(lastY, h - 1), 0)
    }
    @Volatile private var lastX = 0
    @Volatile private var lastY = 0

    /** Asks the X server to change its screen size (TigerVNC resizes via RandR). */
    fun setDesktopSize(w: Int, h: Int) {
        if (!canResize || (w == width && h == height)) return
        send {
            out.writeByte(251); out.writeByte(0)
            out.writeShort(w); out.writeShort(h)
            out.writeByte(1); out.writeByte(0)
            out.writeInt(screenId); out.writeShort(0); out.writeShort(0); out.writeShort(w); out.writeShort(h); out.writeInt(0)
        }
    }

    private fun readUpdate() {
        input.skipBytes(1)
        val n = input.readUnsignedShort()
        repeat(n) {
            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
            when (val enc = input.readInt()) {
                0 -> { // Raw: rows go straight into the framebuffer; only the padding byte becomes opaque alpha
                    val buf = buffer
                    val cw = maxOf(0, minOf(w, width - x))
                    for (j in 0 until h) {
                        if (y + j >= height || cw == 0) { input.skipBytes(w * 4); continue }
                        val off = ((y + j) * width + x) * 4
                        input.readFully(buf, off, cw * 4)
                        if (w > cw) input.skipBytes((w - cw) * 4)
                        var k = off + 3
                        val end = off + cw * 4
                        while (k < end) { buf[k] = -1; k += 4 }
                    }
                }
                1 -> { // CopyRect
                    val sx = input.readUnsignedShort(); val sy = input.readUnsignedShort()
                    val buf = buffer
                    val tmp = ByteArray(w * h * 4)
                    for (j in 0 until h) System.arraycopy(buf, ((sy + j) * width + sx) * 4, tmp, j * w * 4, w * 4)
                    for (j in 0 until h) System.arraycopy(tmp, j * w * 4, buf, ((y + j) * width + x) * 4, w * 4)
                }
                -223 -> { // DesktopSize
                    resizeTo(w, h)
                }
                -308 -> { // ExtendedDesktopSize: x = reason, y = status; then the screen layout
                    val screens = input.readUnsignedByte(); input.skipBytes(3)
                    repeat(screens) { val id = input.readInt(); input.skipBytes(12); screenId = id }
                    canResize = true
                    if (y == 0 && (w != width || h != height)) resizeTo(w, h)
                }
                -239 -> { // Cursor shape: read and discard, so no pointer is ever shown
                    input.skipBytes(w * h * 4 + (w + 7) / 8 * h)
                }
                else -> throw IOException("Unsupported encoding $enc")
            }
        }
    }

    // ------------------------------------------------------------------ input
    /**
     * Every outgoing message (taps, keys, resize, update requests) is written by one background thread, in order.
     * Android forbids socket I/O on the main thread (NetworkOnMainThreadException), and input is sent from the UI
     * thread – writing directly there silently dropped every tap and key press, so apps looked frozen.
     */
    private val outbox = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "rfb-send").apply { isDaemon = true } }

    /** Thread that performed the last write (for tests). */
    @Volatile internal var lastSendThread: String = ""

    private fun send(block: () -> Unit) {
        runCatching {
            outbox.execute {
                synchronized(lock) {
                    runCatching { block(); out.flush(); lastSendThread = Thread.currentThread().name }
                        .onFailure { if (running) android.util.Log.w("KDX-RFB", "send failed: $it") }
                }
            }
        }
    }

    /** buttons: bit0 left, bit1 middle, bit2 right, bit3/4 wheel up/down. */
    fun pointer(x: Int, y: Int, buttons: Int) = send {
        lastX = x.coerceIn(0, maxOf(0, width - 1)); lastY = y.coerceIn(0, maxOf(0, height - 1))
        out.writeByte(5); out.writeByte(buttons)
        out.writeShort(lastX); out.writeShort(lastY)
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
        runCatching { outbox.execute { runCatching { sock.close() } } }.onFailure { runCatching { sock.close() } }
        outbox.shutdown()
    }

    companion object {
        private const val FENCE_BLOCK_BEFORE = 1
        private const val FENCE_BLOCK_AFTER = 2
        private const val FENCE_SYNC_NEXT = 4
        private const val FENCE_REQUEST = 1 shl 31
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
