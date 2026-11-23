package org.kdx.shell.core

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File

/**
 * Notices when KDX keeps restarting (as the home screen, Android starts it again right away each time it dies, which
 * looks like the screen blinking black) and why: a crash (KDX saves the details) or Android ending it (on Android 11+
 * Android says why, e.g. low memory). After [LOOP_STARTS] starts within [LOOP_WINDOW_MS], KDX starts in safe mode:
 * nothing heavy starts by itself (KDE Plasma, WindSystem, automatic space saving), which breaks the loop.
 */
object StartGuard {
    const val LOOP_STARTS = 3
    const val LOOP_WINDOW_MS = 3 * 60_000L

    /** True when [starts] (timestamps, ms) contain [LOOP_STARTS] or more within the last [LOOP_WINDOW_MS] before [now]. */
    fun isLoop(starts: List<Long>, now: Long): Boolean = starts.count { now - it in 0..LOOP_WINDOW_MS } >= LOOP_STARTS

    /** Records this start; returns the recent start times (this one included). */
    fun recordStart(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): List<Long> {
        val starts = (prefs.getString("recentStarts", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it in 0..LOOP_WINDOW_MS } + now
        prefs.edit().putString("recentStarts", starts.joinToString(",")).commit()
        return starts
    }

    /** KDX has been running fine for a while: forget the recent starts. */
    fun markStable(prefs: SharedPreferences) { prefs.edit().remove("recentStarts").apply() }

    fun crashFile(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    /** Saves the details of a crash in KDX's own code (then lets Android handle it as usual). */
    fun installCrashRecorder(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(app, previous))
    }

    private class Recorder(val ctx: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                crashFile(ctx).writeText("KDX crashed – ${java.util.Date()} (thread ${t.name})\n" + e.stackTraceToString().take(20_000))
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Why Android ended KDX recently (Android 11+), newest first, in plain words. */
    fun recentExitReasons(ctx: Context): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
                .filter { it.processName == ctx.packageName }
                .map { info ->
                    val why = when (info.reason) {
                        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android ended KDX because the phone was low on memory"
                        android.app.ApplicationExitInfo.REASON_CRASH -> "KDX crashed"
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "KDX crashed (native code)"
                        android.app.ApplicationExitInfo.REASON_ANR -> "KDX stopped responding"
                        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android ended KDX for using too much CPU or memory"
                        android.app.ApplicationExitInfo.REASON_SIGNALED -> "KDX was killed (signal ${info.status})"
                        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "KDX was stopped from Android's settings"
                        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "KDX exited"
                        else -> "KDX ended (reason ${info.reason})"
                    }
                    val pss = if (info.pss > 0) ", using ${info.pss / 1024} MB" else ""
                    "${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(info.timestamp))}: $why$pss" +
                        (info.description?.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "")
                }
        }.getOrDefault(emptyList())
    }
}
