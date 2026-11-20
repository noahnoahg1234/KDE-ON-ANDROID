package org.kdx.shell.droid

/**
 * Keeps a Linux app window near 60 fps by changing its render resolution. Feed it the frame rate every couple of
 * seconds: while the app animates (frames keep coming) but stays under [low] fps, the scale steps down; once it
 * holds [high] fps for a while, it steps back up. Idle apps send few frames and are left alone. Several readings in
 * a row are needed before a change, so it doesn't flip-flop.
 */
class AutoScaler(
    var scale: Float = 1f,
    private val min: Float = 0.45f,
    private val step: Float = 0.15f,
    private val low: Int = 50,
    private val high: Int = 58,
    private val animatingFps: Int = 12,
) {
    private var lowCount = 0
    private var highCount = 0

    /** Returns true when [scale] changed. */
    fun update(fps: Int): Boolean {
        val animating = fps >= animatingFps
        lowCount = if (animating && fps < low) lowCount + 1 else 0
        highCount = if (animating && fps >= high) highCount + 1 else if (!animating) highCount else 0
        if (lowCount >= 2 && scale > min + 0.01f) {
            scale = (scale - step).coerceAtLeast(min); lowCount = 0; highCount = 0; return true
        }
        if (highCount >= 8 && scale < 0.99f) {
            scale = (scale + step).coerceAtMost(1f); highCount = 0; return true
        }
        return false
    }
}
