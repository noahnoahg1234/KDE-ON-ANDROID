package org.kdx.shell.apps

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.droid.LinuxDisplay
import org.kdx.shell.droid.RfbClient
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.hypot

/** Connection + framebuffer for one Linux Display window. */
class DisplaySession : Closeable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var rfb: RfbClient? = null
    var bitmap by mutableStateOf<Bitmap?>(null)
    var frame by mutableIntStateOf(0)
    var status by mutableStateOf("Starting…")
    var connected by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    /** On-screen keyboard for the app (on by default, see KdxState.linuxKeyboard). */
    var keyboard by mutableStateOf(true)
    /** Bumped on every tap in the app, to bring the keyboard back if Android hid it. */
    var tapCount by mutableIntStateOf(0)
    var showKeys by mutableStateOf(false)
    /** Touch mode: drag scrolls like a touchscreen. Mouse mode: drag = mouse drag. */
    var touchMode by mutableStateOf(true)
    var modifiers by mutableIntStateOf(0) // bit0 ctrl, bit1 alt, bit2 super (sticky)
    /** Where the screen was just touched (the mouse pointer is hidden, so taps get a visible mark instead). */
    val marks = androidx.compose.runtime.mutableStateListOf<TouchMark>()
    var markClock by mutableLongStateOf(0L)
    var onClose: () -> Unit = {}
    /** Frames per second over the last measurement (shown in the toolbar). */
    var fps by mutableIntStateOf(0)
    /** Auto performance mode: this window's resolution factor (1 = full), lowered when it can't reach 60 fps. */
    var autoScale by mutableFloatStateOf(1f)

    /** True once the app has shown a window (until then the window says it's starting). */
    var appReady by mutableStateOf(false)
    /** Set when the app stopped unexpectedly: exit code and its last log lines. */
    var crash by mutableStateOf<Pair<Int, List<String>>?>(null)
    var crashRanMs = 0L

    fun connect(port: Int) {
        if (connected) return
        scope.launch {
            status = "Starting…"
            val client = RfbClient(port = port)
            val ok = withContext(Dispatchers.IO) {
                var err: Throwable? = null
                repeat(60) { runCatching { client.connect(); return@withContext true }.onFailure { err = it }; delay(300) }
                status = "Can't connect to the display: ${err?.message}"
                false
            }
            if (!ok) return@launch
            rfb = client
            // Double buffering: the screen shows `front` while the reader thread copies the next frame into the
            // other bitmap (one native memcpy of the RGBA framebuffer), then they swap – no tearing, no per-pixel work.
            val pair = java.util.concurrent.atomic.AtomicReference(
                Bitmap.createBitmap(client.width, client.height, Bitmap.Config.ARGB_8888) to
                    Bitmap.createBitmap(client.width, client.height, Bitmap.Config.ARGB_8888))
            val front = java.util.concurrent.atomic.AtomicReference(pair.get().first)
            val pending = java.util.concurrent.atomic.AtomicBoolean(false)
            bitmap = front.get()
            connected = true; status = ""
            client.onResize = { w, h ->
                val a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                pair.set(a to Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)); front.set(a)
                scope.launch { bitmap = a }
            }
            client.onFrame = {
                val (a, b) = pair.get()
                val back = if (front.get() === a) b else a
                val buf = client.buffer
                if (back.width == client.width && back.height == client.height && buf.size == back.width * back.height * 4) {
                    back.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(buf))
                    if (pending.compareAndSet(false, true)) {
                        scope.launch { front.set(back); bitmap = back; pending.set(false); frame++ }
                    }
                }
            }
            withContext(Dispatchers.IO) { runCatching { client.loop() } }
            connected = false; status = "Display closed"
        }
    }

    override fun close() { rfb?.close(); scope.cancel(); onClose() }
}

data class TouchMark(val pos: Offset, val kind: TouchTranslator.Kind, val at: Long)

private const val M_CTRL = 1
private const val M_ALT = 2
private const val M_SUPER = 4

/**
 * Linux Display – shows an X display. With arg "app:N" it is one Linux/Flatpak app's own window: it takes the app's
 * title, resizes the X screen to fit, and closes when the app exits. Without an arg it's the shared Linux desktop.
 */
@Composable
fun LinuxDisplayApp(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val appNo = w.arg?.takeIf { it.startsWith("app:") }?.removePrefix("app:")?.toIntOrNull()
    val disp: LinuxDisplay? = if (appNo != null) state.appDisplay(appNo) else state.linuxDisplay
    val s = remember(w) {
        w.retain("display") {
            DisplaySession().also { ds ->
                ds.keyboard = state.linuxKeyboard
                if (appNo != null) ds.onClose = { state.releaseDisplay(appNo) }
            }
        }
    }
    if (disp == null) {
        Box(Modifier.fillMaxSize().background(c.window).padding(24.dp), Alignment.Center) {
            Text(if (appNo != null) "This app has stopped." else "Linux apps need DroidSystem 2 (Debian). Turn on the Linux system in System Settings.",
                color = c.inactiveText)
        }
        return
    }
    if (!disp.isSetUp) {
        Column(Modifier.fillMaxSize().background(c.window).padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Icon(Icons.Filled.DesktopWindows, null, tint = c.accent, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("Set up the display for Linux apps", color = c.text, fontSize = 18.sp)
            Text("Installs the TigerVNC X server and Openbox window manager from Debian (about 60 MB).",
                color = c.inactiveText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Button({ state.open(KApp.KONSOLE, "display-setup") }) { Text("Install display server") }
        }
        return
    }
    val appName = remember(w) { w.title }
    LaunchedEffect(disp) {
        if (appNo == null && !disp.isRunning()) withContext(Dispatchers.IO) { runCatching { disp.start(1280, 800) }.onFailure { s.status = it.message ?: "Display failed" } }
        s.connect(disp.port)
    }
    if (appNo != null) {
        // The KDX window follows the app: it takes its window title, and closes when the app quits normally.
        // If the app stops right away or with an error, keep the window open and show why.
        LaunchedEffect(disp, s.crash) {
            if (s.crash != null) return@LaunchedEffect
            while (true) {
                delay(1500)
                val p = disp.appProcess ?: continue
                if (!p.isAlive) {
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    val ran = System.currentTimeMillis() - disp.appStartedAt
                    disp.logToFile("==== $appName exited with code $code after ${ran / 1000} s ====")
                    delay(800) // let the last output lines arrive before showing them
                    val lines = disp.appLog().takeLast(120).ifEmpty {
                        disp.logFile?.let { f -> withContext(Dispatchers.IO) { runCatching { f.readLines().takeLast(120) }.getOrNull() } } ?: emptyList()
                    }
                    if (ran < 20_000 || code !in setOf(0, 130, 143)) { s.crash = code to lines; s.crashRanMs = ran }
                    else { state.toast("$appName closed"); state.close(w) }
                    break
                }
                val t = withContext(Dispatchers.IO) { disp.windowTitle() }
                if (!t.isNullOrBlank()) s.appReady = true
                else if (!disp.sys.hasCommand("usr/bin/xdotool") && System.currentTimeMillis() - disp.appStartedAt > 8000) s.appReady = true
                w.title = t?.takeIf { it.isNotBlank() } ?: appName
            }
        }
        s.crash?.let { (code, lines) ->
            CrashPanel(state, appName, code, lines, s.crashRanMs, disp.logFile,
                restart = {
                    s.crash = null
                    s.scope.launch(Dispatchers.IO) { disp.relaunch?.let { disp.appProcess = it() } }
                },
                close = { state.close(w) })
            return
        }
    }
    // Frame rate + Auto mode: while the app animates (frames keep coming) but stays under ~50 fps, step its
    // resolution down; once it holds 60 fps with room to spare, step back up. Idle apps send few frames and are
    // left alone. Hysteresis (several readings in a row) avoids flip-flopping.
    LaunchedEffect(s.connected) {
        val scaler = org.kdx.shell.droid.AutoScaler(s.autoScale)
        while (s.connected) {
            val f0 = s.rfb?.frames ?: 0L
            delay(2000)
            val fps = (((s.rfb?.frames ?: 0L) - f0) / 2).toInt()
            s.fps = fps
            if (appNo == null || disp.desktop || state.linuxPerformance != org.kdx.shell.core.LinuxPerformance.AUTO) continue
            if (scaler.update(fps)) s.autoScale = scaler.scale
        }
    }
    val focus = remember { FocusRequester() }
    val ime = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Keep the on-screen keyboard up while this Linux app window is the active one: when it becomes active, when
    // it connects, and after every tap inside the app (Android sometimes hides the keyboard on its own).
    val isActive = state.activeId == w.id
    LaunchedEffect(isActive, s.keyboard, s.connected, s.tapCount) {
        if (isActive && s.keyboard && s.connected) {
            delay(120)
            runCatching { focus.requestFocus() }
            ime?.show()
        }
    }
    var typed by remember { mutableStateOf(TextFieldValue(" ")) }
    val density = LocalDensity.current.density

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // toolbar
        Row(Modifier.fillMaxWidth().background(c.header).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({
                s.keyboard = !s.keyboard
                if (s.keyboard) { runCatching { focus.requestFocus() }; ime?.show() } else ime?.hide()
            }, Modifier.size(40.dp)) {
                Icon(Icons.Filled.Keyboard, "Keyboard", tint = if (s.keyboard) c.accent else c.text)
            }
            IconButton({ s.touchMode = !s.touchMode }, Modifier.size(40.dp)) {
                Icon(if (s.touchMode) Icons.Filled.TouchApp else Icons.Filled.Mouse, if (s.touchMode) "Touch mode" else "Mouse mode", tint = c.text)
            }
            IconButton({ s.showKeys = !s.showKeys }, Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardCommandKey, "Special keys", tint = if (s.showKeys) c.accent else c.text)
            }
            disp.logFile?.let { f ->
                IconButton({ state.open(KApp.KATE, f.path) }, Modifier.size(40.dp)) { Icon(Icons.Filled.Article, "App log", tint = c.text) }
            }
            Text(if (s.touchMode) "Tap = click · drag = scroll · hold = right-click · hold+drag = drag · 2 fingers: tap = right-click, pinch = zoom · 3-finger tap = paste"
                else "Mouse mode: drag = drag · hold = right-click · 2-finger tap = right-click",
                Modifier.weight(1f).padding(horizontal = 4.dp), color = c.inactiveText, fontSize = 11.sp, maxLines = 1)
            if (s.zoom > 1.01f) IconButton({ s.zoom = 1f; s.pan = Offset.Zero }, Modifier.size(40.dp)) { Icon(Icons.Filled.FitScreen, "Fit") }
            if (s.connected) Text(
                "${s.fps} fps" + if (appNo != null && state.linuxPerformance == org.kdx.shell.core.LinuxPerformance.AUTO && s.autoScale < 0.99f)
                    " · ${(s.autoScale * 100).toInt()}%" else "",
                Modifier.padding(horizontal = 6.dp), color = if (s.fps >= 55 || s.fps < 12) c.inactiveText else c.neutral, fontSize = 11.sp,
            )
        }
        if (s.showKeys) LazyRow(Modifier.fillMaxWidth().background(c.header), horizontalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            val keys = listOf("Esc" to RfbClient.KEY_ESCAPE, "Tab" to RfbClient.KEY_TAB, "←" to RfbClient.KEY_LEFT, "↑" to RfbClient.KEY_UP,
                "↓" to RfbClient.KEY_DOWN, "→" to RfbClient.KEY_RIGHT, "Enter" to RfbClient.KEY_RETURN, "⌫" to RfbClient.KEY_BACKSPACE,
                "Del" to RfbClient.KEY_DELETE, "Home" to RfbClient.KEY_HOME, "End" to RfbClient.KEY_END)
            items(listOf("Ctrl" to M_CTRL, "Alt" to M_ALT, "Super" to M_SUPER)) { (label, bit) ->
                FilterChip(s.modifiers and bit != 0, { s.modifiers = s.modifiers xor bit }, label = { Text(label, fontSize = 12.sp) })
            }
            items(keys) { (label, sym) -> AssistChip({ sendKey(s, sym) }, label = { Text(label, fontSize = 12.sp) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val bmp = s.bitmap
            var size by remember { mutableStateOf(IntSize.Zero) }
            // app windows: make the X screen match this window's shape (debounced)
            if (appNo != null) LaunchedEffect(size, s.connected, s.autoScale, state.linuxPerformance, state.plasmaResolution) {
                if (!s.connected || size.width == 0) return@LaunchedEffect
                delay(500)
                if (disp.desktop) { // KDE Plasma: its own resolution setting (phone's resolution = 1:1 pixels)
                    val (xw, xh) = state.plasmaGeometry(size.width, size.height)
                    s.rfb?.setDesktopSize(xw, xh)
                    return@LaunchedEffect
                }
                val wDp = size.width / density; val hDp = size.height / density
                val f = state.xScale(wDp) * (if (state.linuxPerformance == org.kdx.shell.core.LinuxPerformance.AUTO) s.autoScale else 1f)
                s.rfb?.setDesktopSize(((wDp * f).toInt() / 8 * 8).coerceIn(320, 3840), (hDp * f).toInt().coerceIn(240, 2160))
            }
            if (bmp == null || !s.connected) {
                Column(Modifier.align(Alignment.Center).onSizeChanged { size = it }, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!s.status.startsWith("Can't") && s.status != "Display closed") CircularProgressIndicator()
                    Text(s.status, color = Color.White, modifier = Modifier.padding(12.dp))
                    if (s.status.startsWith("Can't") || s.status == "Display closed") Button({ s.connect(disp.port) }) { Text("Reconnect") }
                }
            } else {
                val fit = if (size.width == 0) 1f else minOf(size.width.toFloat() / bmp.width, size.height.toFloat() / bmp.height)
                val scale = fit * s.zoom
                val origin = Offset((size.width - bmp.width * scale) / 2f, (size.height - bmp.height * scale) / 2f) + s.pan
                fun toFb(p: Offset) = ((p.x - origin.x) / scale).toInt() to ((p.y - origin.y) / scale).toInt()
                val view = rememberUpdatedState(Triple(origin, scale, fit))
                val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                val touch = remember(s) {
                    TouchTranslator(
                        slopPx = 8f * density,
                        toFb = { p -> val (o, sc, _) = view.value; ((p.x - o.x) / sc).toInt() to ((p.y - o.y) / sc).toInt() },
                        out = { x, y, m -> s.rfb?.pointer(x, y, m) },
                        // an X app scrolls roughly this many of its pixels per wheel step: the content then follows the finger
                        scrollStepPx = { 56f * view.value.second },
                        touchMode = { s.touchMode },
                        zoomed = { s.zoom > 1.01f },
                        onPinch = { f, c ->
                            // zoom around the fingers: the X pixel under them stays under them
                            val (o, sc, ft) = view.value
                            val fbX = (c.x - o.x) / sc; val fbY = (c.y - o.y) / sc
                            val z = (s.zoom * f).coerceIn(1f, 5f)
                            val sc2 = ft * z
                            val bw = s.bitmap?.width ?: 1; val bh = s.bitmap?.height ?: 1
                            val base = Offset((size.width - bw * sc2) / 2f, (size.height - bh * sc2) / 2f)
                            s.zoom = z
                            s.pan = if (z <= 1.01f) Offset.Zero else Offset(c.x - fbX * sc2, c.y - fbY * sc2) - base
                        },
                        onPan = { d -> s.pan += d },
                        haptic = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                        mark = { p, k -> s.marks += TouchMark(p, k, android.os.SystemClock.uptimeMillis()) },
                    )
                }
                // long press, fling and fading touch marks run on the frame clock
                LaunchedEffect(touch) {
                    while (true) {
                        withFrameMillis { }
                        val now = android.os.SystemClock.uptimeMillis()
                        touch.tick(now)
                        if (s.marks.isNotEmpty()) { s.marks.removeAll { now - it.at > 400 }; s.markClock = now }
                    }
                }
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { size = it }
                        .pointerInput(s) { touchInput(s, touch) }
                ) {
                    s.frame // redraw on every frame
                    drawContext.canvas.save()
                    drawContext.canvas.translate(origin.x, origin.y)
                    drawContext.canvas.scale(scale, scale)
                    drawImage(bmp.asImageBitmap(), filterQuality = if (scale < 1f) FilterQuality.Medium else FilterQuality.Low)
                    drawContext.canvas.restore()
                    // touch marks: a ring that grows and fades (tap white, right-click orange, middle green, hold blue)
                    val now = s.markClock
                    s.marks.forEach { m ->
                        val age = ((now - m.at) / 350f).coerceIn(0f, 1f)
                        val col = when (m.kind) { TouchTranslator.Kind.TAP -> Color.White; TouchTranslator.Kind.RIGHT -> Color(0xFFF67400)
                            TouchTranslator.Kind.MIDDLE -> Color(0xFF27AE60); TouchTranslator.Kind.HOLD -> Color(0xFF3DAEE9) }
                        drawCircle(col.copy(alpha = 0.55f * (1 - age)), radius = (14f + 16f * age) * density, center = m.pos,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f * density))
                        drawCircle(col.copy(alpha = 0.25f * (1 - age)), radius = 10f * density, center = m.pos)
                    }
                }
            }
            if (appNo != null && s.connected && !s.appReady) {
                Column(Modifier.align(Alignment.Center).background(Color(0xCC232627), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("Starting $appName…", color = Color.White, fontSize = 15.sp)
                    Text("The first start can take a minute while it sets up.", color = Color(0xFFBDC3C7), fontSize = 12.sp)
                }
            }
            // hidden text field that collects on-screen keyboard input
            if (s.keyboard) BasicTextField(
                value = typed,
                onValueChange = { v ->
                    val old = typed.text
                    when {
                        v.text.length < old.length -> repeat(old.length - v.text.length) { sendKey(s, RfbClient.KEY_BACKSPACE) }
                        v.text.startsWith(old) -> typeText(s, v.text.substring(old.length))
                        else -> typeText(s, v.text.trim())
                    }
                    typed = TextFieldValue(" ", selection = androidx.compose.ui.text.TextRange(1))
                },
                modifier = Modifier.size(1.dp).focusRequester(focus).onPreviewKeyEvent { e -> hardwareKey(s, e) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.None),
            )
        }
    }
}

/** Feeds touches (and a real mouse) into the [TouchTranslator]. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.touchInput(s: DisplaySession, t: TouchTranslator) = awaitPointerEventScope {
    var mouseButtons = 0
    while (true) {
        val ev = awaitPointerEvent()
        val first = ev.changes.firstOrNull() ?: continue
        if (first.type == androidx.compose.ui.input.pointer.PointerType.Mouse) {
            // a real mouse (USB/Bluetooth/DeX): straight through, with hover, all buttons and the wheel
            val rfb = s.rfb ?: continue
            val (x, y) = t.mapForMouse(first.position)
            if (ev.type == androidx.compose.ui.input.pointer.PointerEventType.Scroll) {
                val d = first.scrollDelta
                val btn = when { d.y > 0 -> 16; d.y < 0 -> 8; d.x > 0 -> 64; d.x < 0 -> 32; else -> 0 }
                if (btn != 0) { rfb.pointer(x, y, mouseButtons or btn); rfb.pointer(x, y, mouseButtons) }
            } else {
                val b = ev.buttons
                mouseButtons = (if (b.isPrimaryPressed) 1 else 0) or (if (b.isTertiaryPressed) 2 else 0) or (if (b.isSecondaryPressed) 4 else 0)
                rfb.pointer(x, y, mouseButtons)
                if (ev.type == androidx.compose.ui.input.pointer.PointerEventType.Press) s.tapCount++
            }
            ev.changes.forEach { it.consume() }
            continue
        }
        for (c in ev.changes) {
            when {
                c.changedToDown() -> { t.down(c.id.value, c.position, c.uptimeMillis); s.tapCount++ }
                c.changedToUp() -> t.up(c.id.value, c.position, c.uptimeMillis)
                c.pressed && c.positionChanged() -> t.move(c.id.value, c.position, c.uptimeMillis)
            }
            c.consume()
        }
    }
}

private fun withModifiers(s: DisplaySession, block: (RfbClient) -> Unit) {
    val rfb = s.rfb ?: return
    val mods = listOfNotNull(
        RfbClient.KEY_CTRL.takeIf { s.modifiers and M_CTRL != 0 },
        RfbClient.KEY_ALT.takeIf { s.modifiers and M_ALT != 0 },
        RfbClient.KEY_SUPER.takeIf { s.modifiers and M_SUPER != 0 },
    )
    mods.forEach { rfb.key(it, true) }
    block(rfb)
    mods.reversed().forEach { rfb.key(it, false) }
    s.modifiers = 0 // sticky modifiers apply to one key
}

private fun sendKey(s: DisplaySession, sym: Int) = withModifiers(s) { it.tapKey(sym) }
private fun typeText(s: DisplaySession, t: String) { if (t.isNotEmpty()) withModifiers(s) { it.type(t) } }

/** Hardware keyboard → X keysyms. */
private fun hardwareKey(s: DisplaySession, e: KeyEvent): Boolean {
    val rfb = s.rfb ?: return false
    val sym = when (e.key) {
        Key.Enter, Key.NumPadEnter -> RfbClient.KEY_RETURN
        Key.Backspace -> RfbClient.KEY_BACKSPACE
        Key.Tab -> RfbClient.KEY_TAB
        Key.Escape -> RfbClient.KEY_ESCAPE
        Key.DirectionLeft -> RfbClient.KEY_LEFT
        Key.DirectionRight -> RfbClient.KEY_RIGHT
        Key.DirectionUp -> RfbClient.KEY_UP
        Key.DirectionDown -> RfbClient.KEY_DOWN
        Key.MoveHome -> RfbClient.KEY_HOME
        Key.MoveEnd -> RfbClient.KEY_END
        Key.PageUp -> RfbClient.KEY_PAGE_UP
        Key.PageDown -> RfbClient.KEY_PAGE_DOWN
        Key.Delete -> RfbClient.KEY_DELETE
        Key.CtrlLeft, Key.CtrlRight -> RfbClient.KEY_CTRL
        Key.AltLeft, Key.AltRight -> RfbClient.KEY_ALT
        Key.ShiftLeft, Key.ShiftRight -> RfbClient.KEY_SHIFT
        Key.MetaLeft, Key.MetaRight -> RfbClient.KEY_SUPER
        else -> {
            val cp = e.utf16CodePoint
            if (cp > 0 && (e.isCtrlPressed || e.isAltPressed)) RfbClient.keysymFor(cp.toChar().lowercaseChar()) else return false
        }
    }
    rfb.key(sym, e.type == KeyEventType.KeyDown)
    return true
}

/** Shown instead of silently closing when a Linux app stops right away or with an error. */
@Composable
private fun CrashPanel(
    state: KdxState, app: String, code: Int, log: List<String>, ranMs: Long, logFile: java.io.File?,
    restart: () -> Unit, close: () -> Unit,
) {
    val c = LocalBreeze.current
    // proot reports "killed by signal N" as 128+N
    val why = when (code) {
        137, -9 -> "Android ended it – usually because the phone ran low on memory. Close other apps and try again."
        159 -> "It used a system call Android doesn't allow apps to make (SIGSYS)."
        139 -> "It crashed (segmentation fault)."
        134 -> "It aborted (SIGABRT) – see the log below."
        0 -> "It exited by itself right after starting."
        else -> "It exited with code $code – see the log below."
    }
    Column(Modifier.fillMaxSize().background(c.window).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ErrorOutline, null, tint = c.negative, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Text("$app stopped", color = c.text, fontSize = 20.sp)
        }
        Text(why, color = c.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Text("Exit code $code after ${ranMs / 1000} s." + if (log.none { it.substringAfter(": ", it).isNotBlank() && !it.startsWith("====") })
            " It printed nothing at all, which means it was stopped from outside (by Android) before it could report anything." else "",
            color = c.inactiveText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        logFile?.let { Text("Full log saved to ${it.path}", color = c.inactiveText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(restart) { Text("Restart") }
            OutlinedButton({
                val text = logFile?.let { runCatching { it.readText() }.getOrNull() } ?: ("$app exited with code $code\n" + log.joinToString("\n"))
                state.copyText(text.takeLast(60_000)); state.toast("Log copied")
            }) { Text("Copy log") }
            logFile?.let { f -> OutlinedButton({ state.open(KApp.KATE, f.path) }) { Text("Open") } }
            OutlinedButton(close) { Text("Close") }
        }
        Spacer(Modifier.height(10.dp))
        Text("Log", color = c.inactiveText, fontSize = 12.sp)
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxWidth().weight(1f).background(Color(0xFF232627)).padding(6.dp),
            reverseLayout = true,
        ) {
            items(log.reversed()) { l ->
                Text(l.substringAfter(": ", l), color = Color(0xFFEFF0F1), fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            if (log.isEmpty()) item { Text("(the app printed nothing)", color = Color(0xFF9DA7B3), fontSize = 11.sp) }
        }
    }
}
