package org.kdx.shell.core

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File

/**
 * Notices when KDX keeps restarting (as the home screen, Android starts it again right away each time it dies, which
 * looks like the screen blinking black) and why: a crash (KDX saves the details) or Android ending it (on Android 11+
 * Android says why, e.g. low memory).
 *
 * Checked as early as possible – when the process starts ([KdxApp]), before any of KDX's screen or state exists – so
 * a crash while KDX starts up or draws its screen is caught too:
 *  - [LOOP_STARTS] starts within [LOOP_WINDOW_MS]: [Mode.SAFE] – nothing heavy starts by itself.
 *  - [RECOVERY_STARTS] starts within [RECOVERY_WINDOW_MS] (safe mode didn't stop it): [Mode.RECOVERY] – KDX shows a
 *    plain Android recovery screen instead of its desktop, with the details.
 *
 * Everything else that can make the screen flicker without KDX dying (KDX's window being rebuilt, another app's
 * window covering it, Android asking for memory back) goes into an event log ([log]).
 */
object StartGuard {
    const val LOOP_STARTS = 3
    const val LOOP_WINDOW_MS = 3 * 60_000L
    const val RECOVERY_STARTS = 5
    const val RECOVERY_WINDOW_MS = 5 * 60_000L

    enum class Mode { NORMAL, SAFE, RECOVERY }

    /** This process's mode, decided by [onProcessStart]. */
    @Volatile var mode = Mode.NORMAL
    /** Recent start times (ms), this one included. */
    @Volatile var starts: List<Long> = emptyList(); private set

    private fun count(starts: List<Long>, now: Long, window: Long) = starts.count { now - it in 0..window }

    /** True when [starts] (timestamps, ms) contain [LOOP_STARTS] or more within the last [LOOP_WINDOW_MS] before [now]. */
    fun isLoop(starts: List<Long>, now: Long): Boolean = count(starts, now, LOOP_WINDOW_MS) >= LOOP_STARTS

    fun modeFor(starts: List<Long>, now: Long): Mode = when {
        count(starts, now, RECOVERY_WINDOW_MS) >= RECOVERY_STARTS -> Mode.RECOVERY
        isLoop(starts, now) -> Mode.SAFE
        else -> Mode.NORMAL
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kdx-guard", Context.MODE_PRIVATE)

    /** Records this start; returns the recent start times (this one included). */
    fun recordStart(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): List<Long> {
        val window = maxOf(LOOP_WINDOW_MS, RECOVERY_WINDOW_MS)
        val starts = (prefs.getString("recentStarts", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it in 0..window } + now
        prefs.edit().putString("recentStarts", starts.joinToString(",")).commit()
        return starts
    }

    /** Called first thing when KDX's main process starts. */
    fun onProcessStart(ctx: Context) {
        installCrashRecorder(ctx)
        val now = System.currentTimeMillis()
        starts = runCatching { recordStart(prefs(ctx), now) }.getOrDefault(listOf(now))
        mode = modeFor(starts, now)
        log(ctx, "KDX started (${starts.size} starts in the last ${RECOVERY_WINDOW_MS / 60_000} min) – mode ${mode.name.lowercase()}")
    }

    /** KDX has been running fine for a while: forget the recent starts. */
    fun markStable(ctx: Context) { prefs(ctx).edit().remove("recentStarts").apply() }

    fun crashFile(ctx: Context) = File(ctx.filesDir, "last-crash.txt")
    fun logFile(ctx: Context) = File(ctx.filesDir, "kdx-events.log")

    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    /** Adds a line to the event log (the last [LOG_LINES] lines are kept). */
    @Synchronized fun log(ctx: Context, msg: String) {
        runCatching {
            val f = logFile(ctx)
            val line = "${timeFmt.format(java.util.Date())} $msg\n"
            if (f.length() > 64_000) f.writeText(f.readLines().takeLast(LOG_LINES).joinToString("\n", postfix = "\n"))
            f.appendText(line)
        }
    }

    const val LOG_LINES = 200

    fun recentLog(ctx: Context, lines: Int = 60): String =
        runCatching { logFile(ctx).readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")

    /** Everything known about why KDX kept ending or flickering, for the user to copy. */
    fun report(ctx: Context): String = buildString {
        appendLine("KDX ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Starts in the last ${RECOVERY_WINDOW_MS / 60_000} minutes: ${starts.size}")
        val reasons = recentExitReasons(ctx)
        if (reasons.isNotEmpty()) { appendLine(); appendLine("What Android says:"); reasons.forEach { appendLine("• $it") } }
        val crash = runCatching { crashFile(ctx).takeIf { it.isFile }?.readText() }.getOrNull()
        if (crash != null) { appendLine(); appendLine(crash.take(4000)) }
        val log = recentLog(ctx)
        if (log.isNotBlank()) { appendLine(); appendLine("Recent events:"); appendLine(log) }
    }

    /** Saves the details of a crash in KDX's own code (then lets Android handle it as usual). */
    fun installCrashRecorder(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(app, previous))
    }

    private class Recorder(val ctx: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                crashFile(ctx).writeText("KDX crashed – ${java.util.Date()} (thread ${t.name})\n" + e.stackTraceToString().take(20_000))
                log(ctx, "CRASH: $e")
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
                        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Something KDX depends on stopped"
                        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "A permission of KDX changed"
                        else -> "KDX ended (reason ${info.reason})"
                    }
                    val pss = if (info.pss > 0) ", using ${info.pss / 1024} MB" else ""
                    "${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(info.timestamp))}: $why$pss" +
                        (info.description?.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "")
                }
        }.getOrDefault(emptyList())
    }

    /**
     * Flicker detector: KDX's window losing the screen (paused, or focus going to another window) [FLICKER_COUNT]
     * times within [FLICKER_WINDOW_MS] while KDX keeps running.
     */
    class Flicker(private val count: Int = FLICKER_COUNT, private val window: Long = FLICKER_WINDOW_MS) {
        private val times = ArrayDeque<Long>()
        /** Records one loss of the screen at [now]; true when that makes it flickering. */
        fun lost(now: Long): Boolean {
            times.addLast(now)
            while (times.isNotEmpty() && now - times.first() > window) times.removeFirst()
            return times.size >= count
        }
        fun reset() = times.clear()
    }

    const val FLICKER_COUNT = 5
    const val FLICKER_WINDOW_MS = 20_000L
}
