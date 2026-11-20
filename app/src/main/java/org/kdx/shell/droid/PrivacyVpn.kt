package org.kdx.shell.droid

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** A country the traffic can leave from, with a city used for the fake GPS location. */
data class PrivacyCountry(val code: String, val name: String, val city: String, val lat: Double, val lon: Double)

/**
 * KDX Privacy VPN: every app's traffic goes through Tor (free, no account; or the user's own SOCKS5 proxy), so websites
 * and apps see a Tor exit's address instead of the phone's IP and location, and the network you're on (Wi-Fi owner,
 * mobile carrier) only sees encrypted Tor traffic. DNS goes through Tor too, and the ad blocker keeps working.
 *
 * Android only allows one VPN at a time, so while this runs it also does the ad blocker's job.
 * Tor itself runs in KDX's ":tor" process; KDX's own process is left out of the VPN (Tor must reach the internet
 * directly), and DroidSystem's Linux apps – which run in KDX's process – are pointed at Tor's proxy instead.
 */
class PrivacyVpnService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var stack: TunStack? = null
    private var worker: Thread? = null
    private var torConn: ServiceConnection? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification("Starting…"))
        if (!isRunning) Thread({ runCatching { startUp() }.onFailure { status = "Couldn't start: ${it.message}"; shutdown(); stopSelf() } }, "kdx-privacy-start").start()
        return START_STICKY
    }

    override fun onRevoke() { shutdown(); stopSelf() }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    private fun startUp() {
        val ctx = this
        // only one VPN can run: this one takes over ad blocking
        if (AdBlockService.isRunning) { AdBlockService.stop(ctx); Thread.sleep(400) }
        val p = prefs(ctx)
        val useTor = p.getString("mode", "tor") != "socks"
        val proxy: InetSocketAddress
        var user: String? = null; var pass: String? = null
        if (useTor) {
            status = "Starting Tor…"
            writeTorrc(ctx, p.getString("country", "") ?: "")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(n: ComponentName?, b: IBinder?) {}
                override fun onServiceDisconnected(n: ComponentName?) { if (isRunning) status = "Tor stopped unexpectedly" }
            }
            torConn = conn
            bindService(Intent(ctx, org.torproject.jni.TorService::class.java), conn, Context.BIND_AUTO_CREATE)
            proxy = InetSocketAddress("127.0.0.1", TOR_SOCKS)
        } else {
            val host = p.getString("socksHost", "")?.trim().orEmpty()
            if (host.isEmpty()) throw IOException("Enter your proxy's address first")
            proxy = InetSocketAddress(host, p.getInt("socksPort", 1080))
            if (proxy.isUnresolved) throw IOException("Can't find $host")
            user = p.getString("socksUser", "")?.ifEmpty { null }; pass = p.getString("socksPass", "")
        }

        // DNS: the ad blocker's list (if it's on), everything else resolved through Tor / the proxy
        val forward: (ByteArray) -> ByteArray? = if (useTor) { q -> udpQuery(q, InetSocketAddress("127.0.0.1", TOR_DNS)) }
            else { q -> Socks5.dnsOverTcp(proxy, "1.1.1.1", q, user, pass) }
        // the ad blocker's settings apply live (turning it on/off doesn't restart Tor)
        val adPrefs = AdBlockService.prefs(ctx)
        val list = AdBlockService.current ?: AdBlockService.loadList(ctx).also { AdBlockService.current = it }
        lateinit var engine: DnsEngine
        engine = DnsEngine(list, forward, { name -> AdBlockService.noteBlocked(name) }) { name, onList ->
            AdBlockService.queries.incrementAndGet()
            AdBlockService.current?.let { if (it !== engine.list) engine.list = it }
            adPrefs.getBoolean("enabled", false) && AdBlockService.decide(ctx, name, onList)
        }

        val fd = Builder()
            .setSession("KDX Privacy VPN")
            .setMtu(1500)
            .addAddress(ADDR4, 24).addRoute("0.0.0.0", 0)
            .addAddress(ADDR6, 64).addRoute("::", 0)      // IPv6 is captured and dropped, so it can't leak
            .addDnsServer(AdBlockService.DNS4)
            .addDisallowedApplication(packageName)        // Tor itself (and Linux apps, which use Tor's proxy)
            .apply { skipApps(ctx).forEach { runCatching { addDisallowedApplication(it) } } }
            .setBlocking(true)
            .apply { runCatching { setMetered(false) } }
            .establish() ?: throw IOException("Android didn't allow the VPN (permission missing?)")
        tun = fd
        val out = FileOutputStream(fd.fileDescriptor)
        val s = TunStack({ out.write(it) }, { ip, port -> Socks5.connect(proxy, ip.hostAddress!!, port, user, pass) },
            { pkt -> engine.handle(pkt) })
        stack = s; currentStack = s
        isRunning = true
        startedAt = System.currentTimeMillis()
        LinuxPrivacy.enable(ctx, if (useTor) "127.0.0.1:$TOR_SOCKS" else "${proxy.hostString}:${proxy.port}", user, pass)
        worker = Thread({
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32767)
            while (isRunning) {
                val n = runCatching { input.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                if (n > 0) s.onPacket(buf, n)
            }
        }, "kdx-privacy").apply { isDaemon = true; start() }

        if (useTor) {
            // wait for Tor to connect; apps' connections simply wait meanwhile (nothing goes around Tor)
            var last = -1
            while (isRunning) {
                val pct = runCatching { TorControl.bootstrap(ctx) }.getOrNull()
                if (pct != null && pct != last) { last = pct; status = if (pct >= 100) "Protected" else "Connecting to Tor… $pct%"; updateNotification() }
                if (pct != null && pct >= 100) break
                Thread.sleep(700)
            }
        } else { status = "Protected"; updateNotification() }
    }

    private fun shutdown() {
        val was = isRunning || tun != null
        isRunning = false
        status = "Off"
        stack?.close(); stack = null; currentStack = null
        runCatching { tun?.close() }; tun = null
        worker?.interrupt(); worker = null
        torConn?.let { runCatching { unbindService(it) } }; torConn = null
        killTorProcess(this)
        LinuxPrivacy.disable()
        if (was && AdBlockService.prefs(this).getBoolean("enabled", false) && VpnService.prepare(this) == null) {
            // give the ad blocker its VPN back
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { AdBlockService.start(this) } }, 600)
        }
    }

    private fun udpQuery(q: ByteArray, to: InetSocketAddress): ByteArray? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = 10_000
            s.send(DatagramPacket(q, q.size, to))
            val r = DatagramPacket(ByteArray(4096), 4096); s.receive(r); r.data.copyOf(r.length)
        }
    }.getOrNull()

    private fun updateNotification() =
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status)) }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Privacy VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while KDX hides your IP address"; setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PrivacyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Privacy VPN")
            .setContentText(text)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setContentIntent(open).setOngoing(true).build()
    }

    companion object {
        const val TOR_SOCKS = 19050
        const val TOR_DNS = 19053
        const val ADDR4 = "10.111.223.1"
        const val ADDR6 = "fd00:6b64:79::1"
        private const val CHANNEL = "privacy"
        private const val NOTIFICATION_ID = 44
        private const val ACTION_STOP = "org.kdx.shell.PRIVACY_STOP"

        @Volatile var isRunning = false; private set
        @Volatile var status = "Off"; private set
        @Volatile var startedAt = 0L; private set
        /** The packet stack while running (for live traffic numbers). */
        @Volatile var currentStack: TunStack? = null; private set

        val COUNTRIES = listOf(
            PrivacyCountry("", "Automatic (fastest)", "New York", 40.7128, -74.0060),
            PrivacyCountry("us", "United States", "New York", 40.7128, -74.0060),
            PrivacyCountry("ca", "Canada", "Toronto", 43.6532, -79.3832),
            PrivacyCountry("gb", "United Kingdom", "London", 51.5074, -0.1278),
            PrivacyCountry("de", "Germany", "Berlin", 52.5200, 13.4050),
            PrivacyCountry("nl", "Netherlands", "Amsterdam", 52.3676, 4.9041),
            PrivacyCountry("fr", "France", "Paris", 48.8566, 2.3522),
            PrivacyCountry("ch", "Switzerland", "Zurich", 47.3769, 8.5417),
            PrivacyCountry("at", "Austria", "Vienna", 48.2082, 16.3738),
            PrivacyCountry("se", "Sweden", "Stockholm", 59.3293, 18.0686),
            PrivacyCountry("no", "Norway", "Oslo", 59.9139, 10.7522),
            PrivacyCountry("fi", "Finland", "Helsinki", 60.1699, 24.9384),
            PrivacyCountry("ro", "Romania", "Bucharest", 44.4268, 26.1025),
            PrivacyCountry("pl", "Poland", "Warsaw", 52.2297, 21.0122),
            PrivacyCountry("jp", "Japan", "Tokyo", 35.6762, 139.6503),
            PrivacyCountry("sg", "Singapore", "Singapore", 1.3521, 103.8198),
            PrivacyCountry("au", "Australia", "Sydney", -33.8688, 151.2093),
        )

        fun prefs(ctx: Context) = ctx.getSharedPreferences("privacy", Context.MODE_PRIVATE)
        fun skipApps(ctx: Context): Set<String> = prefs(ctx).getStringSet("skip", emptySet()) ?: emptySet()

        fun start(ctx: Context) {
            prefs(ctx).edit().putBoolean("enabled", true).apply()
            ctx.startForegroundService(Intent(ctx, PrivacyVpnService::class.java))
        }

        fun stop(ctx: Context) {
            prefs(ctx).edit().putBoolean("enabled", false).apply()
            runCatching { ctx.startService(Intent(ctx, PrivacyVpnService::class.java).setAction(ACTION_STOP)) }
        }

        /** Restarts a running VPN so new settings apply. */
        fun restart(ctx: Context) {
            if (!isRunning) return
            runCatching { ctx.startService(Intent(ctx, PrivacyVpnService::class.java).setAction(ACTION_STOP)) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start(ctx) }, 1500)
        }

        fun torDir(ctx: Context): File = ctx.getDir("TorService", Context.MODE_PRIVATE)

        fun writeTorrc(ctx: Context, country: String) {
            val rc = StringBuilder()
                .append("SocksPort 127.0.0.1:$TOR_SOCKS\n")
                .append("DNSPort 127.0.0.1:$TOR_DNS\n")
                .append("ClientOnly 1\nAvoidDiskWrites 1\n")
            if (country.isNotEmpty()) rc.append("ExitNodes {$country}\nStrictNodes 1\n")
            File(torDir(ctx), "torrc").writeText(rc.toString())
        }

        private fun killTorProcess(ctx: Context) {
            runCatching {
                ctx.getSystemService(ActivityManager::class.java).runningAppProcesses
                    ?.filter { it.processName == ctx.packageName + ":tor" }
                    ?.forEach { android.os.Process.killProcess(it.pid) }
            }
        }

        /** Asks Tor for a new circuit: websites then see a different address. */
        fun newIdentity(ctx: Context) = TorControl.command(ctx, "SIGNAL NEWNYM")

        /**
         * Checks the address websites see, by asking check.torproject.org through the same proxy apps use.
         * Returns (ip, isTor).
         */
        fun checkIp(ctx: Context): Pair<String, Boolean> {
            val p = prefs(ctx)
            val tor = p.getString("mode", "tor") != "socks"
            val proxy = if (tor) InetSocketAddress("127.0.0.1", TOR_SOCKS) else InetSocketAddress(p.getString("socksHost", ""), p.getInt("socksPort", 1080))
            val host = "check.torproject.org"
            val raw = Socks5.connect(proxy, host, 443, p.getString("socksUser", "")?.ifEmpty { null }.takeIf { !tor }, p.getString("socksPass", ""), timeoutMs = 30_000)
            val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(raw, host, 443, true) as javax.net.ssl.SSLSocket
            ssl.use { s ->
                s.soTimeout = 30_000
                s.startHandshake()
                s.outputStream.write("GET /api/ip HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: KDX\r\n\r\n".toByteArray())
                val text = s.inputStream.readBytes().toString(Charsets.UTF_8)
                val json = org.json.JSONObject(text.substring(text.indexOf('{')))
                return json.optString("IP") to json.optBoolean("IsTor")
            }
        }
    }
}

/** Talks to Tor through its control socket (private to KDX: TorService creates it in KDX's app storage). */
object TorControl {
    private fun socketFile(ctx: Context) = File(File(PrivacyVpnService.torDir(ctx), "data"), "ControlSocket")

    /** Sends [cmd] and returns the reply lines (without the status codes). Throws on an error reply. */
    fun command(ctx: Context, cmd: String): List<String> {
        val s = LocalSocket()
        s.connect(LocalSocketAddress(socketFile(ctx).path, LocalSocketAddress.Namespace.FILESYSTEM))
        s.soTimeout = 5000
        return s.use { talk(it.inputStream.bufferedReader(), it.outputStream, listOf("AUTHENTICATE", cmd)).last() }
    }

    /** Tor's start-up progress, 0..100. */
    fun bootstrap(ctx: Context): Int? = command(ctx, "GETINFO status/bootstrap-phase").firstOrNull()?.let { parseProgress(it) }

    fun parseProgress(line: String) = Regex("PROGRESS=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** Runs [cmds] over a control connection; returns each command's reply lines. */
    fun talk(r: BufferedReader, w: OutputStream, cmds: List<String>): List<List<String>> = cmds.map { cmd ->
        w.write("$cmd\r\n".toByteArray()); w.flush()
        val lines = mutableListOf<String>()
        while (true) {
            val l = r.readLine() ?: throw IOException("Tor closed the control connection")
            if (l.length < 4) continue
            val code = l.substring(0, 3); val sep = l[3]; val rest = l.substring(4)
            if (!code.startsWith("2")) throw IOException("Tor: $rest")
            when (sep) {
                '+' -> { // data block until "."
                    val sb = StringBuilder(rest)
                    while (true) { val d = r.readLine() ?: break; if (d == ".") break; sb.append('\n').append(d) }
                    lines += sb.toString()
                }
                '-' -> lines += rest
                ' ' -> { if (rest != "OK") lines += rest; break }
            }
        }
        lines.map { it.substringAfter('=', it) }
    }
}

/**
 * DroidSystem's Linux apps run inside KDX's own process, which the VPN leaves out, so they're sent through the proxy
 * instead: proxy variables for command-line tools, apt, GTK and Chromium-based apps, and a Firefox policy. Their
 * DNS server is set to nothing, so programs that ignore proxies can't look names up behind the VPN's back.
 */
object LinuxPrivacy {
    @Volatile var env: Map<String, String> = emptyMap(); private set
    @Volatile var policies: File? = null; private set
    @Volatile var proxyAddr: String? = null; private set
    val active get() = proxyAddr != null

    fun enable(ctx: Context, hostPort: String, user: String?, pass: String?) {
        val login = if (user.isNullOrEmpty()) "" else "${enc(user)}:${enc(pass ?: "")}@"
        val url = "socks5h://$login$hostPort"
        env = mapOf(
            "all_proxy" to url, "ALL_PROXY" to url, "http_proxy" to url, "HTTP_PROXY" to url,
            "https_proxy" to url, "HTTPS_PROXY" to url, "ftp_proxy" to url,
            "no_proxy" to "localhost,127.0.0.1,::1", "NO_PROXY" to "localhost,127.0.0.1,::1",
        )
        policies = File(ctx.filesDir, "privacy/policies.json").apply { parentFile?.mkdirs(); writeText(firefoxPolicies(hostPort)) }
        proxyAddr = hostPort
    }

    fun disable() { env = emptyMap(); proxyAddr = null }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun firefoxPolicies(hostPort: String) = """
        {"policies": {
          "Proxy": {"Mode": "manual", "SOCKSProxy": "$hostPort", "SOCKSVersion": 5, "UseProxyForDNS": true, "Locked": true},
          "DNSOverHTTPS": {"Enabled": false, "Locked": true},
          "Permissions": {"Location": {"BlockNewRequests": true, "Locked": true}},
          "DisableTelemetry": true
        }}
    """.trimIndent()

    /** Firefox install directories (as seen inside the Linux system) whose distribution/policies.json Firefox reads. */
    val FIREFOX_DIRS = listOf("/app/lib/firefox", "/usr/lib/firefox-esr", "/usr/lib/firefox")
}
