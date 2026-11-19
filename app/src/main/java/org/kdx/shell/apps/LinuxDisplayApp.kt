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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
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
    var status by mutableStateOf("Connecting…")
    var connected by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var keyboard by mutableStateOf(false)
    var modifiers by mutableIntStateOf(0) // bit0 ctrl, bit1 alt, bit2 super (sticky)

    fun connect(port: Int) {
        if (connected) return
        scope.launch {
            status = "Connecting…"
            val client = RfbClient(port = port)
            val ok = withContext(Dispatchers.IO) {
                var err: Throwable? = null
                repeat(30) { runCatching { client.connect(); return@withContext true }.onFailure { err = it }; delay(300) }
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

    override fun close() { rfb?.close(); scope.cancel() }
}

private const val M_CTRL = 1
private const val M_ALT = 2
private const val M_SUPER = 4

/** Linux Display – shows DroidSystem's X display; touch becomes mouse input. */
@Composable
fun LinuxDisplayApp(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val disp = state.linuxDisplay
    val s = remember(w) { w.retain("display") { DisplaySession() } }
    if (disp == null) {
        Box(Modifier.fillMaxSize().background(c.window).padding(24.dp), Alignment.Center) {
            Text("Linux apps need DroidSystem 2 (Debian). Turn on the Linux system in System Settings.", color = c.inactiveText)
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
    LaunchedEffect(Unit) {
        if (!disp.isRunning()) withContext(Dispatchers.IO) { runCatching { disp.start(1280, 800) }.onFailure { s.status = it.message ?: "Display failed" } }
        s.connect(disp.port)
    }
    val focus = remember { FocusRequester() }
    var typed by remember { mutableStateOf(TextFieldValue(" ")) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // toolbar
        Row(Modifier.fillMaxWidth().background(c.header).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ s.keyboard = !s.keyboard; if (s.keyboard) runCatching { focus.requestFocus() } }) {
                Icon(Icons.Filled.Keyboard, "Keyboard", tint = if (s.keyboard) c.accent else c.text)
            }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val keys = listOf("Esc" to RfbClient.KEY_ESCAPE, "Tab" to RfbClient.KEY_TAB, "←" to RfbClient.KEY_LEFT, "↑" to RfbClient.KEY_UP,
                    "↓" to RfbClient.KEY_DOWN, "→" to RfbClient.KEY_RIGHT, "Enter" to RfbClient.KEY_RETURN, "⌫" to RfbClient.KEY_BACKSPACE)
                items(listOf("Ctrl" to M_CTRL, "Alt" to M_ALT, "Super" to M_SUPER)) { (label, bit) ->
                    FilterChip(s.modifiers and bit != 0, { s.modifiers = s.modifiers xor bit }, label = { Text(label, fontSize = 12.sp) })
                }
                items(keys) { (label, sym) ->
                    AssistChip({ sendKey(s, sym) }, label = { Text(label, fontSize = 12.sp) })
                }
            }
            IconButton({ s.zoom = 1f; s.pan = Offset.Zero }) { Icon(Icons.Filled.FitScreen, "Fit") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val bmp = s.bitmap
            if (bmp == null || !s.connected) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (s.status.startsWith("Connecting")) CircularProgressIndicator()
                    Text(s.status, color = Color.White, modifier = Modifier.padding(12.dp))
                    if (!s.connected && !s.status.startsWith("Connecting")) Button({ s.connect(disp.port) }) { Text("Reconnect") }
                }
            } else {
                var size by remember { mutableStateOf(IntSize.Zero) }
                // fit framebuffer into the window, then apply pinch zoom / pan
                val fit = if (size.width == 0) 1f else minOf(size.width.toFloat() / bmp.width, size.height.toFloat() / bmp.height)
                val scale = fit * s.zoom
                val origin = Offset((size.width - bmp.width * scale) / 2f, (size.height - bmp.height * scale) / 2f) + s.pan
                fun toFb(p: Offset) = ((p.x - origin.x) / scale).toInt() to ((p.y - origin.y) / scale).toInt()
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { size = it }
                        .pointerInput(bmp) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val rfb = s.rfb ?: return@awaitEachGesture
                                val start = down.position
                                val t0 = System.currentTimeMillis()
                                var dragging = false
                                var multi = false
                                var lastDist = 0f
                                var lastCentroid = Offset.Zero
                                var (fx, fy) = toFb(start)
                                rfb.pointer(fx, fy, 0)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        // two fingers: pinch zoom + pan (or scroll when not zoomed)
                                        val a = pressed[0].position; val b = pressed[1].position
                                        val dist = hypot(a.x - b.x, a.y - b.y); val cen = (a + b) / 2f
                                        if (multi) {
                                            if (lastDist > 0) s.zoom = (s.zoom * dist / lastDist).coerceIn(1f, 5f)
                                            if (s.zoom > 1.01f) s.pan += cen - lastCentroid
                                            else if (abs(cen.y - lastCentroid.y) > 18) {
                                                val (sx, sy) = toFb(cen)
                                                val btn = if (cen.y < lastCentroid.y) 16 else 8
                                                rfb.pointer(sx, sy, btn); rfb.pointer(sx, sy, 0)
                                            }
                                        }
                                        if (!multi || abs(cen.y - lastCentroid.y) > 18) lastCentroid = cen
                                        multi = true; lastDist = dist
                                        ev.changes.forEach { it.consume() }
                                        continue
                                    }
                                    val ch = ev.changes.firstOrNull() ?: break
                                    if (!ch.pressed) {
                                        if (!multi && !dragging) {
                                            val right = System.currentTimeMillis() - t0 > 550
                                            rfb.pointer(fx, fy, if (right) 4 else 1); rfb.pointer(fx, fy, 0)
                                        } else if (dragging) rfb.pointer(fx, fy, 0)
                                        break
                                    }
                                    if (multi) continue
                                    if (ch.positionChanged()) {
                                        if (!dragging && (ch.position - start).getDistance() > 12f) { dragging = true; rfb.pointer(fx, fy, 1) }
                                        if (dragging) { toFb(ch.position).let { fx = it.first; fy = it.second }; rfb.pointer(fx, fy, 1) }
                                        ch.consume()
                                    }
                                }
                            }
                        }
                ) {
                    s.frame // redraw on every frame
                    drawContext.canvas.save()
                    drawContext.canvas.translate(origin.x, origin.y)
                    drawContext.canvas.scale(scale, scale)
                    drawImage(bmp.asImageBitmap(), filterQuality = if (scale < 1f) FilterQuality.Medium else FilterQuality.Low)
                    drawContext.canvas.restore()
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
