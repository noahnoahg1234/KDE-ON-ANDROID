package org.kdx.shell.apps

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kdx.shell.BuildConfig
import org.kdx.shell.core.Accents
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.core.ThemeMode
import org.kdx.shell.core.Wallpapers
import org.kdx.shell.core.brush
import org.kdx.shell.shell.rememberBattery
import java.io.File

@Composable
private fun Section(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBreeze.current
    Column(
        Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(8.dp)).background(c.view)
            .border(1.dp, c.border.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun KV(k: String, v: String) {
    val c = LocalBreeze.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, Modifier.width(140.dp), color = c.inactiveText, fontSize = 13.sp)
        Text(v, color = c.text, fontSize = 13.sp)
    }
}

/** Live line graph, like the sensors in Plasma System Monitor. */
@Composable
private fun Graph(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val c = LocalBreeze.current
    Canvas(modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(4.dp)).background(c.window)) {
        for (i in 1..3) drawLine(c.border.copy(alpha = 0.4f), Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
        if (values.size < 2) return@Canvas
        val step = size.width / (60 - 1)
        val start = size.width - step * (values.size - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = start + i * step
            val y = size.height * (1 - v.coerceIn(0f, 1f))
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        val fill = Path().apply { addPath(p); lineTo(size.width, size.height); lineTo(start, size.height); close() }
        drawPath(fill, color.copy(alpha = 0.2f))
        drawPath(p, color, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun readCpuFreqs(): List<Long> = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
    runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000 }.getOrDefault(-1)
}

private fun readMaxFreq(i: Int): Long =
    runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }.getOrDefault(-1)

class MonitorSession {
    val mem = mutableStateListOf<Float>()
    val cpu = mutableStateListOf<Float>()
}

/** Plasma System Monitor. */
@Composable
fun SystemMonitor(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    val s = remember(w) { w.retain("sysmon") { MonitorSession() } }
    val battery = rememberBattery()
    var memInfo by remember { mutableStateOf(ActivityManager.MemoryInfo()) }
    var freqs by remember { mutableStateOf(emptyList<Long>()) }
    val maxFreqs = remember { (0 until Runtime.getRuntime().availableProcessors()).map { readMaxFreq(it) } }
    LaunchedEffect(Unit) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        while (true) {
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            memInfo = mi
            s.mem += if (mi.totalMem > 0) 1f - mi.availMem.toFloat() / mi.totalMem else 0f
            val f = withContext(Dispatchers.IO) { readCpuFreqs() }
            freqs = f
            val load = f.indices.mapNotNull { i -> if (f[i] > 0 && maxFreqs[i] > 0) f[i].toFloat() / maxFreqs[i] else null }
            s.cpu += if (load.isEmpty()) 0f else load.average().toFloat()
            while (s.mem.size > 60) s.mem.removeAt(0)
            while (s.cpu.size > 60) s.cpu.removeAt(0)
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().background(c.window).verticalScroll(rememberScrollState())) {
        val gib = 1073741824.0
        Section("Memory", Icons.Filled.Memory) {
            Text(
                "%.2f GiB used of %.2f GiB (%.0f%%)%s".format(
                    (memInfo.totalMem - memInfo.availMem) / gib, memInfo.totalMem / gib,
                    100.0 * (memInfo.totalMem - memInfo.availMem) / memInfo.totalMem.coerceAtLeast(1),
                    if (memInfo.lowMemory) " · LOW" else "",
                ), color = c.text, fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Graph(s.mem, c.accent)
        }
        Section("CPU frequency", Icons.Filled.DeveloperBoard) {
            Text("${Runtime.getRuntime().availableProcessors()} cores · ${Build.SUPPORTED_ABIS.firstOrNull() ?: ""}", color = c.text, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Graph(s.cpu, Color(0xFF27AE60))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                freqs.forEachIndexed { i, f ->
                    Text("cpu$i ${if (f > 0) "$f MHz" else "n/a"}", color = c.inactiveText, fontSize = 12.sp)
                }
            }
        }
        Section("Battery", Icons.Filled.BatteryStd) {
            if (battery.level >= 0) {
                LinearProgressIndicator(progress = { battery.level / 100f }, Modifier.fillMaxWidth(), color = if (battery.level <= 15) c.negative else c.positive)
                Spacer(Modifier.height(6.dp))
                KV("Charge", "${battery.level}%")
                KV("State", if (battery.charging) "Charging" else "Discharging")
                KV("Temperature", "%.1f °C".format(battery.tempC))
                KV("Voltage", "${battery.voltage} mV")
            } else Text("No battery", color = c.inactiveText)
        }
        Section("Storage", Icons.Filled.Storage) {
            listOf("Internal storage" to Environment.getExternalStorageDirectory(), "System" to Environment.getRootDirectory(), "App data" to ctx.filesDir)
                .forEach { (name, dir) ->
                    runCatching {
                        val st = StatFs(dir.path)
                        val total = st.totalBytes
                        val free = st.availableBytes
                        Text("$name — ${humanSize(total - free)} used of ${humanSize(total)}", color = c.text, fontSize = 13.sp)
                        LinearProgressIndicator(progress = { (total - free).toFloat() / total.coerceAtLeast(1) }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                }
        }
        Section("Uptime", Icons.Filled.Timer) {
            val now by produceState(SystemClock.elapsedRealtime()) { while (true) { value = SystemClock.elapsedRealtime(); delay(1000) } }
            val sec = now / 1000
            KV("System uptime", "%dd %02d:%02d:%02d".format(sec / 86400, sec / 3600 % 24, sec / 60 % 60, sec % 60))
            KV("KDX windows", "${state.windows.size} open on ${state.desktopCount} desktops")
        }
    }
}

/** KInfoCenter – static device information. */
@Composable
fun InfoCenter(state: KdxState) {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val dm = ctx.resources.displayMetrics
    Column(Modifier.fillMaxSize().background(c.window).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            org.kdx.shell.shell.KdxLogo(64.dp)
            Spacer(Modifier.height(8.dp))
            Text("KDX ${BuildConfig.VERSION_NAME}", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text("A KDE Plasma-inspired workspace for Android", color = c.inactiveText, fontSize = 13.sp)
        }
        Section("Software", Icons.Filled.Android) {
            KV("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            KV("Security patch", Build.VERSION.SECURITY_PATCH)
            KV("Kernel", System.getProperty("os.version") ?: "?")
            KV("Build", Build.DISPLAY)
            KV("Java VM", "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
        }
        Section("Hardware", Icons.Filled.PhoneAndroid) {
            KV("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            KV("Board", Build.BOARD)
            KV("Hardware", Build.HARDWARE)
            KV("Processors", "${Runtime.getRuntime().availableProcessors()} × ${Build.SUPPORTED_ABIS.joinToString()}")
            if (Build.VERSION.SDK_INT >= 31) KV("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            KV("Memory", humanSize(mi.totalMem))
        }
        Section("Display", Icons.Filled.Monitor) {
            KV("Resolution", "${dm.widthPixels} × ${dm.heightPixels} px")
            KV("Density", "${dm.densityDpi} dpi (×${dm.density})")
            KV("Window size", "${cfg.screenWidthDp} × ${cfg.screenHeightDp} dp")
            KV("Orientation", if (cfg.orientation == 2) "Landscape" else "Portrait")
        }
        Section("About", Icons.Filled.Info) {
            Text(
                "KDX re-creates the KDE Plasma desktop experience (Kickoff, KRunner, panel, " +
                    "virtual desktops, window management and core KDE apps) natively on Android. " +
                    "It is an independent project and is not affiliated with or endorsed by KDE e.V.",
                color = c.text, fontSize = 13.sp,
            )
            TextButton({ state.openUrl("https://kde.org") }) { Text("Learn about the real KDE at kde.org") }
        }
    }
}

/** System Settings – configure KDX. */
@Composable
fun SystemSettings(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope { org.kdx.shell.core.StartGuard.coroutines }
    val scroll = rememberScrollState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)!!.use { input -> state.wallpaperFile.outputStream().use { input.copyTo(it) } }
                }.isSuccess
            }
            if (ok) { state.customWallpaper = true; state.wallpaperVersion++; state.save() } else state.toast("Could not load image")
        }
    }
    LaunchedEffect(w.arg) { if (w.arg == "wallpaper") scroll.scrollTo(0) }

    fun set(block: () -> Unit) { block(); state.save() }

    Column(Modifier.fillMaxSize().background(c.window).verticalScroll(scroll)) {
        DesktopSection(state)
        RealKdeSection(state)
        LinuxSystemSection(state)
        if (state.debian != null) StoragePackingSection(state)
        TroubleshootingSection(state)
        Section("Wallpaper", Icons.Filled.Wallpaper) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Wallpapers.forEachIndexed { i, wp ->
                    val sel = !state.customWallpaper && state.wallpaperIndex == i
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(96.dp, 60.dp).clip(RoundedCornerShape(6.dp)).background(wp.brush())
                                .border(if (sel) 3.dp else 1.dp, if (sel) c.accent else c.border, RoundedCornerShape(6.dp))
                                .clickable { set { state.wallpaperIndex = i; state.customWallpaper = false } }
                        )
                        Text(wp.name, color = c.text, fontSize = 12.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(96.dp, 60.dp).clip(RoundedCornerShape(6.dp)).background(c.header)
                            .border(if (state.customWallpaper) 3.dp else 1.dp, if (state.customWallpaper) c.accent else c.border, RoundedCornerShape(6.dp))
                            .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.AddPhotoAlternate, "Choose image", tint = c.text) }
                    Text("Image…", color = c.text, fontSize = 12.sp)
                }
            }
        }
        Section("Colors & Theme", Icons.Filled.Palette) {
            ThemeMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { set { state.themeMode = m } }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.themeMode == m, { set { state.themeMode = m } })
                    Text(m.label, color = c.text)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Accent color", color = c.inactiveText, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Accents.forEachIndexed { i, (name, col) ->
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(col)
                            .border(if (state.accentIndex == i) 3.dp else 0.dp, c.text, CircleShape)
                            .clickable { set { state.accentIndex = i } },
                        contentAlignment = Alignment.Center,
                    ) { if (state.accentIndex == i) Icon(Icons.Filled.Check, name, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
        }
        Section("Workspace", Icons.Filled.DesktopWindows) {
            Text("Virtual desktops: ${state.desktopCount}", color = c.text)
            Slider(state.desktopCount.toFloat(), { state.setDesktops(it.toInt()) }, valueRange = 1f..8f, steps = 6)
            Toggle("Open windows floating instead of maximized", state.floatingByDefault) { set { state.floatingByDefault = it } }
            Text("Taskbar", color = c.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            org.kdx.shell.core.PanelPosition.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { set { state.panelPosition = p } }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.panelPosition == p, { set { state.panelPosition = p } })
                    Text(p.label, color = c.text, fontSize = 14.sp)
                }
            }
            Toggle("Show favorites as desktop icons", state.showDesktopIcons) { set { state.showDesktopIcons = it } }
        }
        Section("Widgets", Icons.Filled.Widgets) {
            if (state.desktopWidgets.isEmpty()) Text("No widgets on the desktop.", color = c.inactiveText, fontSize = 13.sp)
            state.desktopWidgets.toList().forEach { dw ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(dw.type.title, Modifier.weight(1f), color = c.text, fontSize = 14.sp)
                    TextButton({ state.removeWidget(dw) }) { Text("Remove") }
                }
            }
            OutlinedButton({ state.widgetExplorerOpen = true }) { Text("Add Widgets…") }
        }
        Section("Date & Time", Icons.Filled.Schedule) {
            Toggle("24-hour clock", state.use24h) { set { state.use24h = it } }
            Toggle("Show seconds on desktop clock", state.showSeconds) { set { state.showSeconds = it } }
        }
        Section("Shortcuts", Icons.Filled.Keyboard) {
            listOf(
                "Meta" to "Open Kickoff", "Alt+Space / Alt+F2" to "KRunner", "Meta+W" to "Overview",
                "Meta+D" to "Show desktop", "Alt+Tab" to "Switch windows", "Alt+F4" to "Close window",
                "Meta+↑ / Meta+↓" to "Maximize / restore", "Ctrl+F1…F8" to "Go to desktop",
                "Ctrl+Meta+← / →" to "Previous / next desktop", "Ctrl+Alt+T" to "Konsole",
                "Meta+E" to "Dolphin", "Meta+V" to "Klipper",
            ).forEach { (k, v) -> KV(k, v) }
            Text("Shortcuts work with a hardware keyboard or in desktop mode.", color = c.inactiveText, fontSize = 12.sp)
        }
        Section("Android Integration", Icons.Filled.Android) {
            OutlinedButton({ state.openHomeSettings() }, Modifier.fillMaxWidth()) { Text("Set KDX as default home app") }
            OutlinedButton({ state.openAndroidSettings() }, Modifier.fillMaxWidth()) { Text("Open Android Settings") }
            OutlinedButton({ state.appInfo(ctx.packageName) }, Modifier.fillMaxWidth()) { Text("KDX app permissions") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalBreeze.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = c.text, fontSize = 14.sp)
        Switch(value, onChange)
    }
}

/** The event log behind the safe-mode / flicker notices, for when the screen misbehaves. */
@Composable
private fun TroubleshootingSection(state: KdxState) {
    val c = LocalBreeze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Section("Troubleshooting", Icons.Filled.BugReport) {
        Text("If the screen blinks or flickers, KDX records what happened (restarts, crashes, Android ending it, other " +
            "windows taking the screen). Copy it and send it so the cause can be found.", color = c.inactiveText, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton({
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(org.kdx.shell.core.StartGuard.report(ctx)))
                state.toast("Diagnostics copied")
            }) { Text("Copy diagnostics") }
        }
    }
}

/**
 * System Settings → Desktop: KDX's resolution (how much fits on the screen) and the PC desktop – icons like a computer,
 * pinned apps on the taskbar, floating windows, Android apps in windows.
 */
@Composable
private fun DesktopSection(state: KdxState) {
    val c = LocalBreeze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dm = ctx.resources.displayMetrics
    Section("Desktop", Icons.Filled.DesktopWindows) {
        Text("Resolution", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("Higher = everything smaller and more room, like a computer monitor. Text never gets smaller than readable.",
            color = c.inactiveText, fontSize = 12.sp)
        org.kdx.shell.core.DesktopResolution.entries.forEach { r ->
            val (aw, ah) = org.kdx.shell.core.DesktopResolution.actualSize(r, dm.widthPixels, dm.heightPixels, dm.density)
            Row(Modifier.fillMaxWidth().clickable { state.desktopResolution = r; state.save() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.desktopResolution == r, { state.desktopResolution = r; state.save() })
                Column {
                    Text(r.label, color = c.text, fontSize = 14.sp)
                    Text("On this screen: $aw × $ah", color = c.inactiveText, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Toggle("PC desktop (icons in columns, pinned apps on the taskbar, floating windows)", state.pcDesktop) { state.changePcDesktop(it) }
        Toggle("Open Android apps in windows", state.androidAppWindows) { state.androidAppWindows = it; state.save() }
        if (state.androidAppWindows) {
            // read again every time (the user may just have come back from Developer options)
            val supported = org.kdx.shell.core.AndroidWindows.supported(ctx)
            val canAsk = org.kdx.shell.core.AndroidWindows.canRequestWindows
            val usage = org.kdx.shell.core.AndroidWindows.hasUsageAccess(ctx)
            Text(if (usage) "Open Android app windows show on KDX's taskbar. Long-press one there → Maximize fills the desktop " +
                    "and keeps the taskbar visible (the maximize button Android puts on the window makes the app full screen)."
                else "Android app windows you open from KDX show on its taskbar. To also see apps opened another way – and have " +
                    "them leave the taskbar when closed – allow KDX \"Usage access\".", color = c.inactiveText, fontSize = 12.sp)
            if (!usage) OutlinedButton({ org.kdx.shell.core.AndroidWindows.openUsageAccess(ctx) }) { Text("Allow Usage access") }
            if (!canAsk) Text("Android blocks KDX from asking for floating windows on this phone, so Android apps open full screen.",
                color = c.negative, fontSize = 12.sp)
            else if (supported) Text("Android apps open in floating windows you can move and resize. An app that's already " +
                "open full screen opens that way again – close it in Recents first. If apps still open full screen, " +
                "restart the phone once (Android only reads the freeform setting when it starts).", color = c.inactiveText, fontSize = 12.sp)
            else {
                Text("Your phone has floating windows switched off. Turn them on once: Developer options → " +
                    "\"Enable freeform windows\" (and \"Force activities to be resizable\"), then restart the phone. " +
                    "No Developer options? Settings → About phone → tap \"Build number\" 7 times. Until then Android " +
                    "apps open full screen as usual.", color = c.inactiveText, fontSize = 12.sp)
                OutlinedButton({ org.kdx.shell.core.AndroidWindows.openDeveloperOptions(ctx) }) { Text("Open Developer options") }
            }
        }
    }
}

/**
 * System Settings → Real KDE Plasma: one switch that sets up everything (Linux system, Debian, Plasma) and then runs
 * the real KDE Plasma desktop full screen, at the phone's own resolution if wanted.
 */
@Composable
private fun RealKdeSection(state: KdxState) {
    val c = LocalBreeze.current
    val deb = state.debian
    val plasmaReady = deb != null && org.kdx.shell.droid.KdePlasma.isInstalled(deb) && deb.hasCommand("usr/bin/Xvnc")
    val running = state.windows.any { it.fullscreen }
    Section("Real KDE Plasma", Icons.Filled.Dashboard) {
        Text(
            "Runs the real KDE Plasma 6 desktop from Debian (KWin, Plasma panel, Kickoff, Dolphin, Konsole, Kate, " +
                "System Settings…) instead of KDX's look-alike. Turn it on and KDX does every step for you, then starts " +
                "Plasma full screen, and again every time KDX opens.",
            color = c.inactiveText, fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Toggle("Use real KDE Plasma", state.realKde) { state.changeRealKde(it) }
        state.realKdeStep?.let { step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!step.contains("failed") && !step.contains("Couldn't")) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(step, Modifier.padding(start = 8.dp), color = c.text, fontSize = 13.sp)
            }
        }
        // the steps, so it's clear what's done and what's next
        @Composable fun Step(done: Boolean, text: String) = Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (done) c.positive else c.inactiveText, modifier = Modifier.size(18.dp))
            Text(text, Modifier.padding(start = 8.dp), color = c.text, fontSize = 13.sp)
        }
        Step(state.droid != null, "1. Linux system (DroidSystem): about 35 MB")
        Step(deb != null, "2. Debian base: built from official packages")
        Step(plasmaReady, "3. KDE Plasma: about 320 MB download, 1.2 GB on disk")
        Step(running, "4. Plasma running")

        Spacer(Modifier.height(8.dp))
        Text("Resolution", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        org.kdx.shell.core.PlasmaResolution.entries.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { state.changePlasmaResolution(r) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.plasmaResolution == r, { state.changePlasmaResolution(r) })
                Column {
                    Text(r.label, color = c.text, fontSize = 14.sp)
                    Text(r.description, color = c.inactiveText, fontSize = 11.sp)
                }
            }
        }
        val dm = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
        val (xw, xh) = state.plasmaGeometry(dm.widthPixels, dm.heightPixels)
        Text("Plasma draws ${xw}×$xh pixels on your ${dm.widthPixels}×${dm.heightPixels} screen, with its interface " +
            "scaled ${"%.2f".format(state.plasmaScale(minOf(xw, xh)))}× so it's easy to tap.", color = c.inactiveText, fontSize = 11.sp)

        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plasmaReady) Button({ state.launchPlasma() }) { Text(if (running) "Show KDE Plasma" else "Start KDE Plasma") }
            if (deb != null && !plasmaReady) OutlinedButton({ state.retryPlasmaSetup() }) { Text("Run installer again") }
            if (plasmaReady && !org.kdx.shell.droid.KdePlasma.bazaarInstalled(deb!!))
                OutlinedButton({ state.open(org.kdx.shell.core.KApp.KONSOLE, "bazaar-setup") }) { Text("Install the real Bazaar") }
        }
        Text("The real Bazaar app store (from Flathub) comes with Plasma. Apps you install with it, or with KDX's own " +
            "Bazaar, show up in Plasma's menu and open on Plasma's screen.", color = c.inactiveText, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp))
        Text("In Plasma, the small K button in the top corner takes you back to KDX. Plasma needs about 800 MB of RAM.",
            color = c.inactiveText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * System Settings → Storage packing: frees space in DroidSystem by cleaning up what's safe to delete, and by packing
 * big parts that aren't used all the time into compressed archives (unpacked automatically when opened).
 */
@Composable
private fun StoragePackingSection(state: KdxState) {
    val c = LocalBreeze.current
    val deb = state.debian ?: return
    val scope = rememberCoroutineScope { org.kdx.shell.core.StartGuard.coroutines }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val sizes by produceState<Map<String, Long>?>(null, refresh) {
        value = null
        value = withContext(Dispatchers.IO) {
            org.kdx.shell.droid.StoragePacker.ensureTool(deb)
            runCatching { org.kdx.shell.droid.StoragePacker.scan(deb) }.getOrDefault(emptyMap())
        }
    }
    fun job(label: String, cmd: String) {
        if (busy != null) return
        busy = label; result = null
        scope.launch {
            val before = sizes?.get("total") ?: 0L
            val out = StringBuilder()
            val rc = withContext(Dispatchers.IO) { runCatching { deb.run("$cmd </dev/null 2>&1", { out.appendLine(it) }) }.getOrDefault(-1) }
            busy = null
            refresh++
            result = if (rc == 0) "$label: done." else "$label failed (code $rc): " + out.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ")
            if (before > 0) state.toast("$label finished")
        }
    }
    Section("Storage packing", Icons.Filled.Compress) {
        Text("Frees space in DroidSystem. Clean up removes things that are safe to delete. Packing compresses big parts " +
            "you don't use all the time into one archive – they're unpacked automatically when you open them again " +
            "(that takes a moment).", color = c.inactiveText, fontSize = 13.sp)
        val s = sizes
        if (s == null) Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Measuring…", color = c.text, fontSize = 13.sp)
        } else {
            s["total"]?.let { KV("DroidSystem uses", humanSize(it)) }
            busy?.let { b ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  $b… keep KDX open (packing a lot can take several minutes).", color = c.text, fontSize = 13.sp)
                }
            }
            result?.let { Text(it, color = if (it.contains("failed")) c.negative else c.positive, fontSize = 12.sp) }

            // automatic saving
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Save space automatically when storage is low", color = c.text, fontSize = 14.sp)
                    Text("Cleans up, then packs parts you haven't opened for 3 days (anything not open, when the phone is " +
                        "nearly full). Free now: ${humanSize(state.freeBytes())}.", color = c.inactiveText, fontSize = 11.sp)
                }
                Switch(state.autoSave, { state.changeAutoSave(it) })
            }
            if (state.autoSave) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("When less than ", color = c.text, fontSize = 13.sp)
                listOf(1, 2, 5).forEach { gb ->
                    FilterChip(state.autoSaveGb == gb, { state.changeAutoSave(true, gb) }, label = { Text("$gb GB") }, modifier = Modifier.padding(end = 4.dp))
                }
                Text(" is free", color = c.text, fontSize = 13.sp)
            }
            if (state.autoSaving) Text("Saving space now…", color = c.text, fontSize = 12.sp)
            state.lastAutoSave?.let { Text("Last automatic save: $it", color = c.inactiveText, fontSize = 11.sp) }

            Spacer(Modifier.height(6.dp))
            Text("Clean up", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            org.kdx.shell.droid.StoragePacker.CLEANUPS.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title + (s[item.id]?.let { "  ·  " + humanSize(it) } ?: ""), color = c.text, fontSize = 14.sp)
                        Text(item.description, color = c.inactiveText, fontSize = 11.sp)
                    }
                    TextButton({ job("Cleaning ${item.title.lowercase()}", item.command) }, enabled = busy == null) { Text("Clean") }
                }
            }
            OutlinedButton({ job("Cleaning up", org.kdx.shell.droid.StoragePacker.CLEANUPS.joinToString("; ") { it.command }) },
                enabled = busy == null) { Text("Clean everything") }

            Spacer(Modifier.height(10.dp))
            Text("Packs", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            org.kdx.shell.droid.StoragePacker.PACKS.forEach { p ->
                val packed = org.kdx.shell.droid.StoragePacker.isPacked(deb, p.id)
                val current = s["pack:${p.id}"] ?: 0L
                if (!packed && current < 1_000_000) return@forEach // not installed / nearly empty
                val inUse = when (p.id) {
                    "plasma" -> state.windows.any { it.fullscreen && it.app == org.kdx.shell.core.KApp.LINUXDISPLAY }
                    "windsystem-c" -> state.windSession != null
                    else -> false
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title + "  ·  " + if (packed) "packed, ${humanSize(org.kdx.shell.droid.StoragePacker.packedSize(deb, p.id))}" else humanSize(current),
                            color = c.text, fontSize = 14.sp)
                        Text(if (inUse) "Close it first to pack it." else p.description, color = c.inactiveText, fontSize = 11.sp)
                    }
                    if (packed) TextButton({ job("Unpacking ${p.title}", "droidsystem-pack unpack ${p.id}") }, enabled = busy == null) { Text("Unpack") }
                    else TextButton({ job("Packing ${p.title}", "droidsystem-pack pack ${p.id}") }, enabled = busy == null && !inUse) { Text("Pack") }
                }
            }
        }
    }
}

/** System Settings → Linux System: installs and manages DroidSystem. */
@Composable
private fun LinuxSystemSection(state: KdxState) {
    val c = LocalBreeze.current
    var confirmUninstall by remember { mutableStateOf(false) }
    val ds = state.droid
    val stats by produceState<Triple<Long, Int, Int>?>(null, ds?.root, state.linuxApps) {
        value = ds?.let { withContext(Dispatchers.IO) { Triple(it.sizeOnDisk(), it.installed().size, it.flatpakInstalled().size) } }
    }
    Section("Linux System (DroidSystem)", Icons.Filled.Terminal) {
        Text(
            "DroidSystem 2 is KDX's Linux system, based on Debian. Turning it on builds Debian from official packages " +
                "(about 35 MB download) and runs it with proot, so real Linux software works: apt, Linux apps with windows " +
                "on the Linux Display, and Flatpak apps from Flathub. DroidSystem's own tools, Bazaar and dspkg are layered on top.",
            color = c.inactiveText, fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        val busy = state.droidProgress
        Toggle("Enable Linux system", state.droidEnabled && ds != null) { on ->
            if (on) state.enableDroid() else state.disableDroid()
        }
        if (busy != null) {
            Text("Installing DroidSystem: ${busy.second}", color = c.text, fontSize = 13.sp)
            LinearProgressIndicator(progress = { busy.first }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
        } else if (ds == null) {
            val target = state.droidTargetDir()
            KV("Install location", target.path)
            if (!state.hasStorageAccess()) Text(
                "Tip: grant KDX file access first (Dolphin → Grant) so DroidSystem can go in your phone's Android " +
                    "folder (Android/DroidSystem), where your PC can see it.",
                color = c.neutral, fontSize = 12.sp,
            )
        } else {
            KV("Version", ds.version)
            KV("Location", ds.root.path)
            stats?.let { (size, pkgs, fps) ->
                KV("Size", humanSize(size)); KV("Packages", "$pkgs (dspkg)"); KV("Flatpak apps", "$fps")
            }
            DebianBlock(state)
            Toggle("Flatpak support", state.flatpakEnabled) { state.flatpakEnabled = it; state.save(); state.refreshLinuxApps() }
            Toggle("Sound from Linux apps", state.linuxSound) { state.setSound(it) }
            Toggle("Always show the on-screen keyboard in Linux apps", state.linuxKeyboard) { state.changeLinuxKeyboard(it) }
            Spacer(Modifier.height(6.dp))
            Text("Linux app performance", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Linux apps draw on the CPU; fewer pixels means more frames per second. While Linux apps are open, " +
                "KDX runs as a foreground service so Android gives them full speed.", color = c.inactiveText, fontSize = 12.sp)
            org.kdx.shell.core.LinuxPerformance.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { state.setPerformance(p) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.linuxPerformance == p, { state.setPerformance(p) })
                    Column {
                        Text(p.label, color = c.text, fontSize = 14.sp)
                        Text(p.description, color = c.inactiveText, fontSize = 11.sp)
                    }
                }
            }
            Toggle("Open Linux file types in Dolphin (.deb .dsp .desktop .sh .flatpakref .tar.*)", state.linuxFileTypes) {
                state.linuxFileTypes = it; state.save()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ state.open(org.kdx.shell.core.KApp.KONSOLE) }, Modifier.weight(1f)) { Text("Terminal") }
                OutlinedButton({ state.open(org.kdx.shell.core.KApp.DOLPHIN, ds.root.path) }, Modifier.weight(1f)) { Text("Browse") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ state.installDroid(ds.root) }, Modifier.weight(1f)) { Text("Repair") }
                OutlinedButton({ confirmUninstall = true }, Modifier.weight(1f)) { Text("Uninstall", color = c.negative) }
            }
        }
    }
    if (confirmUninstall) AlertDialog(
        onDismissRequest = { confirmUninstall = false },
        title = { Text("Uninstall DroidSystem?") },
        text = { Text("This deletes ${ds?.root?.path} with all installed packages, Flatpak apps and files in its home folder.") },
        confirmButton = { TextButton({ confirmUninstall = false; state.uninstallDroid() }) { Text("Uninstall", color = c.negative) } },
        dismissButton = { TextButton({ confirmUninstall = false }) { Text("Cancel") } },
    )
}

/** DroidSystem 2: the Linux base (Ubuntu or Debian) – status and setup actions. */
@Composable
private fun DebianBlock(state: KdxState) {
    val c = LocalBreeze.current
    val deb = state.debian
    val prog = state.debianProgress
    Spacer(Modifier.height(6.dp))
    var confirmSwitch by remember { mutableStateOf<org.kdx.shell.droid.Distro?>(null) }
    Text("Linux base", color = c.text, fontWeight = FontWeight.SemiBold)
    when {
        prog != null -> {
            Text("Building DroidSystem 2: ${prog.second}", color = c.text, fontSize = 13.sp)
            LinearProgressIndicator(progress = { prog.first }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            state.debianLog.lastOrNull()?.let { Text(it, color = c.inactiveText, fontSize = 11.sp, maxLines = 1) }
            Text("Keep KDX open; configuring packages takes a few minutes on a phone.", color = c.inactiveText, fontSize = 12.sp)
        }
        deb != null -> {
            KV("Status", "Installed (${deb.distro.fullName}, ${deb.arch})")
            KV("Linux apps", if (deb.hasCommand("usr/bin/Xvnc")) "Display server ready" else "Not set up")
            KV("Flatpak", if (deb.hasCommand("usr/bin/flatpak")) "Ready (Flathub)" else "Not set up")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!deb.hasCommand("usr/bin/Xvnc")) OutlinedButton({ state.open(org.kdx.shell.core.KApp.KONSOLE, "display-setup") }, Modifier.weight(1f)) { Text("Set up Linux apps") }
                else OutlinedButton({ state.open(org.kdx.shell.core.KApp.LINUXDISPLAY) }, Modifier.weight(1f)) { Text("Linux Display") }
                if (!deb.hasCommand("usr/bin/flatpak")) OutlinedButton({ state.open(org.kdx.shell.core.KApp.KONSOLE, "flatpak-setup") }, Modifier.weight(1f)) { Text("Set up Flatpak") }
                else OutlinedButton({ state.open(org.kdx.shell.core.KApp.BAZAAR) }, Modifier.weight(1f)) { Text("Get apps") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                org.kdx.shell.droid.Distro.entries.filter { it != deb.distro }.forEach { d ->
                    OutlinedButton({ confirmSwitch = d }) { Text("Switch to ${d.label}") }
                }
                TextButton({ state.uninstallDebian() }) { Text("Remove", color = c.negative) }
            }
        }
        else -> {
            state.debianError?.let { e ->
                Text("Setup failed: $e", color = c.negative, fontSize = 13.sp, maxLines = 6)
                state.debianLog.takeLast(4).forEach { Text(it, color = c.inactiveText, fontSize = 11.sp, maxLines = 1) }
            } ?: Text("Not installed yet. Choose what it's built from:", color = c.inactiveText, fontSize = 13.sp)
            org.kdx.shell.droid.Distro.entries.forEach { d ->
                Row(Modifier.fillMaxWidth().clickable { state.chooseLinuxBase(d) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.linuxBase == d, { state.chooseLinuxBase(d) })
                    Column {
                        Text(d.fullName + if (d == org.kdx.shell.droid.Distro.UBUNTU) " (recommended)" else "", color = c.text, fontSize = 14.sp)
                        Text(if (d == org.kdx.shell.droid.Distro.UBUNTU) "Newest KDE Plasma and apps, Ubuntu's software"
                            else "Debian's stable release", color = c.inactiveText, fontSize = 11.sp)
                    }
                }
            }
            Button({ state.installDebian() }) { Text(if (state.debianError != null) "Retry" else "Install ${state.linuxBase.label} base") }
        }
    }
    confirmSwitch?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            title = { Text("Switch to ${d.fullName}?") },
            text = { Text("The Linux base is built again from ${d.label}. Everything installed in it is deleted: apt packages, " +
                "KDE Plasma, WindSystem's drive C: and Flatpak apps (install them again afterwards – with real KDE Plasma on, KDX " +
                "sets Plasma up again by itself). Your home folder and the WindSystem folder on your phone's storage are kept.") },
            confirmButton = { TextButton({ confirmSwitch = null; state.switchLinuxBase(d) }) { Text("Switch") } },
            dismissButton = { TextButton({ confirmSwitch = null }) { Text("Cancel") } },
        )
    }
}
