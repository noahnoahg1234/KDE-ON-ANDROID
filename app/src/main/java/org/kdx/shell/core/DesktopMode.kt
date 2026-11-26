package org.kdx.shell.core

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.Settings

/**
 * KDX desktop resolution: how much fits on the screen. A phone shows its UI at its own density (big, finger-sized);
 * a higher "resolution" draws everything smaller, so the desktop has room like a PC monitor. [longSide] is the
 * number of logical pixels across the screen's long side (like a monitor's 1920 in 1920 × 1080).
 */
enum class DesktopResolution(val label: String, val longSide: Int?) {
    PHONE("Phone (default)", null),
    HD("1280 × 720", 1280),
    HD_PLUS("1600 × 900", 1600),
    FULL_HD("1920 × 1080", 1920),
    QHD("2560 × 1440", 2560);

    companion object {
        /** Smallest density KDX goes to: below this, text gets too small to read on a phone. */
        const val MIN_DENSITY = 0.6f

        /** The density to draw KDX with on a [widthPx] × [heightPx] screen whose own density is [systemDensity]. */
        fun density(res: DesktopResolution, widthPx: Int, heightPx: Int, systemDensity: Float): Float {
            val long = res.longSide ?: return systemDensity
            val longPx = maxOf(widthPx, heightPx)
            if (longPx <= 0) return systemDensity
            return (longPx.toFloat() / long).coerceAtLeast(MIN_DENSITY)
        }

        /** What [res] gives on this screen in logical pixels, e.g. "1920 × 864" (the phone's shape, not 16:9). */
        fun actualSize(res: DesktopResolution, widthPx: Int, heightPx: Int, systemDensity: Float): Pair<Int, Int> {
            val d = density(res, widthPx, heightPx, systemDensity)
            return (maxOf(widthPx, heightPx) / d).toInt() to (minOf(widthPx, heightPx) / d).toInt()
        }
    }
}

/**
 * Android apps in floating, resizable windows (Android's "freeform" mode). Android has it built in, but on phones it
 * is switched off until "Enable freeform windows" is turned on in Developer options (and the phone restarted).
 */
object AndroidWindows {
    /** Android's windowing mode number for freeform windows (WindowConfiguration.WINDOWING_MODE_FREEFORM). */
    private const val WINDOWING_MODE_FREEFORM = 5

    fun supported(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val pm = ctx.packageManager
        val cr = ctx.contentResolver
        return pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            runCatching { Settings.Global.getInt(cr, "enable_freeform_support", 0) != 0 }.getOrDefault(false) ||
            runCatching { Settings.Global.getInt(cr, "force_resizable_activities", 0) != 0 }.getOrDefault(false)
    }

    /** Where the [n]th window opens on a [w] × [h] px screen: cascaded like a PC's new windows. */
    fun bounds(n: Int, w: Int, h: Int): Rect {
        val ww = (w * 0.62f).toInt().coerceAtLeast(minOf(w, 600))
        val wh = (h * 0.7f).toInt().coerceAtLeast(minOf(h, 500))
        val step = (minOf(w, h) * 0.04f).toInt().coerceAtLeast(24)
        val k = n % 6
        val left = (w / 12 + k * step).coerceAtMost((w - ww).coerceAtLeast(0))
        val top = (h / 12 + k * step).coerceAtMost((h - wh).coerceAtLeast(0))
        return Rect(left, top, left + ww, top + wh)
    }

    /** Options that open an activity in a floating window at [bounds]. */
    fun options(bounds: Rect): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val o = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        // the windowing mode isn't public API: without it, Android opens a window only where freeform is the default
        runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, WINDOWING_MODE_FREEFORM)
        }
        return o.toBundle()
    }

    /** Opens Developer options, where "Enable freeform windows" is. */
    fun openDeveloperOptions(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

/**
 * Where the taskbar is. The side docks are laid out like Ubuntu's desktop: a dock with the apps along the side
 * (pinned apps, open windows marked with dots, "Show Applications" at the end) and a thin top bar with the clock and
 * the status icons.
 */
enum class PanelPosition(val label: String) {
    BOTTOM("Bottom panel"),
    TOP("Top panel"),
    LEFT_DOCK("Side dock, left (like Ubuntu)"),
    RIGHT_DOCK("Side dock, right");

    val isDock get() = this == LEFT_DOCK || this == RIGHT_DOCK
}
