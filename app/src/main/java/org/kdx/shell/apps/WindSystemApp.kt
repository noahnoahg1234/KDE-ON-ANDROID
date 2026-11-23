package org.kdx.shell.apps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.droid.WindSystem
import org.kdx.shell.droid.deleteTree

/** Runs [block] off the main thread (Wine/xdotool commands). */
private fun KdxState.io(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { runCatching { block() } } }

/** WindSystem's own colours: a dark or light taskbar and menus, with a blue accent. */
internal data class WindColors(val bar: Color, val menu: Color, val text: Color, val dim: Color, val hover: Color, val accent: Color = Color(0xFF3B8EEA))

internal fun windColors(dark: Boolean) = if (dark) WindColors(Color(0xF0202020), Color(0xF62B2B2B), Color.White, Color(0xFFB0B0B0), Color(0x22FFFFFF))
    else WindColors(Color(0xF0F3F3F3), Color(0xF8FAFAFA), Color(0xFF1B1B1B), Color(0xFF5F5F5F), Color(0x14000000))

/** WindSystem – Windows programs (Wine) on a desktop with a taskbar, start menu, search and clock. */
@Composable
fun WindSystemApp(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val deb = state.debian
    if (deb == null || !WindSystem.isInstalled(deb)) {
        Column(Modifier.fillMaxSize().background(c.window).padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Air, null, tint = Color(0xFF3B8EEA), modifier = Modifier.size(64.dp))
            Text("WindSystem", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("Runs Windows programs with Wine, set up to report Windows 10. Needs DroidSystem 2.", color = c.inactiveText,
                fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            if (deb != null) Button({ state.open(KApp.KONSOLE, "windsystem-setup") }) { Text("Install WindSystem") }
        }
        return
    }
    val wc = windColors(state.windConfig.darkTaskbar)
    var menu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    // Windows programs expect a mouse: one-finger drag = mouse drag (move windows by their title bars, select, drag
    // scrollbars); two fingers scroll; double-tap a title bar to maximize; hold = right-click
    LinuxDisplayApp(state, w, followApp = false, mouseStyleTouch = true,
        onDoubleTap = { x, y -> state.windSession?.let { ss -> state.io { ss.doubleTapAt(x, y) } } },
        desktopSize = { w, h -> state.windGeometry(w, h) }, desktopSizeKey = state.windConfig.resolution,
        overlay = {
            if (menu) {
                Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { menu = false; query = "" })
                StartMenu(state, wc, query, { query = it }, searchFocus, Modifier.align(Alignment.BottomStart),
                    close = { menu = false; query = "" }, openSettings = { menu = false; settings = true })
            }
            state.windStatus?.let { st ->
                Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(wc.menu).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = wc.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(st, color = wc.text, fontSize = 14.sp)
                }
            }
            state.windError?.let { err -> StartError(state, wc, err) }
            state.windCrashes.firstOrNull()?.let { (crash, report) -> CrashDialog(state, wc, crash, report) { state.windCrashes.removeAt(0) } }
            if (settings) WindSettings(state, wc) { settings = false }
        },
        bottomBar = { Taskbar(state, wc, menu, onStart = { menu = !menu; if (!menu) query = "" }, onSearch = { menu = true }, searchFocus = searchFocus, query = query, onQuery = { query = it; menu = true }) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Taskbar(
    state: KdxState, wc: WindColors, menuOpen: Boolean, onStart: () -> Unit, onSearch: () -> Unit,
    searchFocus: FocusRequester, query: String, onQuery: (String) -> Unit,
) {
    val cfg = state.windConfig
    val s = state.windSession
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 600.dp
    Row(Modifier.fillMaxWidth().height(46.dp).background(wc.bar), verticalAlignment = Alignment.CenterVertically) {
        // Start
        Box(Modifier.size(46.dp).background(if (menuOpen) wc.hover else Color.Transparent).clickable(onClick = onStart), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Air, "Start", tint = wc.accent, modifier = Modifier.size(26.dp))
        }
        // Search: a box on wide screens, a button on phones (the start menu then opens ready to type)
        if (cfg.showSearch && narrow) Box(Modifier.size(46.dp).clickable(onClick = onSearch), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Search, "Search", tint = wc.text, modifier = Modifier.size(22.dp))
        }
        if (cfg.showSearch && !narrow) Row(
            Modifier.padding(horizontal = 4.dp).widthIn(max = 220.dp).weight(1f, fill = false).height(34.dp)
                .clip(RoundedCornerShape(17.dp)).background(wc.hover).clickable(onClick = onSearch).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = wc.dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Type here to search", color = wc.dim, fontSize = 13.sp, maxLines = 1)
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(searchFocus), singleLine = true,
                    textStyle = TextStyle(color = wc.text, fontSize = 13.sp), cursorBrush = SolidColor(wc.accent))
            }
        }
        // Open windows
        LazyRow(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            items(state.windWindows, key = { it.first }) { (id, title) ->
                var menu by remember { mutableStateOf(false) }
                val active = id == state.windActive
                Box {
                    Column(
                        Modifier.fillMaxHeight().widthIn(min = 44.dp, max = if (narrow) 120.dp else 180.dp).background(if (active) wc.hover else Color.Transparent)
                            .combinedClickable(onClick = { if (active) s?.minimize(id) else s?.focus(id) }, onLongClick = { menu = true })
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.WebAsset, null, tint = wc.text, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(title, color = wc.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.align(Alignment.CenterHorizontally).width(if (active) 28.dp else 10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(wc.accent))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Close window") }, onClick = { menu = false; s?.let { state.io { it.close(id) } } })
                        DropdownMenuItem(text = { Text("Minimize") }, onClick = { menu = false; s?.minimize(id) })
                    }
                }
            }
        }
        // Clock
        var now by remember { mutableStateOf(java.util.Date()) }
        LaunchedEffect(cfg.clockSeconds) { while (true) { now = java.util.Date(); delay(if (cfg.clockSeconds) 1000 else 5000) } }
        val time = java.text.SimpleDateFormat(if (cfg.clock24h) (if (cfg.clockSeconds) "HH:mm:ss" else "HH:mm") else (if (cfg.clockSeconds) "h:mm:ss a" else "h:mm a"), java.util.Locale.getDefault()).format(now)
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(now)
        Column(Modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.End) {
            Text(time, color = wc.text, fontSize = 12.sp)
            Text(date, color = wc.text, fontSize = 11.sp)
        }
        // Show desktop
        Box(Modifier.width(8.dp).fillMaxHeight().border(0.5.dp, wc.dim.copy(alpha = 0.4f))
            .clickable { s?.let { ss -> val ids = state.windWindows.map { it.first }; state.io { ss.showDesktop(ids) } } })
    }
    }
}

private fun iconFor(p: WindSystem.Program): ImageVector = when (p.command.firstOrNull()) {
    "explorer" -> Icons.Filled.Folder; "wineconsole" -> Icons.Filled.Terminal; "notepad" -> Icons.Filled.Description
    "wordpad" -> Icons.Filled.Article; "mspaint" -> Icons.Filled.Brush; "taskmgr" -> Icons.Filled.MonitorHeart
    "control" -> Icons.Filled.Tune; "regedit" -> Icons.Filled.AccountTree; "winecfg" -> Icons.Filled.Settings
    "uninstaller" -> Icons.Filled.DeleteSweep; "iexplore" -> Icons.Filled.Public; "winecalc" -> Icons.Filled.Calculate
    "msiexec" -> Icons.Filled.InstallDesktop
    "dosbox-x" -> Icons.Filled.SportsEsports
    else -> if (p.category == "Programs folder") Icons.Filled.InstallDesktop else Icons.Filled.Apps
}

@Composable
private fun StartMenu(
    state: KdxState, wc: WindColors, query: String, onQuery: (String) -> Unit, searchFocus: FocusRequester,
    modifier: Modifier, close: () -> Unit, openSettings: () -> Unit,
) {
    val s = state.windSession
    val deb = state.debian ?: return
    val installed by produceState(emptyList<WindSystem.Program>()) {
        value = withContext(Dispatchers.IO) { runCatching { WindSystem.installedPrograms(deb, state.windSharedDir()) }.getOrDefault(emptyList()) }
    }
    val all = remember(installed) { installed + WindSystem.BUILTIN }
    fun run(p: WindSystem.Program) { close(); s?.let { ss -> state.io { ss.run(p) } } }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
    BoxWithConstraints(modifier) {
        val phone = maxWidth < 600.dp
        Column(
            Modifier.padding(6.dp).width(minOf(maxWidth - 12.dp, 440.dp)).heightIn(max = maxHeight * 0.8f)
                .clip(RoundedCornerShape(10.dp)).background(wc.menu).padding(12.dp),
        ) {
            if (!state.windConfig.showSearch || phone) {
                // the search box lives here on phones, or when it's hidden from the taskbar
                Row(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(19.dp)).background(wc.hover).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = wc.dim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(query, onQuery, Modifier.weight(1f).focusRequester(searchFocus), singleLine = true,
                        textStyle = TextStyle(color = wc.text, fontSize = 14.sp), cursorBrush = SolidColor(wc.accent))
                }
                Spacer(Modifier.height(8.dp))
            }
            val results = if (query.isBlank()) all else all.filter { it.name.contains(query, true) || it.command.any { a -> a.contains(query, true) } }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (query.isNotBlank()) item {
                    MenuRow(wc, Icons.Filled.PlayArrow, "Run \"$query\"", "Windows command or program path") { close(); s?.let { ss -> state.io { ss.runLine(query) } } }
                }
                if (query.isBlank() && installed.isEmpty()) item {
                    Text("Put Windows installers (.exe, .msi) in ${state.windSharedDir().path}/Programs, and DOS games/programs in its DOS folder – they appear here.",
                        color = wc.dim, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
                }
                val groups = results.groupBy { it.category.ifEmpty { "Programs" } }
                groups.forEach { (cat, list) ->
                    item { Text(cat, color = wc.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp)) }
                    items(list, key = { cat + it.name }) { p -> MenuRow(wc, iconFor(p), p.name, null) { run(p) } }
                }
                if (results.isEmpty() && query.isNotBlank()) item { Text("No programs match \"$query\".", color = wc.dim, fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = wc.dim.copy(alpha = 0.3f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AccountCircle, null, tint = wc.text, modifier = Modifier.size(26.dp))
                Text("  WindSystem", color = wc.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton({ close(); state.open(KApp.DOLPHIN, state.windSharedDir().path) }) { Icon(Icons.Filled.FolderOpen, "Android/WindSystem folder", tint = wc.text) }
                IconButton(openSettings) { Icon(Icons.Filled.Settings, "WindSystem settings", tint = wc.text) }
                var power by remember { mutableStateOf(false) }
                Box {
                    IconButton({ power = true }) { Icon(Icons.Filled.PowerSettingsNew, "Power", tint = wc.text) }
                    DropdownMenu(power, { power = false }) {
                        DropdownMenuItem(text = { Text("Restart") }, leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                            onClick = { power = false; close(); s?.let { ss -> state.io { ss.restart() } } })
                        DropdownMenuItem(text = { Text("Shut down") }, leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null) },
                            onClick = { power = false; close(); state.endWindSystem() })
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(wc: WindColors, icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = wc.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = wc.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = wc.dim, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun BoxScope.CrashDialog(state: KdxState, wc: WindColors, crash: WindSystem.Crash, report: java.io.File?, dismiss: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 520.dp).clip(RoundedCornerShape(10.dp)).background(wc.menu)
            .border(1.dp, Color(0xFFE81123).copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFFE81123), modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("${crash.program} stopped working", color = wc.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(crash.summary, color = wc.dim, fontSize = 12.sp, maxLines = 3)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 220.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0x22000000))
            .verticalScroll(rememberScrollState()).padding(8.dp)) {
            crash.details.forEach { Text(it, color = wc.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
        }
        report?.let { Text("Report saved: ${it.path}", color = wc.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            report?.let { f -> TextButton({ state.open(KApp.KATE, f.path) }) { Text("Open report") } }
            TextButton({
                val prog = (WindSystem.BUILTIN.firstOrNull { it.name == crash.program })
                    ?: state.debian?.let { d -> WindSystem.installedPrograms(d, state.windSharedDir()).firstOrNull { it.name == crash.program } }
                prog?.let { p -> state.windSession?.let { ss -> state.io { ss.run(p) } } }
                dismiss()
            }) { Text("Restart program") }
            Button(dismiss) { Text("Close") }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.WindSettings(state: KdxState, wc: WindColors, close: () -> Unit) {
    val cfg = state.windConfig
    fun set(c: WindSystem.Config) = state.changeWindConfig(c)
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.align(Alignment.Center).padding(12.dp).widthIn(max = 560.dp).fillMaxHeight(0.92f).clip(RoundedCornerShape(10.dp))
            .background(wc.menu).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Settings, null, tint = wc.accent)
            Text("  WindSystem settings", color = wc.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(close) { Icon(Icons.Filled.Close, "Close", tint = wc.text) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            @Composable fun Head(t: String) = Text(t, color = wc.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            @Composable fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
                Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, onClick); Text(label, color = wc.text, fontSize = 14.sp)
                }
            @Composable fun Switch(label: String, sub: String?, v: Boolean, onChange: (Boolean) -> Unit) =
                Row(Modifier.fillMaxWidth().clickable { onChange(!v) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(label, color = wc.text, fontSize = 14.sp); sub?.let { Text(it, color = wc.dim, fontSize = 11.sp) } }
                    androidx.compose.material3.Switch(v, onChange)
                }

            Head("Windows version programs see")
            WindSystem.WINDOWS_VERSIONS.forEach { (id, name) -> Choice(name, cfg.windowsVersion == id) { set(cfg.copy(windowsVersion = id)) } }

            Head("Screen resolution")
            listOf("phone" to "Phone's resolution (sharpest)", "balanced" to "Balanced", "fast" to "Fast (fewest pixels)").forEach { (id, l) ->
                Choice(l, cfg.resolution == id) { set(cfg.copy(resolution = id)) }
            }
            Text("Fixed size (the picture is scaled to fit; pinch to zoom)", color = wc.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WindSystem.RESOLUTIONS.forEach { r ->
                    val (rw, rh) = WindSystem.fixedResolution(r)!!
                    FilterChip(cfg.resolution == r, { set(cfg.copy(resolution = r)) },
                        label = { Text(r.replace("x", " × ") + if (rh > rw) " (portrait)" else "") })
                }
            }
            var custom by remember { mutableStateOf(if (WindSystem.fixedResolution(cfg.resolution) != null && cfg.resolution !in WindSystem.RESOLUTIONS) cfg.resolution else "") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(custom, { custom = it.filter { ch -> ch.isDigit() || ch == 'x' } }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Custom (e.g. 1152x864)") })
                TextButton({ WindSystem.fixedResolution(custom)?.let { (cw, ch) -> set(cfg.copy(resolution = "${cw}x$ch")) } ?: state.toast("Type a size like 1152x864") }) { Text("Use") }
            }
            Text("Changes apply right away; programs that were open may need to be moved or reopened.", color = wc.dim, fontSize = 11.sp)
            Text("Text and window size (DPI)", color = wc.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Row { listOf(0 to "Auto", 96 to "100%", 120 to "125%", 144 to "150%", 192 to "200%").forEach { (d, l) ->
                FilterChip(cfg.dpi == d, { set(cfg.copy(dpi = d)) }, label = { Text(l) }, modifier = Modifier.padding(end = 4.dp)) } }

            Head("Graphics and sound")
            Choice("3D through OpenGL (games, 3D programs)", cfg.renderer == "gl") { set(cfg.copy(renderer = "gl")) }
            Choice("No 3D – fastest for normal programs", cfg.renderer == "gdi") { set(cfg.copy(renderer = "gdi")) }
            Switch("Sound", "Windows programs play through the phone's speaker", cfg.sound) { set(cfg.copy(sound = it)) }

            Head("Drives")
            Text("C: Windows (private)  ·  D: ${state.windSharedDir().path}", color = wc.dim, fontSize = 12.sp)
            Switch("Phone storage as drive E:", "All your files (photos, downloads…) in File Explorer and Open dialogs", cfg.phoneDriveE) { set(cfg.copy(phoneDriveE = it)) }

            Head("Look")
            Switch("Modern window style", "Wine's flat \"Light\" theme for title bars, buttons and menus (off = classic)", cfg.modernTheme) { set(cfg.copy(modernTheme = it)) }
            Switch("Touch-sized window frames", "Bigger title bars, window buttons, borders, menus and scrollbars (applies to windows opened after the change)", cfg.touchFrames) { set(cfg.copy(touchFrames = it)) }

            Head("Taskbar and desktop")
            Switch("Dark taskbar and menus", null, cfg.darkTaskbar) { set(cfg.copy(darkTaskbar = it)) }
            Switch("Search box on the taskbar", null, cfg.showSearch) { set(cfg.copy(showSearch = it)) }
            Switch("24-hour clock", null, cfg.clock24h) { set(cfg.copy(clock24h = it)) }
            Switch("Show seconds", null, cfg.clockSeconds) { set(cfg.copy(clockSeconds = it)) }
            Text("Desktop colour", color = wc.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(vertical = 4.dp)) {
                listOf(0xFF0F4C81, 0xFF1B1B1B, 0xFF2E7D32, 0xFF6A1B9A, 0xFFB71C1C, 0xFF37474F, 0xFF00695C).forEach { col ->
                    Box(Modifier.padding(end = 8.dp).size(30.dp).clip(CircleShape).background(Color(col))
                        .border(if (cfg.wallpaper == col) 3.dp else 1.dp, if (cfg.wallpaper == col) wc.accent else wc.dim, CircleShape)
                        .clickable { set(cfg.copy(wallpaper = col)) })
                }
            }

            if (state.debian?.let { WindSystem.usesBox64(it) } == true || true) {
                Head("Speed (ARM phones: box64)")
                listOf("stable" to "Most compatible", "balanced" to "Balanced", "fast" to "Fastest (some programs may crash)").forEach { (id, l) ->
                    Choice(l, cfg.performance == id) { set(cfg.copy(performance = id)) }
                }
                Text("Applies to programs started after the change.", color = wc.dim, fontSize = 11.sp)
            }

            Head("When a program crashes")
            Switch("Show a crash dialog", "With what happened and a saved crash report", cfg.crashDialog) { set(cfg.copy(crashDialog = it)) }
            Switch("Restart it automatically (once)", null, cfg.autoRestart) { set(cfg.copy(autoRestart = it)) }
            Text("Logging", color = wc.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row { listOf("off" to "Off", "errors" to "Errors", "warnings" to "Warnings", "verbose" to "Detailed").forEach { (id, l) ->
                FilterChip(cfg.debugLog == id, { set(cfg.copy(debugLog = id)) }, label = { Text(l) }, modifier = Modifier.padding(end = 4.dp)) } }

            Head("Advanced")
            var dlls by remember { mutableStateOf(cfg.dllOverrides) }
            OutlinedTextField(dlls, { dlls = it }, Modifier.fillMaxWidth(), label = { Text("DLL overrides (e.g. d3d9=native,builtin)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { set(cfg.copy(dllOverrides = dlls)) }))
            TextButton({ set(cfg.copy(dllOverrides = dlls)) }) { Text("Save overrides") }
            Row { listOf("Wine Configuration" to "winecfg", "Registry Editor" to "regedit", "Task Manager" to "taskmgr").forEach { (l, cmd) ->
                OutlinedButton({ state.windSession?.let { ss -> state.io { ss.run(WindSystem.Program(l, listOf(cmd))) } }; close() },
                    Modifier.padding(end = 4.dp)) { Text(l, fontSize = 12.sp) } } }
            TextButton({ confirmReset = true }) { Text("Reset drive C: (removes installed Windows programs)", color = Color(0xFFE81123)) }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("Reset drive C:?") },
        text = { Text("All Windows programs installed in WindSystem and their settings are deleted. Files in Android/WindSystem (drive D:) stay.") },
        confirmButton = { TextButton({
            confirmReset = false; close()
            val deb = state.debian
            state.endWindSystem()
            scope.launch(Dispatchers.IO) { delay(1500); deb?.let { java.io.File(it.root, WindSystem.PREFIX.removePrefix("/")).deleteTree() } }
        }) { Text("Reset", color = Color(0xFFE81123)) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } })
}

/** The start menu's program list with given programs (used by the screenshot test). */
@Composable
internal fun StartMenuPreview(wc: WindColors, programs: List<WindSystem.Program>) {
    Column(Modifier.width(380.dp).clip(RoundedCornerShape(10.dp)).background(wc.menu).padding(12.dp)) {
        programs.groupBy { it.category }.forEach { (cat, list) ->
            Text(cat, color = wc.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
            list.forEach { p -> MenuRow(wc, iconFor(p), p.name, null) {} }
        }
    }
}

/** WindSystem couldn't start: what happened, a self-check and the log – with Copy, Retry and Repair. */
@Composable
private fun BoxScope.StartError(state: KdxState, wc: WindColors, details: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(
        Modifier.align(Alignment.Center).padding(12.dp).widthIn(max = 620.dp).fillMaxHeight(0.9f).clip(RoundedCornerShape(10.dp))
            .background(wc.menu).border(1.dp, Color(0xFFE81123).copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFFE81123), modifier = Modifier.size(30.dp))
            Text("  WindSystem couldn't start", color = wc.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("Copy the details below and send them, or try Repair (runs the installer again).", color = wc.dim, fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 6.dp))
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0x22000000))
            .verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text(details, color = wc.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(details)); state.toast("Copied") }) { Text("Copy details") }
            TextButton({ state.windError = null; state.endWindSystem(); state.open(KApp.KONSOLE, "windsystem-setup") }) { Text("Repair") }
            TextButton({ state.windError = null; state.endWindSystem(); state.launchWindSystem() }) { Text("Retry") }
            Button({ state.windError = null; state.endWindSystem() }) { Text("Close") }
        }
    }
}
