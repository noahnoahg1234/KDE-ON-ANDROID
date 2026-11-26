package org.kdx.shell.core

import android.app.Application
import org.kdx.shell.droid.deleteTree
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kdx.shell.droid.DroidSystem
import java.io.Closeable
import java.io.File

/** KDX's built-in applications – Android re-implementations of their KDE counterparts. */
enum class KApp(
    val title: String,
    val generic: String,
    val icon: ImageVector,
    val category: String,
    val keywords: String,
) {
    DOLPHIN("Dolphin", "File Manager", Icons.Filled.Folder, "System", "files browser explorer folder"),
    KONSOLE("Konsole", "Terminal", Icons.Filled.Terminal, "System", "shell terminal command sh bash"),
    KATE("Kate", "Text Editor", Icons.Filled.EditNote, "Utilities", "text editor notes code write"),
    KCALC("KCalc", "Calculator", Icons.Filled.Calculate, "Utilities", "calculator math"),
    GWENVIEW("Gwenview", "Image Viewer", Icons.Filled.Image, "Graphics", "images photos pictures gallery"),
    KLIPPER("Klipper", "Clipboard Manager", Icons.Filled.ContentPaste, "Utilities", "clipboard copy paste history"),
    KNOTES("KNotes", "Sticky Notes", Icons.Filled.StickyNote2, "Utilities", "notes sticky memo todo"),
    SYSMON("System Monitor", "System Monitor", Icons.Filled.Speed, "System", "ram memory cpu battery storage ksysguard"),
    INFOCENTER("Info Center", "System Information", Icons.Filled.Info, "System", "about device hardware kinfocenter"),
    SETTINGS("System Settings", "Configure KDX", Icons.Filled.Settings, "Settings", "preferences theme wallpaper appearance desktops"),
    /** DroidSystem's app store; listed through its .desktop entry, so only visible once DroidSystem is installed. */
    BAZAAR("Bazaar", "App Store", Icons.Filled.Storefront, "System", "store flatpak packages install apps software"),
    /** System-wide ad blocker (local DNS-only VPN). */
    ADBLOCK("Ad Blocker", "Block ads in all apps", Icons.Filled.Block, "System", "ads adblock blocker tracker privacy dns vpn"),
    /** The real KDE Plasma 6 desktop from Debian, running in DroidSystem. */
    PLASMA("KDE Plasma", "Real KDE Plasma desktop", Icons.Filled.Dashboard, "System", "kde plasma desktop real linux kwin dolphin konsole kate"),
    /** WindSystem: Windows programs with Wine (box64 on ARM), with a taskbar, start menu, search and clock. */
    WINDSYSTEM("WindSystem", "Windows programs", Icons.Filled.Air, "System", "windows wine exe msi windsystem win10 programs games"),
    /** Privacy VPN: all traffic through Tor (or the user's proxy), fake GPS location. */
    PRIVACY("Privacy VPN", "Hide your IP and location", Icons.Filled.VpnLock, "System", "vpn tor privacy ip location proxy socks anonymous hide gps"),
    /** Shows the X display that DroidSystem's Linux apps (apt and Flatpak) draw on. */
    LINUXDISPLAY("Linux Display", "Linux Desktop Apps", Icons.Filled.DesktopWindows, "System", "x11 vnc linux gui display flatpak"),
    ;

    /** Apps that belong to DroidSystem rather than KDX itself. */
    val droidOnly get() = this == BAZAAR || this == LINUXDISPLAY || this == PLASMA || this == WINDSYSTEM

    val key get() = "kdx:$name"
}

/** One open window managed by the KDX window manager (our tiny KWin). */
class KWindow(val id: Int, val app: KApp, arg: String?, desktop: Int, maximized: Boolean) {
    var arg by mutableStateOf(arg)
    var desktop by mutableIntStateOf(desktop)
    var title by mutableStateOf(app.title)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var w by mutableFloatStateOf(480f)
    var h by mutableFloatStateOf(400f)
    var minimized by mutableStateOf(false)
    var maximized by mutableStateOf(maximized)
    var keepAbove by mutableStateOf(false)
    /** Covers the whole screen, KDX's panel included (KDE Plasma brings its own panel). */
    var fullscreen by mutableStateOf(false)
    /** A window can show its own app's icon (e.g. a Linux app on the Linux Display) instead of the KDX app icon. */
    var iconFile by mutableStateOf<File?>(null)
    var placed = false

    /** Per-window state that must survive while the window is minimised or on another desktop. */
    val store = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> retain(key: String, init: () -> T): T = store.getOrPut(key, init) as T

    fun dispose() = store.values.forEach { (it as? Closeable)?.close() }
}

/** An installed Android application shown in Kickoff. */
data class AndroidApp(val label: String, val pkg: String, val cls: String, val icon: ImageBitmap?) {
    val key get() = "pkg:$pkg/$cls"
}

class KdxState(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("kdx", Context.MODE_PRIVATE)

    /** Background jobs: a bug in one shows a message instead of crashing KDX (see StartGuard.coroutines). */
    private val guard = StartGuard.coroutines

    // ---- settings (persisted) ----
    var themeMode by mutableStateOf(ThemeMode.entries[prefs.getInt("theme", 0).coerceIn(0, 2)])
    var accentIndex by mutableIntStateOf(prefs.getInt("accent", 1))
    var wallpaperIndex by mutableIntStateOf(prefs.getInt("wallpaper", 0))
    var customWallpaper by mutableStateOf(prefs.getBoolean("customWallpaper", false))
    var wallpaperVersion by mutableIntStateOf(0)
    var desktopCount by mutableIntStateOf(prefs.getInt("desktops", 4))
    var floatingByDefault by mutableStateOf(prefs.getBoolean("floating", false))
    /** Widgets on the desktop, in order (long-press the desktop → Add Widgets…; long-press a widget to remove it). */
    val desktopWidgets = mutableStateListOf<DesktopWidget>().apply {
        val saved = prefs.getString("widgets", null)
        addAll(if (saved != null) DesktopWidget.parse(saved)
            else DesktopWidget.fromOldSwitches(prefs.getBoolean("wClock", true), prefs.getBoolean("wSystem", true), prefs.getBoolean("wNotes", true)))
    }
    private var nextWidgetId = (desktopWidgets.maxOfOrNull { it.id } ?: 0) + 1
    /** The "Add Widgets" picker is open. */
    var widgetExplorerOpen by mutableStateOf(false)

    fun canAddWidget(t: WidgetType) = t.multiple || desktopWidgets.none { it.type == t }

    fun addWidget(t: WidgetType) {
        if (!canAddWidget(t)) return
        // the first note is the one shared with the Notes app; more notes get their own text
        val text = if (t == WidgetType.NOTE && desktopWidgets.any { it.type == WidgetType.NOTE && it.text == null }) "" else null
        desktopWidgets.add(DesktopWidget(nextWidgetId++, t, text)); save()
    }

    fun removeWidget(w: DesktopWidget) { desktopWidgets.remove(w); save() }

    /** Moves [w] [by] places (negative = earlier). */
    fun moveWidget(w: DesktopWidget, by: Int) {
        val i = desktopWidgets.indexOf(w); if (i < 0) return
        val j = (i + by).coerceIn(0, desktopWidgets.lastIndex)
        if (i != j) { desktopWidgets.removeAt(i); desktopWidgets.add(j, w); save() }
    }
    var showDesktopIcons by mutableStateOf(prefs.getBoolean("icons", true))
    var use24h by mutableStateOf(prefs.getBoolean("h24", true))
    /** Where the taskbar is (System Settings → Workspace). Older KDX: "panel at top" on/off. */
    var panelPosition by mutableStateOf(PanelPosition.entries.firstOrNull { it.name == prefs.getString("panelPos", null) }
        ?: if (prefs.getBoolean("panelTop", false)) PanelPosition.TOP else PanelPosition.BOTTOM)
    /** The panel (or the dock's top bar) is at the top: menus and popups open from there. */
    val panelOnTop get() = panelPosition != PanelPosition.BOTTOM
    var showSeconds by mutableStateOf(prefs.getBoolean("seconds", false))
    /** KDX desktop resolution (System Settings → Desktop): how much fits on the screen. */
    var desktopResolution by mutableStateOf(DesktopResolution.entries.getOrElse(prefs.getInt("deskRes", 0)) { DesktopResolution.PHONE })
    /** PC desktop: icons in columns like a computer, pinned apps on the taskbar, apps in floating windows. */
    var pcDesktop by mutableStateOf(prefs.getBoolean("pcDesktop", false))
    /** Open Android apps in floating windows (needs Android's freeform windows, see AndroidWindows). */
    var androidAppWindows by mutableStateOf(prefs.getBoolean("androidWindows", false))
    /** Apps pinned to the taskbar (PC desktop), as launchable keys. */
    val pinned = mutableStateListOf<String>().apply {
        addAll((prefs.getString("pinned", null) ?: "kdx:DOLPHIN|kdx:KONSOLE|kdx:SETTINGS").split('|').filter { it.isNotBlank() })
    }
    fun isPinned(key: String) = key in pinned
    fun togglePinned(key: String) { if (!pinned.remove(key)) pinned.add(key); save() }

    /** Turns the PC desktop on or off; floating KDX windows come with it (as on a computer). */
    fun changePcDesktop(on: Boolean) {
        pcDesktop = on
        floatingByDefault = on
        if (on && desktopResolution == DesktopResolution.PHONE) desktopResolution = DesktopResolution.HD_PLUS
        save()
    }

    /** Android apps opened in windows so far (for cascading new windows). */
    private var androidWindowCount = 0
    var notes by mutableStateOf(prefs.getString("notes", "Welcome to KDX!\n• Tap the K button for Kickoff\n• Search with KRunner\n• Long-press apps to add favourites")!!)
    val favorites = mutableStateListOf<String>().apply {
        addAll(
            prefs.getString("favs", null)?.split('|')?.filter { it.isNotBlank() }
                ?: listOf(KApp.DOLPHIN.key, KApp.KONSOLE.key, KApp.KATE.key, KApp.SETTINGS.key)
        )
    }

    // ---- DroidSystem (Linux system) ----
    var droidEnabled by mutableStateOf(prefs.getBoolean("droid", false))
    var droidRoot by mutableStateOf(prefs.getString("droidRoot", null))
    var flatpakEnabled by mutableStateOf(prefs.getBoolean("flatpak", true))
    var linuxFileTypes by mutableStateOf(prefs.getBoolean("linuxTypes", true))
    /** Bazaar shows the online Flathub catalog. */
    var flathubEnabled by mutableStateOf(prefs.getBoolean("flathub", true))
    /** Show the on-screen keyboard whenever a Linux app window is active. */
    var linuxKeyboard by mutableStateOf(prefs.getBoolean("linuxKeyboard", true))
    fun changeLinuxKeyboard(on: Boolean) { linuxKeyboard = on; prefs.edit().putBoolean("linuxKeyboard", on).apply() }
    /** Play Linux apps' sound through the phone. */
    var linuxSound by mutableStateOf(prefs.getBoolean("linuxSound", true))
    val flathub by lazy { org.kdx.shell.droid.Flathub(app) }
    var droidProgress by mutableStateOf<Pair<Float, String>?>(null)
    var linuxApps by mutableStateOf<List<DroidSystem.DesktopEntry>>(emptyList())
        private set

    /**
     * The installed DroidSystem, or null when the Linux system is disabled / not installed. Cached and observable, so
     * screens never touch the disk to find out; refreshed by [reloadDroid].
     */
    var droid by mutableStateOf<DroidSystem?>(null)
        private set

    /** Re-reads DroidSystem from disk, upgrades an older install in place, and refreshes Linux apps for Kickoff. */
    fun reloadDroid() {
        viewModelScope.launch(guard) {
            val movedTo = withContext(Dispatchers.IO) { runCatching { moveToAndroidFolder() }.getOrNull() }
            if (movedTo != null) toast("Moved DroidSystem to $movedTo")
            val (ds, apps, migrated) = withContext(Dispatchers.IO) {
                val d = droidRoot?.takeIf { droidEnabled }?.let { DroidSystem(ctx, File(it)) }?.takeIf { it.isInstalled }
                val m = d?.let { runCatching { it.migrate() }.getOrDefault(false) } ?: false
                Triple(d, d?.desktopEntries(flatpakEnabled) ?: emptyList(), m)
            }
            droid = ds
            val deb = ds?.let { debianFor(it) }?.takeIf { withContext(Dispatchers.IO) { it.isInstalled.also { ok -> if (ok) runCatching { it.ensureLayer() } } } }
            attachDebian(deb)
            linuxApps = apps + (deb?.let { withContext(Dispatchers.IO) { org.kdx.shell.droid.AndroidLinux.desktopEntries(it, flatpakEnabled) } } ?: emptyList())
            if (migrated) toast("DroidSystem upgraded to ${DroidSystem.SYSTEM_VERSION} – Bazaar is now installed")
        }
    }

    fun disableDroid() {
        droidEnabled = false; save(); reloadDroid()
    }

    // ---- DroidSystem 2: the Debian base, Linux display and Flatpak ----
    /** The Debian system under DroidSystem, once installed (null while disabled / not installed). */
    var debian by mutableStateOf<org.kdx.shell.droid.DebianSystem?>(null)
        private set
    var debianProgress by mutableStateOf<Pair<Float, String>?>(null)
        private set
    var debianError by mutableStateOf<String?>(null)
    val debianLog = mutableStateListOf<String>()
    var linuxDisplay: org.kdx.shell.droid.LinuxDisplay? = null
        private set
    var flatpakRunner: org.kdx.shell.droid.FlatpakRunner? = null
        private set
    var linuxAudio: org.kdx.shell.droid.LinuxAudio? = null
        private set
    private val androidAudio = org.kdx.shell.droid.AndroidAudio()
    private var soundHintShown = false

    /** Starts DroidSystem's sound server and plays it on the phone (no-op if sound is off or not installed). */
    fun ensureSound() {
        val audio = linuxAudio ?: return
        if (!linuxSound) { androidAudio.stop(); return }
        if (!audio.isSetUp) {
            if (!soundHintShown) { soundHintShown = true; viewModelScope.launch(guard) { toast("For sound in Linux apps, run sound-setup in Konsole once") } }
            return
        }
        runCatching { audio.start() }
        androidAudio.start()
    }

    fun setSound(on: Boolean) {
        linuxSound = on; save()
        if (on) viewModelScope.launch(Dispatchers.IO + guard) { ensureSound() } else { androidAudio.stop(); linuxAudio?.stop() }
    }
    /** Bumped when Debian packages / Flatpaks change, so Bazaar and Kickoff re-check. */
    var debianVersion by mutableIntStateOf(0)

    private fun debianFor(classic: DroidSystem?): org.kdx.shell.droid.DebianSystem =
        org.kdx.shell.droid.AndroidLinux.debian(ctx, classic?.home)

    private var orphansCleaned = false
    @Volatile private var orphanCleanup: kotlinx.coroutines.Job? = null

    /** Waits until leftovers of an earlier KDX session are cleaned up (call before starting Linux programs). */
    suspend fun awaitLinuxCleanup() { orphanCleanup?.join() }

    /** Points DroidSystem's Linux apps at the ad blocker's DNS while it runs (they don't use Android's resolver). */
    fun syncLinuxDns() {
        val d = debian ?: return
        val dns = when {
            // Linux apps use the privacy VPN's proxy (which looks names up itself); others get no DNS, so they can't leak
            org.kdx.shell.droid.LinuxPrivacy.active -> "127.0.0.1"
            org.kdx.shell.droid.AdBlockService.isRunning -> org.kdx.shell.droid.AdBlockService.DNS4
            else -> "1.1.1.1\nnameserver 8.8.8.8"
        }
        viewModelScope.launch(Dispatchers.IO + guard) {
            runCatching {
                val f = File(d.root, "etc/resolv.conf")
                java.nio.file.Files.deleteIfExists(f.toPath())
                f.writeText("nameserver $dns\n")
            }
        }
    }

    private fun attachDebian(d: org.kdx.shell.droid.DebianSystem?) {
        debian = d
        if (d != null) syncLinuxDns()
        startLauncher(d)
        if (d != null && !orphansCleaned) {
            orphansCleaned = true
            // Linux programs left running by an earlier KDX session (their windows are gone). On Android every
            // process with KDX's user ID is KDX's own, and all DroidSystem programs carry HOSTNAME=droidsystem.
            // Only processes older than this KDX process, and anything that starts Linux programs waits for this
            // first (it used to kill a display server started at the same moment: "cannot connect to X server").
            orphanCleanup = viewModelScope.launch(Dispatchers.IO + guard) { runCatching { org.kdx.shell.droid.Procs.killOrphans("HOSTNAME=droidsystem") } }
        }
        if (d != null) {
            if (linuxDisplay?.sys?.root != d.root) {
                linuxDisplay?.stop()
                linuxDisplay = org.kdx.shell.droid.LinuxDisplay(d)
                flatpakRunner = org.kdx.shell.droid.FlatpakRunner(d, linuxDisplay!!)
                linuxAudio?.stop(); linuxAudio = org.kdx.shell.droid.LinuxAudio(d)
            }
        } else {
            linuxDisplay?.stop(); linuxDisplay = null; flatpakRunner = null
            androidAudio.stop(); linuxAudio?.stop(); linuxAudio = null
        }
    }

    /** Enabling DroidSystem also builds the Debian base (tests switch this off to stay offline). */
    var autoInstallDebian = true

    /** Downloads Debian and builds DroidSystem 2 on top of it (debootstrap-style, see DebianSystem.bootstrap). */
    /** The distribution new DroidSystem installs are built from (System Settings → Linux System). */
    var linuxBase by mutableStateOf(org.kdx.shell.droid.Distro.entries.firstOrNull { it.name == prefs.getString("linuxBase", null) }
        ?: org.kdx.shell.droid.Distro.UBUNTU); private set

    fun chooseLinuxBase(d: org.kdx.shell.droid.Distro) { linuxBase = d; prefs.edit().putString("linuxBase", d.name).apply() }

    /**
     * Rebuilds the Linux base from [d]: everything open in it is ended, its files (apt packages, KDE Plasma,
     * WindSystem's drive C:, Flatpak apps) are deleted, and it's built again. The home folder on shared storage stays.
     */
    fun switchLinuxBase(d: org.kdx.shell.droid.Distro) {
        if (debianProgress != null) return
        chooseLinuxBase(d)
        val old = debian ?: debianFor(droid)
        windows.filter { it.app == KApp.LINUXDISPLAY || it.app == KApp.WINDSYSTEM }.toList().forEach { close(it) }
        attachDebian(null)
        debianProgress = 0f to "Removing the old Linux base"
        viewModelScope.launch(guard) {
            withContext(Dispatchers.IO) {
                runCatching { org.kdx.shell.droid.Procs.stopEnv("HOSTNAME=droidsystem") }
                old.root.deleteTree()
            }
            debianProgress = null
            installDebian()
        }
    }

    fun installDebian() {
        if (debianProgress != null) return
        val sys = debianFor(droid)
        val base = linuxBase
        debianError = null
        debianLog.clear()
        debianProgress = 0f to "Starting"
        viewModelScope.launch(guard) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    sys.bootstrap(
                        org.kdx.shell.droid.DebianRepo(mirror = base.mirror(sys.arch), suite = base.suite, arch = sys.arch), File(ctx.cacheDir, "debian"),
                        progress = { p, m -> viewModelScope.launch(guard) { debianProgress = p to m } },
                        log = { line -> viewModelScope.launch(guard) { debianLog += line; while (debianLog.size > 300) debianLog.removeAt(0) } },
                    )
                }
            }
            debianProgress = null
            r.onSuccess { attachDebian(sys); refreshLinuxApps(); toast("DroidSystem 2 (${base.fullName}) is ready") }
                .onFailure { debianError = it.message ?: it.toString() }
        }
    }

    fun uninstallDebian() {
        val d = debian ?: debianFor(droid)
        attachDebian(null)
        viewModelScope.launch(guard) {
            withContext(Dispatchers.IO) { d.root.deleteTree() }
            refreshLinuxApps(); toast("Linux base removed")
        }
    }

    // ---- Linux app windows: every Linux/Flatpak app gets its own X display and its own KDX window ----
    private val appDisplays = java.util.concurrent.ConcurrentHashMap<Int, org.kdx.shell.droid.LinuxDisplay>()

    fun appDisplay(n: Int): org.kdx.shell.droid.LinuxDisplay? = appDisplays[n]

    /** Stops an app window's display (called when its KDX window closes). */
    fun releaseDisplay(n: Int) {
        appDisplays.remove(n)?.let { d -> viewModelScope.launch(Dispatchers.IO + guard) { d.stop() } }
        org.kdx.shell.droid.LinuxAppsService.update(ctx, appDisplays.size)
    }

    /**
     * X screen size for a window [widthDp] x [heightDp]: desktop apps expect ~800px or more, so on phones one
     * X pixel is a bit smaller than a dp (the window scales the picture to fit).
     */
    fun xScale(widthDp: Float): Float = maxOf(1.25f, 820f / widthDp.coerceAtLeast(1f)) * linuxPerformance.scale

    /** How Linux app windows trade sharpness for speed. */
    var linuxPerformance by mutableStateOf(
        LinuxPerformance.entries.getOrElse(prefs.getInt("linuxPerf", 0)) { LinuxPerformance.AUTO })

    fun setPerformance(p: LinuxPerformance) { linuxPerformance = p; prefs.edit().putInt("linuxPerf", p.ordinal).apply() }

    private fun displaySetUp(deb: org.kdx.shell.droid.DebianSystem): Boolean {
        if (deb.hasCommand("usr/bin/Xvnc")) return true
        toast("Setting up the display server for Linux apps first…")
        open(KApp.KONSOLE, "display-setup")
        return false
    }

    /** Starts [start] on a new display and opens a KDX window titled [title] with [icon] for it. */
    private fun openAppWindow(title: String, icon: File?, start: (org.kdx.shell.droid.LinuxDisplay) -> Process) {
        val deb = debian ?: run { toast("Turn on the Linux system (DroidSystem) in System Settings first"); return }
        if (!displaySetUp(deb)) return
        val n = org.kdx.shell.droid.LinuxDisplay.freeDisplay(deb.root, appDisplays.keys.toSet())
            ?: run { toast("Too many Linux windows are open – close some first"); return }
        val disp = org.kdx.shell.droid.LinuxDisplay(deb, n, appMode = true)
        appDisplays[n] = disp
        org.kdx.shell.droid.LinuxAppsService.update(ctx, appDisplays.size)
        val dm = ctx.resources.displayMetrics
        val wDp = dm.widthPixels / dm.density
        val hDp = (dm.heightPixels / dm.density - 140f).coerceAtLeast(300f)
        val f = xScale(wDp)
        closeMenus()
        val w = KWindow(nextId++, KApp.LINUXDISPLAY, "app:$n", currentDesktop, maximized = !floatingByDefault)
        w.title = title; w.iconFile = icon
        windows.add(w); activeId = w.id
        viewModelScope.launch(guard) {
            runCatching {
                withContext(Dispatchers.IO) {
                    awaitLinuxCleanup()
                    ensureSound() // before the app starts, so it finds the sound server
                    disp.start((wDp * f).toInt(), (hDp * f).toInt())
                    disp.logFile = newLogFile(title)
                    disp.relaunch = { disp.logFile = newLogFile(title); start(disp) }
                    disp.appProcess = start(disp)
                }
            }.onFailure { e -> toast("Couldn't start $title: ${e.message}"); close(w) }
        }
    }

    /**
     * A log file for a Linux app run, in the DroidSystem folder (shared storage, so it can be opened in Dolphin/Kate
     * or copied to a PC) – it keeps the app's output even if the app or KDX goes down.
     */
    private fun newLogFile(app: String): File? = runCatching {
        val dir = droid?.let { File(it.home, "logs") } ?: debian?.let { File(it.root, "root/logs") } ?: return null
        dir.mkdirs()
        // keep the 30 newest logs
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(29)?.forEach { it.delete() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(dir, app.replace(Regex("[^A-Za-z0-9._-]+"), "_") + "-$stamp.log")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        f.writeText(
            "KDX ${org.kdx.shell.BuildConfig.VERSION_NAME} – $app – ${java.util.Date()}\n" +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n" +
                "Memory: ${mi.availMem / 1048576} MiB free of ${mi.totalMem / 1048576} MiB${if (mi.lowMemory) " (LOW)" else ""}\n" +
                "Kernel: ${System.getProperty("os.version")}\n----\n"
        )
        f
    }.getOrNull()

    /** Runs a Debian GUI command (from a .desktop file or Konsole's `gui`) in its own window. */
    fun launchLinuxGui(cmd: String, title: String? = null, icon: File? = null) =
        openAppWindow(title ?: cmd.substringBefore(' ').substringAfterLast('/'), icon) { d -> d.launch(cmd) }

    // ---- Real KDE Plasma as the desktop (System Settings → Real KDE Plasma) ----
    /** When on, KDX sets up everything real KDE Plasma needs, then starts it full screen (also every time KDX opens). */
    var realKde by mutableStateOf(prefs.getBoolean("realKde", false)); private set
    var plasmaResolution by mutableStateOf(PlasmaResolution.entries.getOrElse(prefs.getInt("plasmaRes", 0)) { PlasmaResolution.PHONE }); private set
    /** What the automatic setup is doing right now (null when idle or done). */
    var realKdeStep by mutableStateOf<String?>(null); private set
    private var realKdeJob: kotlinx.coroutines.Job? = null
    private var plasmaSetupOpened = false
    private var droidRequested = false

    fun changeRealKde(on: Boolean) {
        realKde = on; prefs.edit().putBoolean("realKde", on).apply()
        if (on) { plasmaSetupOpened = false; droidRequested = false; runRealKde() }
        else { realKdeJob?.cancel(); realKdeStep = null; windows.filter { it.fullscreen }.forEach { close(it) } }
    }

    /** Runs the KDE Plasma installer in Konsole again (e.g. after a failed download). */
    fun retryPlasmaSetup() { plasmaSetupOpened = false; if (realKde) runRealKde() else open(KApp.KONSOLE, "plasma-setup") }

    fun changePlasmaResolution(r: PlasmaResolution) {
        plasmaResolution = r; prefs.edit().putInt("plasmaRes", r.ordinal).apply()
        // the scale factor is chosen when the session starts, so restart a running one
        if (windows.any { it.fullscreen }) {
            windows.filter { it.fullscreen }.forEach { close(it) }
            viewModelScope.launch(guard) { delay(1500); launchPlasma() }
        }
    }

    /** One step at a time: Linux system → Debian → display server + Plasma → start. */
    private fun runRealKde() {
        if (realKdeJob?.isActive == true) return
        realKdeJob = viewModelScope.launch(guard) {
            while (realKde) {
                val deb = debian
                val dp = droidProgress; val bp = debianProgress
                when {
                    dp != null -> realKdeStep = "Installing the Linux system: ${dp.second}"
                    droid == null && droidEnabled && !droidRequested -> realKdeStep = "Starting the Linux system…" // still loading
                    droid == null -> {
                        if (droidRequested) { realKdeStep = "Couldn't turn on the Linux system. Try again below."; return@launch }
                        droidRequested = true; realKdeStep = "Turning on the Linux system…"; enableDroid()
                    }
                    bp != null -> realKdeStep = "Building Debian: ${bp.second}"
                    deb == null -> {
                        if (debianError != null) { realKdeStep = "Building Debian failed: tap Retry in Linux System below."; return@launch }
                        realKdeStep = "Building Debian…"; installDebian()
                    }
                    !org.kdx.shell.droid.KdePlasma.isInstalled(deb) || !deb.hasCommand("usr/bin/Xvnc") -> {
                        realKdeStep = "Installing KDE Plasma in Konsole (about 320 MB). Keep KDX open."
                        if (!plasmaSetupOpened) { plasmaSetupOpened = true; open(KApp.KONSOLE, "plasma-setup") }
                    }
                    else -> {
                        realKdeStep = null
                        // a problem starting Plasma must never crash KDX: as the home screen, Android would restart it,
                        // it would start Plasma again… (the screen blinking black)
                        if (windows.none { it.fullscreen }) runCatching { launchPlasma() }.onFailure {
                            StartGuard.log(ctx, "Starting KDE Plasma failed: $it")
                            realKdeStep = "Couldn't start KDE Plasma: ${it.message ?: it}. Try again below."
                        }
                        return@launch
                    }
                }
                delay(3000)
            }
        }
    }

    /**
     * X screen size for KDE Plasma in a [wPx] x [hPx] area. Phone's resolution = one X pixel per screen pixel.
     * Plasma's interface is then scaled ([plasmaScale]) so the short side is about 720 logical pixels: big enough
     * to use with fingers, small enough for Plasma's panels and menus to fit.
     */
    fun plasmaGeometry(wPx: Int, hPx: Int, res: PlasmaResolution = plasmaResolution): Pair<Int, Int> {
        val minDim = minOf(wPx, hPx).coerceAtLeast(1)
        val xMin = maxOf(PLASMA_MIN_SIDE, minDim * res.pixels).coerceAtMost(minDim.toFloat())
        val r = xMin / minDim
        return (wPx * r).toInt().coerceIn(320, 4096) to (hPx * r).toInt().coerceIn(240, 4096)
    }

    /** Plasma's interface scale for an X screen whose short side is [xMinDim] pixels (at most the phone's density). */
    fun plasmaScale(xMinDim: Int): Float =
        ((xMinDim / PLASMA_MIN_SIDE).coerceIn(1f, ctx.resources.displayMetrics.density.coerceAtLeast(1f)) * 20).toInt() / 20f

    /**
     * Starts the real KDE Plasma desktop (installed with plasma-setup) full screen on its own display. A second call
     * brings the running session back instead of starting another.
     */
    fun launchPlasma() {
        markUsed("plasma")
        val deb = debian ?: run { toast("Turn on the Linux system (DroidSystem 2) in System Settings first"); return }
        windows.firstOrNull { it.fullscreen && it.app == KApp.LINUXDISPLAY }?.let { it.minimized = false; it.desktop = currentDesktop; focus(it); return }
        if (!displaySetUp(deb)) return
        if (!org.kdx.shell.droid.KdePlasma.isInstalled(deb)) {
            toast("Installing KDE Plasma first (about 320 MB)…")
            open(KApp.KONSOLE, "plasma-setup"); return
        }
        val n = org.kdx.shell.droid.LinuxDisplay.freeDisplay(deb.root, appDisplays.keys.toSet())
            ?: run { toast("Too many Linux windows are open – close some first"); return }
        val disp = org.kdx.shell.droid.LinuxDisplay(deb, n, desktop = true)
        appDisplays[n] = disp
        org.kdx.shell.droid.LinuxAppsService.update(ctx, appDisplays.size)
        val dm = ctx.resources.displayMetrics
        val (xw, xh) = plasmaGeometry(dm.widthPixels, dm.heightPixels)
        val scale = plasmaScale(minOf(xw, xh))
        val scaleEnv = mapOf(
            "QT_SCREEN_SCALE_FACTORS" to "%.2f".format(java.util.Locale.US, scale),
            "GDK_SCALE" to scale.toInt().coerceAtLeast(1).toString(),
            "GDK_DPI_SCALE" to "%.2f".format(java.util.Locale.US, scale / scale.toInt().coerceAtLeast(1)),
            "XCURSOR_SIZE" to (24 * scale).toInt().toString(),
        )
        closeMenus()
        val w = KWindow(nextId++, KApp.LINUXDISPLAY, "app:$n", currentDesktop, maximized = true)
        w.title = "KDE Plasma"; w.fullscreen = true
        disp.extraEnv = scaleEnv // apps started on Plasma's screen (Flatpaks via KDX) get the same scale
        windows.add(w); activeId = w.id
        viewModelScope.launch(guard) {
            runCatching {
                withContext(Dispatchers.IO) {
                    awaitLinuxCleanup()
                    ensureSound()
                    if (org.kdx.shell.droid.StoragePacker.isPacked(deb, "plasma")) {
                        withContext(Dispatchers.Main) { toast("Unpacking KDE Plasma (it was packed to save space)…") }
                        deb.run("droidsystem-pack unpack plasma </dev/null 2>&1; true")
                    }
                    org.kdx.shell.droid.KdePlasma.writeDefaults(deb)
                    runCatching { org.kdx.shell.droid.LinuxLauncher.installWrapper(deb) } // Flatpak apps from Plasma's menu
                    disp.start(xw, xh)
                    // Plasma's first step is connecting to X: make sure the X server's socket is really there
                    if (!disp.waitForX()) throw java.io.IOException("the display server didn't come up:\n" + disp.log.toList().takeLast(6).joinToString("\n"))
                    disp.logFile = newLogFile("KDE Plasma")
                    val start = { disp.launch(org.kdx.shell.droid.KdePlasma.COMMAND, org.kdx.shell.droid.KdePlasma.env + scaleEnv) }
                    disp.relaunch = { disp.logFile = newLogFile("KDE Plasma"); start() }
                    disp.appProcess = start()
                }
            }.onFailure { e -> toast("Couldn't start KDE Plasma: ${e.message}"); close(w) }
        }
    }

    // ---- programs inside DroidSystem asking KDX to start Flatpak apps (see LinuxLauncher) ----
    private var launcher: org.kdx.shell.droid.LinuxLauncher? = null

    private fun startLauncher(d: org.kdx.shell.droid.DebianSystem?) {
        if (launcher?.let { d != null && it.sys == d } == true) return
        launcher?.stop(); launcher = null
        if (d == null) return
        val l = org.kdx.shell.droid.LinuxLauncher(d) { r -> runRequested(r) }
        launcher = l
        viewModelScope.launch(Dispatchers.IO + guard) {
            runCatching { l.start() }.onFailure { toast("Couldn't start the app launcher: ${it.message}") }
            runCatching { org.kdx.shell.droid.LinuxLauncher.installWrapper(d) }
        }
    }

    /** Called on a background thread for each `flatpak run` inside DroidSystem; returns an error message or null. */
    private fun runRequested(r: org.kdx.shell.droid.LinuxLauncher.Request): String? {
        markUsed("flatpak")
        val runner = flatpakRunner ?: return "the Linux system isn't ready"
        runner.appDir(r.id) ?: return "${r.id} is not installed"
        val disp = r.display?.let { appDisplays[it] }
        return if (disp != null && disp.desktop) {
            // from real KDE Plasma: open on Plasma's own screen, where KWin manages its window
            runner.launch(r.id, r.args, on = disp, commandOverride = r.command); null
        } else {
            // from anywhere else (a terminal, an app window): a KDX window of its own
            viewModelScope.launch(guard) { launchFlatpak(r.id) }; null
        }
    }

    // ---- WindSystem (Windows programs, see droid/WindSystem.kt) ----
    var windConfig by mutableStateOf(loadWindConfig()); private set
    @Volatile var windSession: org.kdx.shell.droid.WindSession? = null; private set
    var windWindows by mutableStateOf<List<Pair<String, String>>>(emptyList()); internal set
    var windActive by mutableStateOf<String?>(null); internal set
    var windStatus by mutableStateOf<String?>(null); private set
    /** Why WindSystem couldn't start (shown in its window with a self-check and the log), or null. */
    var windError by mutableStateOf<String?>(null)
    /** Crashes waiting to be shown (program crash + its saved report). */
    val windCrashes = androidx.compose.runtime.mutableStateListOf<Pair<org.kdx.shell.droid.WindSystem.Crash, File?>>()

    private fun loadWindConfig(): org.kdx.shell.droid.WindSystem.Config {
        val d = org.kdx.shell.droid.WindSystem.Config()
        val p = prefs
        return org.kdx.shell.droid.WindSystem.Config(
            windowsVersion = p.getString("wind.version", d.windowsVersion)!!, dpi = p.getInt("wind.dpi", 0),
            renderer = p.getString("wind.renderer", d.renderer)!!, sound = p.getBoolean("wind.sound", d.sound),
            phoneDriveE = p.getBoolean("wind.driveE", d.phoneDriveE), darkTaskbar = p.getBoolean("wind.dark", d.darkTaskbar),
            clock24h = p.getBoolean("wind.clock24", d.clock24h), clockSeconds = p.getBoolean("wind.clockSec", d.clockSeconds),
            showSearch = p.getBoolean("wind.search", d.showSearch), wallpaper = p.getLong("wind.wallpaper", d.wallpaper),
            performance = p.getString("wind.perf", d.performance)!!, crashDialog = p.getBoolean("wind.crashDialog", d.crashDialog),
            autoRestart = p.getBoolean("wind.autoRestart", d.autoRestart), debugLog = p.getString("wind.debug", d.debugLog)!!,
            dllOverrides = p.getString("wind.dlls", d.dllOverrides)!!, resolution = p.getString("wind.res", d.resolution)!!,
            modernTheme = p.getBoolean("wind.modern", d.modernTheme), touchFrames = p.getBoolean("wind.touchFrames", d.touchFrames),
        )
    }

    /** Saves WindSystem's settings; applies them to a running WindSystem (registry, drives, wallpaper). */
    fun changeWindConfig(c: org.kdx.shell.droid.WindSystem.Config) {
        windConfig = c
        prefs.edit().putString("wind.version", c.windowsVersion).putInt("wind.dpi", c.dpi).putString("wind.renderer", c.renderer)
            .putBoolean("wind.sound", c.sound).putBoolean("wind.driveE", c.phoneDriveE).putBoolean("wind.dark", c.darkTaskbar)
            .putBoolean("wind.clock24", c.clock24h).putBoolean("wind.clockSec", c.clockSeconds).putBoolean("wind.search", c.showSearch)
            .putLong("wind.wallpaper", c.wallpaper).putString("wind.perf", c.performance).putBoolean("wind.crashDialog", c.crashDialog)
            .putBoolean("wind.autoRestart", c.autoRestart).putString("wind.debug", c.debugLog).putString("wind.dlls", c.dllOverrides)
            .putString("wind.res", c.resolution).putBoolean("wind.modern", c.modernTheme).putBoolean("wind.touchFrames", c.touchFrames).apply()
        windSession?.let { s -> s.config = c; viewModelScope.launch(Dispatchers.IO + guard) { runCatching { s.applySettings() } } }
    }

    /** Android/WindSystem: the phone's Android folder when KDX has file access, else KDX's folder there. */
    fun windSharedDir(): File {
        val candidates = listOfNotNull(
            runCatching { File(Environment.getExternalStorageDirectory(), "Android/WindSystem") }.getOrNull()?.takeIf { hasStorageAccess() },
            ctx.getExternalFilesDir(null)?.let { File(it, "WindSystem") },
        )
        return candidates.firstOrNull { writable(it) } ?: File(ctx.filesDir, "WindSystem")
    }

    /** Opens WindSystem full screen (installing it first if needed); a second call brings it back. */
    fun launchWindSystem() {
        markUsed("windsystem-c")
        val deb = debian ?: run { toast("Turn on the Linux system (DroidSystem 2) in System Settings first"); return }
        windows.firstOrNull { it.app == KApp.WINDSYSTEM }?.let { it.minimized = false; it.desktop = currentDesktop; focus(it); return }
        if (!displaySetUp(deb)) return
        if (!org.kdx.shell.droid.WindSystem.isInstalled(deb)) {
            toast("Installing WindSystem first…")
            open(KApp.KONSOLE, "windsystem-setup"); return
        }
        val n = org.kdx.shell.droid.LinuxDisplay.freeDisplay(deb.root, appDisplays.keys.toSet())
            ?: run { toast("Too many Linux windows are open – close some first"); return }
        val disp = org.kdx.shell.droid.LinuxDisplay(deb, n, desktop = true)
        appDisplays[n] = disp
        org.kdx.shell.droid.LinuxAppsService.update(ctx, appDisplays.size)
        val dm = ctx.resources.displayMetrics
        val (xw, xh) = windGeometry(dm.widthPixels, dm.heightPixels)
        val autoDpi = ((96 * plasmaScale(minOf(xw, xh))) / 24).toInt().coerceAtLeast(4) * 24
        closeMenus()
        val w = KWindow(nextId++, KApp.WINDSYSTEM, "app:$n", currentDesktop, maximized = true)
        w.title = "WindSystem"; w.fullscreen = true
        windows.add(w); activeId = w.id
        windStatus = "Starting WindSystem…"; windError = null
        val shared = windSharedDir()
        viewModelScope.launch(guard) {
            runCatching {
                withContext(Dispatchers.IO) {
                    awaitLinuxCleanup()
                    ensureSound()
                    disp.start(xw, xh)
                    if (!disp.waitForX()) throw java.io.IOException("the display server didn't come up")
                    val s = org.kdx.shell.droid.WindSession(deb, disp, shared, windConfig, autoDpi,
                        onCrash = { c, f -> viewModelScope.launch(guard) { windCrashes += c to f } },
                        onWindows = { list, active -> viewModelScope.launch(guard) { windWindows = list; windActive = active } },
                        status = { st -> viewModelScope.launch(guard) { windStatus = st } })
                    windSession = s
                    s.start()
                }
            }.onFailure { e ->
                // keep the window open and show why, with a self-check and the start log (a toast vanishes too fast)
                windStatus = "Checking what went wrong…"
                val details = withContext(Dispatchers.IO) {
                    val log = runCatching { File(shared, "Logs/WindSystem.log").readLines().takeLast(30).joinToString("\n") }.getOrDefault("(no log)")
                    "${e.message}\n\n--- Self-check\n${org.kdx.shell.droid.WindSystem.diagnose(deb)}\n\n--- Start log\n$log"
                }
                windStatus = null
                windError = details
            }
        }
    }

    /** WindSystem's X screen size for a [wPx] x [hPx] area: a fixed resolution, or phone/balanced/fast like Plasma's. */
    fun windGeometry(wPx: Int, hPx: Int): Pair<Int, Int> =
        org.kdx.shell.droid.WindSystem.fixedResolution(windConfig.resolution)
            ?: plasmaGeometry(wPx, hPx, when (windConfig.resolution) { "fast" -> PlasmaResolution.FAST; "balanced" -> PlasmaResolution.BALANCED; else -> PlasmaResolution.PHONE })

    /** Ends WindSystem (all Windows programs) and closes its window. */
    fun endWindSystem() {
        val s = windSession; windSession = null
        windWindows = emptyList(); windStatus = null
        viewModelScope.launch(Dispatchers.IO + guard) { runCatching { s?.shutdown() } }
        windows.filter { it.app == KApp.WINDSYSTEM }.forEach { close(it) }
    }

    // ---- automatic space saving (System Settings → Storage packing) ----
    var autoSave by mutableStateOf(prefs.getBoolean("autoSave", true)); private set
    var autoSaveGb by mutableIntStateOf(prefs.getInt("autoSaveGb", 2)); private set
    var lastAutoSave by mutableStateOf(prefs.getString("lastAutoSave", null)); private set
    var autoSaving by mutableStateOf(false); private set

    fun changeAutoSave(on: Boolean, gb: Int = autoSaveGb) {
        autoSave = on; autoSaveGb = gb
        prefs.edit().putBoolean("autoSave", on).putInt("autoSaveGb", gb).apply()
        if (on) viewModelScope.launch(guard) { checkStorage() }
    }

    /** Remembers when a packable part was last opened (automatic saving packs ones you haven't used for a while). */
    private fun markUsed(id: String) = prefs.edit().putLong("lastUsed.$id", System.currentTimeMillis()).apply()

    /** Free space where DroidSystem lives (KDX's private storage). */
    fun freeBytes(): Long = runCatching { android.os.StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private fun packInUse(id: String) = when (id) {
        "plasma" -> windows.any { it.fullscreen && it.app == KApp.LINUXDISPLAY }
        "windsystem-c" -> windSession != null
        "flatpak" -> appDisplays.values.any { it.appId != null }
        else -> false
    }

    /** If space is low and automatic saving is on: clean up, then pack parts not used lately. */
    suspend fun checkStorage(force: Boolean = false) {
        val deb = debian ?: return
        val threshold = autoSaveGb * 1024L * 1024 * 1024
        if (autoSaving || (!force && (!autoSave || freeBytes() >= threshold))) return
        autoSaving = true
        val r = withContext(Dispatchers.IO) {
            runCatching {
                org.kdx.shell.droid.StoragePacker.autoSave(deb, ::freeBytes, threshold,
                    lastUsed = { id -> prefs.getLong("lastUsed.$id", 0L) }, inUse = { id -> packInUse(id) })
            }.getOrNull()
        }
        autoSaving = false
        if (r == null) return
        val names = mapOf("plasma" to "KDE Plasma", "windsystem-c" to "WindSystem's drive C:", "flatpak" to "Flatpak apps")
        val freed = "%.1f GB".format(r.freedBytes / 1073741824.0)
        val msg = "Freed $freed: cleaned up" + if (r.packed.isNotEmpty()) " and packed " + r.packed.joinToString(", ") { names[it] ?: it } + " (unpacks when you open it)" else ""
        val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date())
        lastAutoSave = "$stamp – $msg"
        prefs.edit().putString("lastAutoSave", lastAutoSave).apply()
        toast("Storage was low. $msg")
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("storage", "Storage", android.app.NotificationManager.IMPORTANCE_LOW))
            nm.notify(46, android.app.Notification.Builder(ctx, "storage").setSmallIcon(android.R.drawable.stat_notify_sdcard)
                .setContentTitle("KDX saved space").setContentText(msg).setStyle(android.app.Notification.BigTextStyle().bigText(msg)).build())
        }
    }

    /** Runs an installed Flatpak app in its own window, with its name and icon. */
    fun launchFlatpak(id: String) {
        markUsed("flatpak")
        val runner = flatpakRunner ?: run { toast("Turn on the Linux system first"); return }
        // already open? bring that window forward instead of starting a second copy
        windows.firstOrNull { w -> w.app == KApp.LINUXDISPLAY && w.arg?.removePrefix("app:")?.toIntOrNull()?.let { appDisplays[it]?.appId } == id }
            ?.let { focus(it); return }
        val app = runner.installed().firstOrNull { it.id == id } ?: run { toast("$id is not installed"); return }
        val icon = runner.appDir(id)?.let { org.kdx.shell.droid.AndroidLinux.findIcon(listOf(File(it, "export")), id) }
        openAppWindow(app.name, icon) { d -> runner.killLeftovers(id); runner.launch(id, on = d) }
    }

    /** Where a new DroidSystem goes: the phone's storage when KDX has file access, else KDX's private folder. */
    /**
     * Where DroidSystem's folder goes: inside the phone's Android folder.
     *  1. /storage/emulated/0/Android/DroidSystem            (needs file access; some Android versions refuse new folders here)
     *  2. /storage/emulated/0/Android/data/org.kdx.shell/files/DroidSystem   (always allowed; removed if KDX is uninstalled)
     *  3. KDX's private folder, if shared storage isn't available at all
     */
    fun droidTargetDir(): File {
        val candidates = listOfNotNull(
            runCatching { File(Environment.getExternalStorageDirectory(), "Android/DroidSystem") }.getOrNull()?.takeIf { hasStorageAccess() },
            ctx.getExternalFilesDir(null)?.let { File(it, "DroidSystem") },
        )
        return candidates.firstOrNull { writable(it) } ?: File(ctx.filesDir, "DroidSystem")
    }

    private fun writable(dir: File): Boolean = runCatching {
        dir.mkdirs()
        val probe = File(dir, ".kdx-write-test")
        probe.writeText("ok"); probe.delete()
        true
    }.getOrDefault(false)

    /** DroidSystem 1/2 used /storage/emulated/0/DroidSystem; move such a folder into the Android folder. */
    private fun moveToAndroidFolder(): String? {
        val current = droidRoot?.let { File(it) } ?: return null
        val legacy = runCatching { File(Environment.getExternalStorageDirectory(), "DroidSystem") }.getOrNull() ?: return null
        if (current.absolutePath != legacy.absolutePath || !current.isDirectory) return null
        val target = droidTargetDir()
        if (target.absolutePath == current.absolutePath || File(target, "etc/os-release").isFile) return null
        target.parentFile?.mkdirs()
        target.delete() // the empty dir left by the write probe
        val moved = current.renameTo(target) ||
            (runCatching { current.copyRecursively(target, overwrite = true) }.getOrDefault(false) && current.deleteTree())
        if (!moved) return null
        droidRoot = target.path
        save()
        return target.path
    }

    fun enableDroid() {
        if (droidProgress != null) return
        val existing = droidRoot?.let { DroidSystem(ctx, File(it)) }?.takeIf { it.isInstalled }
        if (existing != null) {
            droidEnabled = true; save(); reloadDroid()
            viewModelScope.launch(guard) { if (autoInstallDebian && !withContext(Dispatchers.IO) { debianFor(existing).isInstalled }) installDebian() }
            return
        }
        installDroid(droidTargetDir())
    }

    fun installDroid(dir: File) {
        val ds = DroidSystem(ctx, dir)
        droidProgress = 0f to "Preparing"
        viewModelScope.launch(guard) {
            val r = withContext(Dispatchers.IO) {
                runCatching { ds.install { p, msg -> viewModelScope.launch(guard) { droidProgress = p to msg } } }
            }
            droidProgress = null
            r.onSuccess {
                droidRoot = dir.path; droidEnabled = true; save()
                droid = ds; linuxApps = withContext(Dispatchers.IO) { ds.desktopEntries(flatpakEnabled) }
                // DroidSystem 2 is Debian-based: build the Debian base next
                if (!withContext(Dispatchers.IO) { debianFor(ds).isInstalled }) { if (autoInstallDebian) installDebian() } else reloadDroid()
                toast("DroidSystem installed in ${dir.path}")
            }.onFailure { toast("DroidSystem install failed: ${it.message}") }
        }
    }

    fun uninstallDroid() {
        val dir = droidRoot?.let { File(it) } ?: return
        viewModelScope.launch(guard) {
            withContext(Dispatchers.IO) { DroidSystem(ctx, dir).uninstall() }
            droidEnabled = false; droidRoot = null; droid = null; linuxApps = emptyList(); save()
            toast("DroidSystem removed")
        }
    }

    fun refreshLinuxApps() {
        viewModelScope.launch(guard) {
            linuxApps = withContext(Dispatchers.IO) {
                (droid?.desktopEntries(flatpakEnabled) ?: emptyList()) +
                    (debian?.let { org.kdx.shell.droid.AndroidLinux.desktopEntries(it, flatpakEnabled) } ?: emptyList())
            }
            debianVersion++
        }
    }

    fun save() = prefs.edit()
        .putBoolean("droid", droidEnabled)
        .putString("droidRoot", droidRoot)
        .putBoolean("flatpak", flatpakEnabled)
        .putBoolean("linuxTypes", linuxFileTypes)
        .putBoolean("flathub", flathubEnabled)
        .putBoolean("linuxSound", linuxSound)
        .putInt("theme", themeMode.ordinal)
        .putInt("accent", accentIndex)
        .putInt("wallpaper", wallpaperIndex)
        .putBoolean("customWallpaper", customWallpaper)
        .putInt("desktops", desktopCount)
        .putBoolean("floating", floatingByDefault)
        .putString("widgets", DesktopWidget.serialize(desktopWidgets))
        .putBoolean("icons", showDesktopIcons)
        .putBoolean("h24", use24h)
        .putString("panelPos", panelPosition.name)
        .putBoolean("seconds", showSeconds)
        .putInt("deskRes", desktopResolution.ordinal)
        .putBoolean("pcDesktop", pcDesktop)
        .putBoolean("androidWindows", androidAppWindows)
        .putString("pinned", pinned.joinToString("|"))
        .putString("notes", notes)
        .putString("favs", favorites.joinToString("|"))
        .apply()

    val wallpaperFile get() = File(ctx.filesDir, "wallpaper.img")

    // ---- shell state ----
    var currentDesktop by mutableIntStateOf(0)
    val windows = mutableStateListOf<KWindow>() // z-order: last is on top
    var activeId by mutableStateOf<Int?>(null)
    var kickoffOpen by mutableStateOf(false)
    var krunnerOpen by mutableStateOf(false)
    var overviewOpen by mutableStateOf(false)
    var calendarOpen by mutableStateOf(false)
    private var nextId = 1

    var androidApps by mutableStateOf<List<AndroidApp>>(emptyList())
        private set
    var appsLoading by mutableStateOf(true)

    /** Klipper history (most recent first). */
    val clipboardHistory = mutableStateListOf<String>()

    /** Dolphin's shared copy/cut buffer so files can move between Dolphin windows. */
    var fileClipboard by mutableStateOf<List<File>>(emptyList())
    var fileClipboardCut by mutableStateOf(false)

    // ---- restart-loop guard (see StartGuard) ----
    /** KDX kept restarting: nothing heavy starts by itself until the user resumes. */
    var safeMode by mutableStateOf(false); private set
    var safeModeDetails by mutableStateOf(""); private set

    private fun checkStartLoop() {
        // decided when the process started (KdxApp), before any of this existed
        if (StartGuard.mode != StartGuard.Mode.NORMAL) {
            safeMode = true
            safeModeDetails = "KDX started ${StartGuard.starts.size} times in a few minutes.\n\n" + StartGuard.report(ctx) +
                "\nKDE Plasma auto-start: ${if (realKde) "on" else "off"}"
        }
        // running fine for a while: not a loop
        viewModelScope.launch(guard) { delay(StartGuard.LOOP_WINDOW_MS); StartGuard.markStable(ctx) }
    }

    /** The screen keeps flickering while KDX keeps running (KDX's window keeps losing the screen): the details. */
    var flickerDetails by mutableStateOf<String?>(null)

    fun reportFlicker(details: String) { if (flickerDetails == null) flickerDetails = details }

    /** For tests: show the safe-mode notice with [details]. */
    internal fun enterSafeModeForTest(details: String) { safeModeDetails = details; safeMode = true }

    /** The user saw the safe-mode notice and wants things back to normal. */
    fun leaveSafeMode() {
        safeMode = false
        StartGuard.mode = StartGuard.Mode.NORMAL
        StartGuard.markStable(ctx)
        runCatching { StartGuard.crashFile(ctx).delete() }
        reloadDroid()
        if (realKde) viewModelScope.launch(guard) { delay(2500); runRealKde() }
    }

    init {
        checkStartLoop()
        reloadApps()
        // safe mode: not even the Linux system (it comes back with "Resume")
        if (!safeMode) reloadDroid()
        // real KDE Plasma as the desktop: start it (or continue setting it up) when KDX opens
        if (realKde && !safeMode) viewModelScope.launch(guard) { delay(2500); runRealKde() }
        // automatic space saving: a minute after start, then every 30 minutes (not in safe mode)
        viewModelScope.launch(guard) { delay(60_000); while (true) { if (!safeMode) checkStorage(); delay(30 * 60_000L) } }
    }

    fun reloadApps() {
        viewModelScope.launch(guard) {
            appsLoading = true
            androidApps = withContext(Dispatchers.IO) { queryApps() }
            appsLoading = false
        }
    }

    private fun queryApps(): List<AndroidApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val sizePx = (48 * ctx.resources.displayMetrics.density).toInt()
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val icon = runCatching { ri.loadIcon(pm).toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
                AndroidApp(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri.activityInfo.name, icon)
            }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
    }

    // ---- window management ----
    val activeWindow get() = windows.lastOrNull { it.id == activeId && !it.minimized && it.desktop == currentDesktop }

    fun windowsOnDesktop(d: Int = currentDesktop) = windows.filter { it.desktop == d }

    fun open(app: KApp, arg: String? = null) {
        closeMenus()
        // Single-instance apps are focused instead of duplicated.
        if (app.droidOnly && droid == null) { toast("Enable the Linux system (DroidSystem) in System Settings to use ${app.title}"); return }
        if (app == KApp.PLASMA) { launchPlasma(); return }
        if (app == KApp.WINDSYSTEM) { launchWindSystem(); return }
        if (app in setOf(KApp.SETTINGS, KApp.KLIPPER, KApp.SYSMON, KApp.INFOCENTER, KApp.KNOTES, KApp.BAZAAR, KApp.LINUXDISPLAY, KApp.ADBLOCK, KApp.PRIVACY)) {
            windows.firstOrNull { it.app == app }?.let {
                it.desktop = currentDesktop
                focus(it)
                return
            }
        }
        val w = KWindow(nextId++, app, arg, currentDesktop, maximized = !floatingByDefault)
        windows.add(w)
        activeId = w.id
    }

    fun focus(w: KWindow) {
        w.minimized = false
        if (w.desktop != currentDesktop) currentDesktop = w.desktop
        if (windows.lastOrNull() != w) {
            windows.remove(w)
            // keep "keep above" windows on top
            val idx = if (w.keepAbove) windows.size else windows.indexOfFirst { it.keepAbove }.let { if (it < 0) windows.size else it }
            windows.add(idx, w)
        }
        activeId = w.id
    }

    fun close(w: KWindow) {
        if (w.app == KApp.WINDSYSTEM) {
            val ws = windSession; windSession = null; windWindows = emptyList(); windStatus = null
            if (ws != null) viewModelScope.launch(Dispatchers.IO + guard) { runCatching { ws.shutdown() } }
        }
        w.dispose()
        // a Linux app / Plasma / WindSystem window owns an X display: stop it even if the window was never drawn
        // (opened on another desktop, minimised…) – it used to keep its display server running and its number taken
        if (w.app == KApp.LINUXDISPLAY || w.app == KApp.WINDSYSTEM) w.arg?.takeIf { it.startsWith("app:") }?.removePrefix("app:")?.toIntOrNull()?.let { releaseDisplay(it) }
        windows.remove(w)
        if (activeId == w.id) activeId = windows.lastOrNull { !it.minimized && it.desktop == currentDesktop }?.id
    }

    fun minimize(w: KWindow) {
        w.minimized = true
        if (activeId == w.id) activeId = windows.lastOrNull { !it.minimized && it.desktop == currentDesktop && it != w }?.id
    }

    fun toggleMaximize(w: KWindow) {
        w.maximized = !w.maximized
        focus(w)
    }

    fun taskClicked(w: KWindow) {
        if (w.id == activeId && !w.minimized) minimize(w) else focus(w)
    }

    fun switchDesktop(d: Int) {
        currentDesktop = d.mod(desktopCount)
        activeId = windows.lastOrNull { !it.minimized && it.desktop == currentDesktop }?.id
    }

    fun setDesktops(n: Int) {
        desktopCount = n.coerceIn(1, 8)
        windows.forEach { if (it.desktop >= desktopCount) it.desktop = desktopCount - 1 }
        if (currentDesktop >= desktopCount) switchDesktop(desktopCount - 1)
        save()
    }

    fun closeMenus() {
        kickoffOpen = false
        krunnerOpen = false
        overviewOpen = false
        calendarOpen = false
        widgetExplorerOpen = false
    }

    /** Back button: close menus first, then the active window. Returns false if nothing to do. */
    fun back(): Boolean {
        if (kickoffOpen || krunnerOpen || overviewOpen || calendarOpen || widgetExplorerOpen) {
            closeMenus(); return true
        }
        activeWindow?.let { minimize(it); return true }
        return false
    }

    // ---- favourites ----
    fun isFavorite(key: String) = key in favorites
    fun toggleFavorite(key: String) {
        if (!favorites.remove(key)) favorites.add(key)
        save()
    }

    // ---- launching ----
    fun launch(a: AndroidApp) {
        closeMenus()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(a.pkg, a.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // in a floating window, where Android allows it (otherwise full screen as usual)
        val opts = if (androidAppWindows && AndroidWindows.supported(ctx)) {
            val dm = ctx.resources.displayMetrics
            AndroidWindows.options(AndroidWindows.bounds(androidWindowCount++, dm.widthPixels, dm.heightPixels))
        } else null
        runCatching { ctx.startActivity(intent, opts) }
            .recoverCatching { if (opts != null) ctx.startActivity(intent) else throw it }
            .onFailure { toast("Could not start ${a.label}") }
    }

    fun launchKey(key: String) {
        if (key.startsWith("kdx:")) {
            runCatching { KApp.valueOf(key.removePrefix("kdx:")) }.getOrNull()?.let { open(it) }
        } else androidApps.firstOrNull { it.key == key }?.let { launch(it) }
    }

    fun openUrl(url: String) {
        closeMenus()
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("No app can open $url") }
    }

    fun openIntent(intent: Intent) {
        closeMenus()
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Not supported on this device") }
    }

    fun openAndroidSettings() = openIntent(Intent(Settings.ACTION_SETTINGS))
    fun openHomeSettings() = openIntent(Intent(Settings.ACTION_HOME_SETTINGS))

    fun appInfo(pkg: String) = openIntent(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
    )

    fun uninstall(pkg: String) = openIntent(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // ---- clipboard (Klipper) ----
    fun recordClip(text: String?) {
        val t = text?.takeIf { it.isNotBlank() } ?: return
        clipboardHistory.remove(t)
        clipboardHistory.add(0, t)
        while (clipboardHistory.size > 50) clipboardHistory.removeAt(clipboardHistory.lastIndex)
    }

    fun copyText(text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("KDX", text))
        recordClip(text)
    }

    // ---- storage helpers ----
    fun hasStorageAccess(): Boolean = runCatching {
        // KDX targets Android 9, so the classic storage permission grants access; "all files access" works too.
        // (isExternalStorageManager can throw when no external storage volume is mounted.)
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
            (android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
    }.getOrDefault(false)

    fun homeDir(): File =
        if (hasStorageAccess()) Environment.getExternalStorageDirectory() else ctx.filesDir

    override fun onCleared() {
        windows.forEach { it.dispose() }
        appDisplays.values.forEach { runCatching { it.stop() } }
        org.kdx.shell.droid.LinuxAppsService.update(ctx, 0)
        androidAudio.stop(); linuxAudio?.stop()
        linuxDisplay?.stop()
    }
}


/**
 * Render resolution for Linux app windows. Apps draw in software on the CPU, so fewer pixels = more frames.
 * AUTO starts sharp and lowers/raises each window's resolution to keep it near 60 fps (see LinuxDisplayApp).
 */
enum class LinuxPerformance(val label: String, val scale: Float, val description: String) {
    AUTO("Auto (keep 60 fps)", 1f, "Lowers a window's resolution when it drops below 60 fps, raises it again when there's room"),
    SHARP("Sharp", 1f, "Full resolution"),
    BALANCED("Balanced", 0.75f, "75% resolution – faster"),
    FAST("Fast", 0.55f, "55% resolution – fastest, softer picture"),
}

/** Logical pixels on the short side of KDE Plasma's screen: room for its panels and menus on a phone. */
const val PLASMA_MIN_SIDE = 720f

/** How many pixels KDE Plasma draws (System Settings → Real KDE Plasma). [pixels] = share of the screen's pixels per side. */
enum class PlasmaResolution(val label: String, val description: String, val pixels: Float) {
    PHONE("Phone's resolution", "Every pixel of your screen: sharpest. Needs a fast phone.", 1f),
    BALANCED("Balanced", "About 80% of the pixels per side, scaled up: smoother.", 0.8f),
    FAST("Fast", "The fewest pixels Plasma needs: smoothest on slower phones.", 0f),
}
