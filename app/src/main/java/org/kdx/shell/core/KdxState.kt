package org.kdx.shell.core

import android.app.Application
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    ;

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

    // ---- settings (persisted) ----
    var themeMode by mutableStateOf(ThemeMode.entries[prefs.getInt("theme", 0).coerceIn(0, 2)])
    var accentIndex by mutableIntStateOf(prefs.getInt("accent", 1))
    var wallpaperIndex by mutableIntStateOf(prefs.getInt("wallpaper", 0))
    var customWallpaper by mutableStateOf(prefs.getBoolean("customWallpaper", false))
    var wallpaperVersion by mutableIntStateOf(0)
    var desktopCount by mutableIntStateOf(prefs.getInt("desktops", 4))
    var floatingByDefault by mutableStateOf(prefs.getBoolean("floating", false))
    var showClockWidget by mutableStateOf(prefs.getBoolean("wClock", true))
    var showNotesWidget by mutableStateOf(prefs.getBoolean("wNotes", true))
    var showSystemWidget by mutableStateOf(prefs.getBoolean("wSystem", true))
    var showDesktopIcons by mutableStateOf(prefs.getBoolean("icons", true))
    var use24h by mutableStateOf(prefs.getBoolean("h24", true))
    var panelOnTop by mutableStateOf(prefs.getBoolean("panelTop", false))
    var showSeconds by mutableStateOf(prefs.getBoolean("seconds", false))
    var notes by mutableStateOf(prefs.getString("notes", "Welcome to KDX!\n• Tap the K button for Kickoff\n• Search with KRunner\n• Long-press apps to add favourites")!!)
    val favorites = mutableStateListOf<String>().apply {
        addAll(
            prefs.getString("favs", null)?.split('|')?.filter { it.isNotBlank() }
                ?: listOf(KApp.DOLPHIN.key, KApp.KONSOLE.key, KApp.KATE.key, KApp.SETTINGS.key)
        )
    }

    fun save() = prefs.edit()
        .putInt("theme", themeMode.ordinal)
        .putInt("accent", accentIndex)
        .putInt("wallpaper", wallpaperIndex)
        .putBoolean("customWallpaper", customWallpaper)
        .putInt("desktops", desktopCount)
        .putBoolean("floating", floatingByDefault)
        .putBoolean("wClock", showClockWidget)
        .putBoolean("wNotes", showNotesWidget)
        .putBoolean("wSystem", showSystemWidget)
        .putBoolean("icons", showDesktopIcons)
        .putBoolean("h24", use24h)
        .putBoolean("panelTop", panelOnTop)
        .putBoolean("seconds", showSeconds)
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

    init {
        reloadApps()
    }

    fun reloadApps() {
        viewModelScope.launch {
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
        if (app in setOf(KApp.SETTINGS, KApp.KLIPPER, KApp.SYSMON, KApp.INFOCENTER, KApp.KNOTES)) {
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
        w.dispose()
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
    }

    /** Back button: close menus first, then the active window. Returns false if nothing to do. */
    fun back(): Boolean {
        if (kickoffOpen || krunnerOpen || overviewOpen || calendarOpen) {
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
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(a.pkg, a.cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        }.onFailure { toast("Could not start ${a.label}") }
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
        // Can throw when no external storage volume is mounted.
        if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun homeDir(): File =
        if (hasStorageAccess()) Environment.getExternalStorageDirectory() else ctx.filesDir

    override fun onCleared() {
        windows.forEach { it.dispose() }
    }
}
