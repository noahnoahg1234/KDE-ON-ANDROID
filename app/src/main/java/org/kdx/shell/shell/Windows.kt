package org.kdx.shell.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kdx.shell.apps.AppContent
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import kotlin.math.roundToInt

/** Draws every visible window on the current virtual desktop – a miniature KWin. */
@Composable
fun WindowLayer(state: KdxState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = maxWidth.value
        val areaH = maxHeight.value
        val visible = state.windows.filter { it.desktop == state.currentDesktop && !it.minimized && !it.fullscreen }
        visible.forEachIndexed { index, w ->
            key(w.id) {
                if (!w.placed) {
                    // Cascade new floating windows like KWin's smart placement.
                    val n = state.windows.indexOf(w) % 6
                    w.w = (areaW - 24f).coerceAtMost(560f).coerceAtLeast(240f)
                    w.h = (areaH - 24f).coerceAtMost(460f).coerceAtLeast(200f)
                    w.x = (12f + n * 28f).coerceAtMost((areaW - w.w).coerceAtLeast(0f))
                    w.y = (12f + n * 28f).coerceAtMost((areaH - w.h).coerceAtLeast(0f))
                    w.placed = true
                }
                WindowFrame(state, w, areaW, areaH, active = w.id == state.activeId && index == visible.lastIndex)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowFrame(state: KdxState, w: KWindow, areaW: Float, areaH: Float, active: Boolean) {
    val c = LocalBreeze.current
    val density = LocalDensity.current.density
    val shape = if (w.maximized) RoundedCornerShape(0.dp) else RoundedCornerShape(6.dp)
    val frameMod = if (w.maximized) Modifier.fillMaxSize() else Modifier
        .offset { IntOffset((w.x * density).roundToInt(), (w.y * density).roundToInt()) }
        .size(w.w.dp, w.h.dp)
        .shadow(if (active) 18.dp else 6.dp, shape)

    Column(
        frameMod
            .clip(shape)
            .background(c.window)
            .then(if (!w.maximized) Modifier.border(1.dp, if (active) c.accent.copy(alpha = 0.7f) else c.border, shape) else Modifier)
            // Any touch inside the window raises it (click-to-focus).
            .pointerInput(w.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (state.activeId != w.id || state.windows.lastOrNull() != w) state.focus(w)
                }
            }
    ) {
        TitleBar(state, w, active, areaW, areaH, density)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AppContent(state, w)
            if (!w.maximized) ResizeHandle(w, areaW, areaH, density, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun TitleBar(state: KdxState, w: KWindow, active: Boolean, areaW: Float, areaH: Float, density: Float) {
    val c = LocalBreeze.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(if (active) c.header else c.window)
            .pointerInput(w.id) {
                detectTapGestures(onDoubleTap = { state.toggleMaximize(w) }, onLongPress = { menu = true })
            }
            .pointerInput(w.id, w.maximized) {
                if (!w.maximized) detectDragGestures { change, drag ->
                    change.consume()
                    w.x = (w.x + drag.x / density).coerceIn(-w.w + 80f, areaW - 80f)
                    w.y = (w.y + drag.y / density).coerceIn(0f, areaH - 36f)
                }
            }
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable { menu = true }.padding(4.dp)) {
                WindowIcon(w, 20.dp)
            }
            WindowMenu(state, w, menu) { menu = false }
        }
        Text(
            w.title,
            Modifier.weight(1f).padding(horizontal = 8.dp),
            color = if (active) c.text else c.inactiveText,
            fontSize = 14.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        if (w.keepAbove) Icon(Icons.Filled.PushPin, "Kept above", tint = c.inactiveText, modifier = Modifier.size(14.dp))
        TitleButton(Icons.Filled.KeyboardArrowDown, "Minimize", Color.Transparent) { state.minimize(w) }
        TitleButton(if (w.maximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare, "Maximize", Color.Transparent) { state.toggleMaximize(w) }
        TitleButton(Icons.Filled.Close, "Close", c.negative) { state.close(w) }
        Spacer(Modifier.width(4.dp))
    }
    HorizontalDivider(color = c.border)
}

@Composable
private fun TitleButton(icon: ImageVector, label: String, hoverBg: Color, onClick: () -> Unit) {
    val c = LocalBreeze.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.padding(horizontal = 2.dp).size(28.dp).clip(CircleShape)
            .background(if (hoverBg != Color.Transparent) hoverBg.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (hoverBg != Color.Transparent) hoverBg else c.text, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ResizeHandle(w: KWindow, areaW: Float, areaH: Float, density: Float, modifier: Modifier) {
    val c = LocalBreeze.current
    Box(
        modifier.size(28.dp).pointerInput(w.id) {
            detectDragGestures { change, drag ->
                change.consume()
                w.w = (w.w + drag.x / density).coerceIn(220f, (areaW - w.x).coerceAtLeast(220f))
                w.h = (w.h + drag.y / density).coerceIn(160f, (areaH - w.y).coerceAtLeast(160f))
            }
        },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Icon(Icons.Filled.SouthEast, "Resize", tint = c.inactiveText.copy(alpha = 0.6f), modifier = Modifier.size(14.dp).padding(1.dp))
    }
}

/** KWin Overview: virtual desktop strip plus a grid of open windows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Overview(state: KdxState) {
    val c = LocalBreeze.current
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.overviewOpen = false }
            .statusBarsPadding().navigationBarsPadding().padding(12.dp)
    ) {
        Text("Overview", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.desktopCount) { d ->
                val active = d == state.currentDesktop
                Column(
                    Modifier.width(110.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (active) c.accent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f))
                        .border(1.dp, if (active) c.accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                        .clickable { state.switchDesktop(d) }
                        .padding(8.dp),
                ) {
                    Text("Desktop ${d + 1}", color = Color.White, fontSize = 13.sp)
                    Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.windowsOnDesktop(d).take(5).forEach { WindowIcon(it, 18.dp) }
                    }
                }
            }
            item {
                Row {
                    IconButton({ state.setDesktops(state.desktopCount + 1) }) { Icon(Icons.Filled.Add, "Add desktop", tint = Color.White) }
                    if (state.desktopCount > 1) IconButton({ state.setDesktops(state.desktopCount - 1) }) { Icon(Icons.Filled.Remove, "Remove desktop", tint = Color.White) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val list = state.windowsOnDesktop()
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No windows on this desktop", color = Color.White.copy(alpha = 0.7f))
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { w ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(c.window)
                            .combinedClickable(onClick = { state.overviewOpen = false; state.focus(w) }, onLongClick = { menu = true })
                    ) {
                        Row(Modifier.fillMaxWidth().background(c.header).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            WindowIcon(w, 18.dp)
                            Text(w.title, Modifier.weight(1f).padding(horizontal = 6.dp), color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.Close, "Close", tint = c.negative, modifier = Modifier.size(18.dp).clickable { state.close(w) })
                        }
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            WindowIcon(w, 48.dp)
                            if (w.minimized) Text("minimized", Modifier.align(Alignment.BottomCenter).padding(4.dp), color = c.inactiveText, fontSize = 11.sp)
                        }
                    }
                    WindowMenu(state, w, menu) { menu = false }
                }
            }
        }
    }
}
