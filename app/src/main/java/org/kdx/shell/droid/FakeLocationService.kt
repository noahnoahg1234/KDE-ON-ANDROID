package org.kdx.shell.droid

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock

/**
 * Fake GPS location: replaces the location Android gives apps (GPS and network providers) with a chosen place, using
 * Android's mock location feature. Android only allows this for the app picked in
 * Developer options → "Select mock location app", so the user stays in control.
 */
class FakeLocationService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopFaking(); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification())
        if (!running) startFaking()
        return START_STICKY
    }

    override fun onDestroy() { stopFaking(); super.onDestroy() }

    private fun startFaking() {
        val lm = getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        try {
            providers.forEach { p ->
                runCatching { lm.removeTestProvider(p) }
                @Suppress("DEPRECATION")
                lm.addTestProvider(p, false, false, false, false, true, true, true,
                    android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
                lm.setTestProviderEnabled(p, true)
            }
        } catch (e: SecurityException) {
            error = "Choose KDX in Developer options → Select mock location app"
            isRunning = false; stopSelf(); return
        }
        error = null
        running = true; isRunning = true
        worker = Thread({
            val rnd = java.util.Random()
            while (running) {
                val (lat, lon) = target(this)
                providers.forEach { p ->
                    runCatching {
                        lm.setTestProviderLocation(p, Location(p).apply {
                            // a few metres of natural-looking drift
                            latitude = lat + rnd.nextGaussian() * 0.00002; longitude = lon + rnd.nextGaussian() * 0.00002
                            altitude = 35.0; accuracy = if (p == LocationManager.GPS_PROVIDER) 4f else 20f
                            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        })
                    }
                }
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            }
        }, "kdx-fake-location").apply { isDaemon = true; start() }
    }

    private fun stopFaking() {
        running = false; isRunning = false
        worker?.interrupt(); worker = null
        val lm = getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { runCatching { lm.removeTestProvider(it) } }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fake location", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val stop = PendingIntent.getService(this, 2, Intent(this, FakeLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Fake location is on").setContentText("Apps see ${placeName(this)}")
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build()).setOngoing(true).build()
    }

    companion object {
        private const val CHANNEL = "fakelocation"
        private const val NOTIFICATION_ID = 45
        private const val ACTION_STOP = "org.kdx.shell.FAKELOC_STOP"
        @Volatile var isRunning = false; private set
        @Volatile var error: String? = null; private set

        private fun prefs(ctx: Context) = PrivacyVpnService.prefs(ctx)

        /** The fake place: a city (by country code) or custom coordinates. */
        fun target(ctx: Context): Pair<Double, Double> {
            val p = prefs(ctx)
            if (p.getString("fakeCity", "us") == "custom")
                return p.getFloat("fakeLat", 40.7128f).toDouble() to p.getFloat("fakeLon", -74.0060f).toDouble()
            val c = city(ctx); return c.lat to c.lon
        }

        fun city(ctx: Context): PrivacyCountry {
            val code = prefs(ctx).getString("fakeCity", "us") ?: "us"
            return PrivacyVpnService.COUNTRIES.firstOrNull { it.code == code && it.code.isNotEmpty() } ?: PrivacyVpnService.COUNTRIES[1]
        }

        fun placeName(ctx: Context): String =
            if (prefs(ctx).getString("fakeCity", "us") == "custom") target(ctx).let { "%.4f, %.4f".format(it.first, it.second) }
            else city(ctx).let { "${it.city}, ${it.name}" }

        /** True when KDX is the selected mock location app. */
        fun allowed(ctx: Context): Boolean = runCatching {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        fun start(ctx: Context) { prefs(ctx).edit().putBoolean("fakeLocation", true).apply(); ctx.startForegroundService(Intent(ctx, FakeLocationService::class.java)) }
        fun stop(ctx: Context) {
            prefs(ctx).edit().putBoolean("fakeLocation", false).apply()
            runCatching { ctx.startService(Intent(ctx, FakeLocationService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
