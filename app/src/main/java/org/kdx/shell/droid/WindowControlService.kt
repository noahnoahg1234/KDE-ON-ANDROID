package org.kdx.shell.droid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * KDX Window Control: maximise / resize Android app windows from KDX's taskbar. Android applies a size to a floating
 * window only when the app opens, and gives apps no call to resize an open one – but an accessibility service can see
 * where windows are and perform touch gestures. So this drags the window by its title bar to the desktop's corner,
 * then drags its bottom-right corner to the desktop's other corner, and checks the result.
 */
class WindowControlService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) checkFullscreen()
    }

    /** Is the app on top full screen (reaching where the taskbar is)? Then KDX's floating taskbar steps aside. */
    private fun checkFullscreen() = runCatching {
        val dm = resources.displayMetrics
        val top = windows.filter { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName?.toString() != packageName
        }.maxByOrNull { it.layer }
        val b = top?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
        org.kdx.shell.shell.OverlayTaskbar.coveredByFullscreen = b != null &&
            b.width() >= dm.widthPixels * 0.95 && b.bottom >= dm.heightPixels - 40 * dm.density
    }
    override fun onInterrupt() {}

    /** The app window of [pkg] on screen, or null. */
    private fun windowBounds(pkg: String): Rect? = runCatching {
        windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName?.toString() == pkg
        }?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
    }.getOrNull()

    private fun drag(from: Pair<Float, Float>, to: Pair<Float, Float>, done: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(from.first, from.second); lineTo(to.first, to.second) }
        // press, hold briefly (so it's a drag, not a fling), move
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 120, 600)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done(true) }
            override fun onCancelled(d: GestureDescription?) { done(false) }
        }, null)
        if (!ok) done(false)
    }

    /** Moves and resizes [pkg]'s window to [target]; [result] gets whether it ended up (about) there. */
    fun place(pkg: String, target: Rect, result: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val b = windowBounds(pkg) ?: return result(false)
        val dp = resources.displayMetrics.density
        // 1. move: grab the title bar Android draws at the window's top
        val grabY = b.top + 14 * dp
        drag(b.centerX().toFloat() to grabY, (target.left + b.width() / 2f) to (target.top + 14 * dp)) {
            main.postDelayed({
                val m = windowBounds(pkg) ?: return@postDelayed result(false)
                // 2. resize: Android's resize handle reaches a little outside the window's corner
                val edge = 6 * dp
                drag((m.right + edge / 2) to (m.bottom + edge / 2), target.right.toFloat() to target.bottom.toFloat()) {
                    main.postDelayed({
                        val r = windowBounds(pkg)
                        val slack = (40 * dp).toInt()
                        result(r != null && kotlin.math.abs(r.left - target.left) < slack && kotlin.math.abs(r.top - target.top) < slack &&
                            kotlin.math.abs(r.right - target.right) < slack && kotlin.math.abs(r.bottom - target.bottom) < slack)
                    }, 500)
                }
            }, 500)
        }
    }

    companion object {
        /** Android's lock screen / power menu (only an accessibility service may open them). */
        fun lockScreen(): Boolean = android.os.Build.VERSION.SDK_INT >= 28 && instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        fun powerMenu(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) == true

        @Volatile var instance: WindowControlService? = null
        val isOn get() = instance != null

        fun openSettings(ctx: android.content.Context) {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
