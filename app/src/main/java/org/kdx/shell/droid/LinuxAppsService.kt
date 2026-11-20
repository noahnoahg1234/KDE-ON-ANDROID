package org.kdx.shell.droid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps Linux apps running at full speed while they're open. As a foreground service KDX counts as an app the user
 * is actively using: Android doesn't freeze it in the background, doesn't throttle its CPU like a cached app, and is
 * far less likely to kill it (and its Linux programs) when memory runs low. A partial wake lock keeps the CPU
 * running with the screen off, so music keeps playing and downloads continue.
 */
class LinuxAppsService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        startForeground(NOTIFICATION_ID, notification(count))
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KDX:LinuxApps").apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(count: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux apps", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while DroidSystem's Linux apps are running"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("DroidSystem is running")
            .setContentText(if (count == 1) "1 Linux app open at full speed" else "$count Linux apps open at full speed")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "linux-apps"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_COUNT = "count"

        /** Starts/updates the service for [count] open Linux apps, or stops it when there are none. */
        fun update(ctx: Context, count: Int) {
            val i = Intent(ctx, LinuxAppsService::class.java)
            if (count <= 0) { ctx.stopService(i); return }
            runCatching { ctx.startForegroundService(i.putExtra(EXTRA_COUNT, count)) }
        }
    }
}
