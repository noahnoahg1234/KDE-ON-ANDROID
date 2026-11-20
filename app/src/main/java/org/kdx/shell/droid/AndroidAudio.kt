package org.kdx.shell.droid

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plays DroidSystem's sound on the phone: reads the raw PCM stream from [LinuxAudio.PCM_PORT] and writes it to an
 * Android AudioTrack (48 kHz, stereo, 16-bit). Reconnects automatically while enabled.
 */
class AndroidAudio {
    @Volatile private var thread: Thread? = null
    @Volatile var enabled = false
        private set
    @Volatile var playing = false
        private set

    fun start() {
        if (enabled) return
        enabled = true
        thread = Thread({ run() }, "kdx-audio").apply { isDaemon = true; start() }
    }

    fun stop() {
        enabled = false
        thread?.interrupt(); thread = null
    }

    private fun run() {
        val minBuf = AudioTrack.getMinBufferSize(LinuxAudio.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        while (enabled) {
            var track: AudioTrack? = null
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", LinuxAudio.PCM_PORT), 2000)
                    sock.tcpNoDelay = true
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(LinuxAudio.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minBuf * 2, 16_384))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    track!!.play(); playing = true
                    val input = sock.getInputStream()
                    val buf = ByteArray(8192)
                    while (enabled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        var off = 0
                        while (off < n) { val w = track!!.write(buf, off, n - off); if (w <= 0) break; off += w }
                    }
                }
            } catch (_: Exception) {
                // sound server not up (yet) or restarted: retry shortly
            } finally {
                playing = false
                runCatching { track?.stop() }; runCatching { track?.release() }
            }
            if (enabled) runCatching { Thread.sleep(1500) }
        }
    }
}
