package org.kdx.shell.droid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * KDX Ad Blocker: a local, DNS-only VPN (like Blokada/DNS66 – no root, no remote server).
 *
 * Android is told to use [DNS4]/[DNS6] as the DNS server and to route *only* those addresses into the VPN, so all
 * other traffic goes directly to the internet, untouched. Every DNS lookup from every app arrives here: names on the
 * blocklist get "no such domain", the rest are forwarded to the upstream DNS server over a protected socket (outside
 * the VPN) and the answer is passed back.
 */
class AdBlockService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private val pool = Executors.newFixedThreadPool(8)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopBlocking(); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification())
        if (!running) startBlocking()
        return START_STICKY
    }

    override fun onRevoke() { stopBlocking(); stopSelf() } // user turned the VPN off in Android settings

    override fun onDestroy() { stopBlocking(); pool.shutdownNow(); super.onDestroy() }

    private fun startBlocking() {
        val list = loadList(this)
        current = list
        val fd = Builder()
            .setSession("KDX Ad Blocker")
            .addAddress("10.111.222.1", 32)
            .addDnsServer(DNS4).addRoute(DNS4, 32)
            .addAddress("fd00:6b64:78::1", 128)
            .addDnsServer(DNS6).addRoute(DNS6, 128)
            .setBlocking(true)
            .apply { runCatching { setMetered(false) } }
            .establish() ?: return
        tun = fd
        running = true
        isRunning = true
        worker = Thread({ loop(fd) }, "kdx-adblock").apply { isDaemon = true; start() }
    }

    private fun stopBlocking() {
        running = false
        isRunning = false
        runCatching { tun?.close() }
        tun = null
        worker?.interrupt(); worker = null
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        val upstream = InetAddress.getByName(upstreamDns(this))
        // asks the real DNS server over a protected socket, which bypasses this VPN
        val forward: (ByteArray) -> ByteArray? = { dns ->
            runCatching {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 5000
                    s.send(DatagramPacket(dns, dns.size, upstream, 53))
                    val r = DatagramPacket(ByteArray(4096), 4096)
                    s.receive(r)
                    r.data.copyOf(r.length)
                }
            }.getOrNull()
        }
        val engine = DnsEngine(current ?: loadList(this), forward) { name -> blocked.incrementAndGet(); remember(name) }
        while (running) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n < 0) break
            if (DnsPackets.parse(buf, n) == null) continue
            queries.incrementAndGet()
            current?.let { engine.list = it } // picks up list updates / allow-list changes
            val packet = buf.copyOf(n)
            if (engine.isBlockedQuery(packet, n)) { engine.handle(packet)?.let { write(output, it) }; continue }
            pool.execute { engine.handle(packet)?.let { write(output, it) } } // forwarding waits on the network
        }
    }

    private fun write(out: FileOutputStream, packet: ByteArray) {
        synchronized(out) { runCatching { out.write(packet) } }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ad blocker", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while KDX blocks ads"; setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Ad blocker is on")
            .setContentText("Blocking ads and trackers in all apps")
            .setContentIntent(open).setOngoing(true).build()
    }

    companion object {
        /** The DNS addresses apps are given; they only exist inside this VPN. */
        const val DNS4 = "10.111.222.2"
        const val DNS6 = "fd00:6b64:78::2"
        private const val CHANNEL = "adblock"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "org.kdx.shell.ADBLOCK_STOP"

        @Volatile var isRunning = false; private set
        @Volatile var current: Blocklist? = null
        val queries = AtomicLong()
        val blocked = AtomicLong()
        private val recent = ArrayDeque<String>()

        /** The most recently blocked names, newest first. */
        fun recentlyBlocked(): List<String> = synchronized(recent) { recent.toList().reversed() }

        private fun remember(name: String) = synchronized(recent) {
            recent.remove(name); recent.addLast(name); while (recent.size > 100) recent.removeFirst()
        }

        fun prefs(ctx: Context) = ctx.getSharedPreferences("adblock", Context.MODE_PRIVATE)
        fun upstreamDns(ctx: Context): String = prefs(ctx).getString("upstream", "1.1.1.1") ?: "1.1.1.1"
        fun allowList(ctx: Context): Set<String> = prefs(ctx).getStringSet("allow", emptySet()) ?: emptySet()

        /** The updated list if one was downloaded, else the one bundled with KDX; plus the user's allow list. */
        fun loadList(ctx: Context): Blocklist {
            val updated = AdBlockFiles.listFile(ctx.filesDir)
            val domains = if (updated.isFile) updated.inputStream().use { Blocklist.parse(it) }
            // bundled list: gzip data under a name the Android build tools don't treat specially (".gz" assets get renamed)
            else runCatching { ctx.assets.open("adblock/blocklist.bin").use { Blocklist.parseGzip(it) } }.getOrDefault(emptyList())
            return Blocklist(domains, allowList(ctx), if (updated.isFile) "updated ${java.util.Date(updated.lastModified())}" else "bundled with KDX")
        }

        /** Reloads the list into a running blocker (after an update or allow-list change). */
        fun reload(ctx: Context) { if (isRunning) current = loadList(ctx) }

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, AdBlockService::class.java))

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, AdBlockService::class.java).setAction(ACTION_STOP)) }
        }

        /** Downloads the latest StevenBlack list. Returns the number of domains. */
        fun update(ctx: Context): Int {
            val c = java.net.URL(AdBlockFiles.UPDATE_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            val text = c.inputStream.use { it.readBytes() }
            val domains = Blocklist.parse(text.inputStream())
            if (domains.size < 10_000) throw java.io.IOException("Downloaded list looks wrong (${domains.size} domains)")
            val f = AdBlockFiles.listFile(ctx.filesDir).apply { parentFile?.mkdirs() }
            File(f.path + ".part").apply { writeText(domains.joinToString("\n")) }.renameTo(f)
            reload(ctx)
            return domains.size
        }
    }
}
