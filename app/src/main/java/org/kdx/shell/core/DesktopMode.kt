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
    fun bounds(n: Int, w: Int, h: Int, area: Rect = Rect(0, 0, w, h)): Rect {
        // inside [area] (the desktop: not under KDX's taskbar)
        val aw = area.width(); val ah = area.height()
        val ww = (aw * 0.62f).toInt().coerceAtLeast(minOf(aw, 600))
        val wh = (ah * 0.7f).toInt().coerceAtLeast(minOf(ah, 500))
        val step = (minOf(aw, ah) * 0.04f).toInt().coerceAtLeast(24)
        val k = n % 6
        val left = area.left + (aw / 12 + k * step).coerceAtMost((aw - ww).coerceAtLeast(0))
        val top = area.top + (ah / 12 + k * step).coerceAtMost((ah - wh).coerceAtLeast(0))
        return Rect(left, top, left + ww, top + wh)
    }

    /**
     * Android's call that asks for a floating window (ActivityOptions.setLaunchWindowingMode) is hidden from apps
     * (Android 9+ blocks it), so without this KDX's request quietly fell back to a full-screen app. KDX lifts the block
     * for ActivityOptions only (with LSPosed's HiddenApiBypass).
     */
    private val setWindowingMode: java.lang.reflect.Method? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions;")
        }
        runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType) }.getOrNull()
    }

    /** True if KDX can ask Android for a floating window (the hidden call is reachable). */
    val canRequestWindows get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.P || setWindowingMode != null

    /** Options that open an activity in a floating window at [bounds]. */
    fun options(bounds: Rect): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val o = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        runCatching { setWindowingMode?.invoke(o, WINDOWING_MODE_FREEFORM) }
        return o.toBundle()
    }

    /** True if the user allowed KDX "Usage access" (to see which apps are open, for the taskbar). */
    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun openUsageAccess(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Android usage events since [since] (ms), as (package, type, time), oldest first. */
    fun usageEvents(ctx: Context, since: Long, until: Long): List<Triple<String, Int, Long>> = runCatching {
        val usm = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java)
        val ev = usm.queryEvents(since, until)
        val e = android.app.usage.UsageEvents.Event()
        buildList { while (ev.hasNextEvent()) { ev.getNextEvent(e); add(Triple(e.packageName, e.eventType, e.timeStamp)) } }
    }.getOrDefault(emptyList())

    /** The screen area minus Android's own bars, from the system's dimensions (0 if unknown). */
    fun systemBarPx(ctx: Context, name: String): Int {
        val id = ctx.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
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

/**
 * Which Android apps have open windows, from Android's usage events (needs "Usage access" – Android doesn't otherwise
 * tell an app which other apps are open). Kept free of Android classes so it can be tested.
 *
 * Android reports an activity being destroyed as "stopped" (it rewrites the event), and a window that's merely hidden
 * – behind KDX's full-screen desktop – is stopped too. The difference is KDX: a window can only be hidden behind KDX
 * while KDX is in front. So an app that stops while KDX isn't in front had its window closed.
 */
class OpenAppTracker {
    /** Android's UsageEvents.Event types used here. */
    companion object {
        const val RESUMED = 1          // ACTIVITY_RESUMED (MOVE_TO_FOREGROUND)
        const val STOPPED = 23         // ACTIVITY_STOPPED – also what an activity's destruction is reported as
        /** A stop this soon after KDX came in front still counts as "hidden behind KDX" (events arrive in order). */
        const val GRACE_MS = 1500L
    }

    private val open = LinkedHashSet<String>()
    /** Packages with an open window, oldest first. */
    val packages: List<String> get() = open.toList()

    /** Times (ms) KDX was in front: start to end (null = still in front). */
    private val kdxFront = ArrayDeque<Pair<Long, Long?>>()

    fun kdxCameToFront(t: Long) { if (kdxFront.lastOrNull()?.second != null || kdxFront.isEmpty()) kdxFront.addLast(t to null); trim(t) }
    fun kdxLeftFront(t: Long) { kdxFront.removeLastOrNull()?.let { (s, e) -> kdxFront.addLast(s to (e ?: t)) } }

    private fun trim(now: Long) { while (kdxFront.size > 1 && (kdxFront.first().second ?: now) < now - 10 * 60_000) kdxFront.removeFirst() }

    /** True if KDX was in front at [t] (or had just come in front). */
    fun kdxInFrontAt(t: Long): Boolean = kdxFront.any { (s, e) -> t >= s - GRACE_MS && (e == null || t <= e + GRACE_MS) }

    /**
     * Applies events (package, type, time ms) in time order. [relevant] filters packages (apps with a launcher entry,
     * not KDX). An app is open from its first resumed activity until it stops while KDX isn't in front.
     */
    fun apply(events: List<Triple<String, Int, Long>>, relevant: (String) -> Boolean) {
        for ((pkg, type, t) in events) {
            if (!relevant(pkg)) continue
            when (type) {
                RESUMED -> { open -= pkg; open += pkg } // most recently used last
                STOPPED -> if (!kdxInFrontAt(t)) open -= pkg
            }
        }
    }

    fun add(pkg: String) { open += pkg }
    fun remove(pkg: String) { open -= pkg }
}

/** Margins (px) KDX's own bars take: where a maximised Android window must not go, so the taskbar stays visible. */
data class DesktopMargins(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun bounds(screenW: Int, screenH: Int) = Rect(left, top, (screenW - right).coerceAtLeast(left + 1), (screenH - bottom).coerceAtLeast(top + 1))

    companion object {
        /** KDX's bars for [panel]: the panel (48 dp) or the dock (60 dp) with its top bar (34 dp), plus Android's bars. */
        fun of(panel: PanelPosition, density: Float, statusBarPx: Int, navBarPx: Int): DesktopMargins {
            fun dp(v: Int) = (v * density).toInt()
            return when (panel) {
                PanelPosition.BOTTOM -> DesktopMargins(0, statusBarPx, 0, navBarPx + dp(48))
                PanelPosition.TOP -> DesktopMargins(0, statusBarPx + dp(48), 0, navBarPx)
                PanelPosition.LEFT_DOCK -> DesktopMargins(dp(60), statusBarPx + dp(34), 0, navBarPx)
                PanelPosition.RIGHT_DOCK -> DesktopMargins(0, statusBarPx + dp(34), dp(60), navBarPx)
            }
        }
    }
}

/**
 * KDX's look. PLASMA: KDE Plasma's Breeze look (the default). DESK: KDX's own dark desktop style – dark windows with
 * thin title bars and round buttons, a strip of round app icons on the desktop's left edge, a big start menu with
 * large round icons, and big folder icons on the desktop.
 */
enum class ShellStyle(val label: String) { PLASMA("Plasma (Breeze)"), DESK("Desk (dark desktop)") }
