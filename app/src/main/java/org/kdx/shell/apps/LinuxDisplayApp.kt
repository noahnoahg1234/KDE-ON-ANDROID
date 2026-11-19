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
    var keyboard by mutableStateOf(false)
    var showKeys by mutableStateOf(false)
    /** Touch mode: drag scrolls like a touchscreen. Mouse mode: drag = mouse drag. */
    var touchMode by mutableStateOf(true)
    var modifiers by mutableIntStateOf(0) // bit0 ctrl, bit1 alt, bit2 super (sticky)
    var onClose: () -> Unit = {}
    /** True once the app has shown a window (until then the window says it's starting). */
    var appReady by mutableStateOf(false)
    /** Set when the app stopped unexpectedly: exit code and its last log lines. */
    var crash by mutableStateOf<Pair<Int, List<String>>?>(null)

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
            bitmap = Bitmap.createBitmap(client.width, client.height, Bitmap.Config.ARGB_8888)
            connected = true; status = ""
            var pending = false
            client.onResize = { w, h -> scope.launch { bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } }
            client.onFrame = {
                // copy on the reader thread, then let Compose redraw (coalesced)
                bitmap?.takeIf { it.width == client.width && it.height == client.height }
                    ?.setPixels(client.pixels, 0, client.width, 0, 0, client.width, client.height)
                if (!pending) { pending = true; scope.launch { pending = false; frame++ } }
            }
            withContext(Dispatchers.IO) { runCatching { client.loop() } }
            connected = false; status = "Display closed"
        }
    }

    override fun close() { rfb?.close(); scope.cancel(); onClose() }
}

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
        w.retain("display") { DisplaySession().also { ds -> if (appNo != null) ds.onClose = { state.releaseDisplay(appNo) } } }
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
                    if (ran < 20_000 || code !in setOf(0, 130, 143)) { s.crash = code to disp.appLog().takeLast(80) }
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
            CrashPanel(state, appName, code, lines,
                restart = {
                    s.crash = null
                    s.scope.launch(Dispatchers.IO) { disp.relaunch?.let { disp.appProcess = it() } }
                },
                close = { state.close(w) })
            return
        }
    }
    val focus = remember { FocusRequester() }
    var typed by remember { mutableStateOf(TextFieldValue(" ")) }
    val density = LocalDensity.current.density

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // toolbar
        Row(Modifier.fillMaxWidth().background(c.header).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ s.keyboard = !s.keyboard; if (s.keyboard) runCatching { focus.requestFocus() } }, Modifier.size(40.dp)) {
                Icon(Icons.Filled.Keyboard, "Keyboard", tint = if (s.keyboard) c.accent else c.text)
            }
            IconButton({ s.touchMode = !s.touchMode }, Modifier.size(40.dp)) {
                Icon(if (s.touchMode) Icons.Filled.TouchApp else Icons.Filled.Mouse, if (s.touchMode) "Touch mode" else "Mouse mode", tint = c.text)
            }
            IconButton({ s.showKeys = !s.showKeys }, Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardCommandKey, "Special keys", tint = if (s.showKeys) c.accent else c.text)
            }
            Text(if (s.touchMode) "Touch: tap, drag to scroll, hold for right-click, hold+drag to drag" else "Mouse: drag = drag, hold = right-click",
                Modifier.weight(1f).padding(horizontal = 4.dp), color = c.inactiveText, fontSize = 11.sp, maxLines = 1)
            if (s.zoom > 1.01f) IconButton({ s.zoom = 1f; s.pan = Offset.Zero }, Modifier.size(40.dp)) { Icon(Icons.Filled.FitScreen, "Fit") }
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
            if (appNo != null) LaunchedEffect(size, s.connected) {
                if (!s.connected || size.width == 0) return@LaunchedEffect
                delay(500)
                val wDp = size.width / density; val hDp = size.height / density
                val f = state.xScale(wDp)
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
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { size = it }
                        .pointerInput(bmp, s.touchMode) { awaitEachGesture { gesture(s, ::toFb) } }
                ) {
                    s.frame // redraw on every frame
                    drawContext.canvas.save()
                    drawContext.canvas.translate(origin.x, origin.y)
                    drawContext.canvas.scale(scale, scale)
                    drawImage(bmp.asImageBitmap(), filterQuality = if (scale < 1f) FilterQuality.Medium else FilterQuality.Low)
                    drawContext.canvas.restore()
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

/** One touch gesture → X pointer events. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.gesture(s: DisplaySession, toFb: (Offset) -> Pair<Int, Int>) {
    val down = awaitFirstDown()
    val rfb = s.rfb ?: return
    val start = down.position
    val t0 = System.currentTimeMillis()
    var moved = false
    var held = false      // long-press reached before moving
    var dragging = false  // button 1 held down
    var multi = false
    var lastDist = 0f
    var lastCentroid = Offset.Zero
    var lastScroll = start
    var (fx, fy) = toFb(start)
    rfb.pointer(fx, fy, 0)
    while (true) {
        val ev = withTimeoutOrNull(60) { awaitPointerEvent() }
        if (ev == null) {
            if (!moved && !held && System.currentTimeMillis() - t0 > 500) held = true
            continue
        }
        val pressed = ev.changes.filter { it.pressed }
        if (pressed.size >= 2) {
            // two fingers: pinch to zoom, pan when zoomed, otherwise scroll
            val a = pressed[0].position; val b = pressed[1].position
            val dist = hypot(a.x - b.x, a.y - b.y); val cen = (a + b) / 2f
            if (multi) {
                if (lastDist > 0) s.zoom = (s.zoom * dist / lastDist).coerceIn(1f, 5f)
                if (s.zoom > 1.01f) { s.pan += cen - lastCentroid; lastCentroid = cen }
                else if (abs(cen.y - lastCentroid.y) > 30) {
                    val (sx, sy) = toFb(cen); val btn = if (cen.y < lastCentroid.y) 16 else 8
                    rfb.pointer(sx, sy, btn); rfb.pointer(sx, sy, 0); lastCentroid = cen
                }
            } else lastCentroid = cen
            if (dragging) { rfb.pointer(fx, fy, 0); dragging = false }
            multi = true; lastDist = dist
            ev.changes.forEach { it.consume() }
            continue
        }
        val ch = ev.changes.firstOrNull() ?: break
        if (!ch.pressed) {
            when {
                multi -> {}
                dragging -> rfb.pointer(fx, fy, 0)
                !moved -> {
                    val right = held || System.currentTimeMillis() - t0 > 500
                    rfb.pointer(fx, fy, if (right) 4 else 1); rfb.pointer(fx, fy, 0)
                }
            }
            break
        }
        if (multi || !ch.positionChanged()) continue
        val dist = (ch.position - start).getDistance()
        if (!moved && dist > 14f) {
            moved = true
            if (!s.touchMode || held) { dragging = true; rfb.pointer(fx, fy, 1) } // mouse mode, or hold-then-drag
        }
        if (dragging) {
            toFb(ch.position).let { fx = it.first; fy = it.second }; rfb.pointer(fx, fy, 1)
        } else if (moved) {
            // touch scrolling: finger up = content moves up = wheel down (natural scrolling)
            val dy = ch.position.y - lastScroll.y; val dx = ch.position.x - lastScroll.x
            val step = 40f
            if (abs(dy) >= step) {
                val btn = if (dy < 0) 16 else 8
                repeat((abs(dy) / step).toInt().coerceAtMost(5)) { rfb.pointer(fx, fy, btn); rfb.pointer(fx, fy, 0) }
                lastScroll = Offset(lastScroll.x, ch.position.y)
            }
            if (abs(dx) >= step * 1.5f) {
                val btn = if (dx < 0) 64 else 32 // buttons 7/6 = horizontal wheel
                rfb.pointer(fx, fy, btn); rfb.pointer(fx, fy, 0)
                lastScroll = Offset(ch.position.x, lastScroll.y)
            }
        }
        ch.consume()
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
private fun CrashPanel(state: KdxState, app: String, code: Int, log: List<String>, restart: () -> Unit, close: () -> Unit) {
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
        Text(why, color = c.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(restart) { Text("Restart") }
            OutlinedButton({ state.copyText("$app exited with code $code\n" + log.joinToString("\n")); state.toast("Log copied") }) { Text("Copy log") }
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
