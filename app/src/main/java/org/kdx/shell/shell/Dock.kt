package org.kdx.shell.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.core.PanelPosition
import java.text.SimpleDateFormat
import java.util.Locale

/** One dock entry: a pinned app (maybe with open windows) or an open window of an app that isn't pinned. */
private class DockItem(val launchable: Launchable?, val windows: List<KWindow>)

/** Pinned apps first (with their open windows), then windows of other apps – one entry per app. */
private fun dockItems(state: KdxState): List<DockItem> {
    val open = state.windowsOnDesktop().sortedBy { it.id }
    val pinned = state.pinned.mapNotNull { state.resolve(it) }
    val used = mutableSetOf<Int>()
    val items = pinned.map { l ->
        val ws = if (l is Launchable.Kdx) open.filter { it.app == l.app && it.iconFile == null } else emptyList()
        used += ws.map { it.id }
        DockItem(l, ws)
    }
    // other windows, grouped by app (Linux apps each by their own window)
    val rest = open.filter { it.id !in used }.groupBy { if (it.iconFile != null) "w${it.id}" else it.app.name }
    return items + rest.values.map { DockItem(null, it) }
}

/**
 * A side dock like Ubuntu's: app icons from the top, a dot beside each open app (two for several windows), the
 * active app highlighted, and "Show Applications" at the bottom. Tap: open / switch to / minimise; long-press: menu.
 */
@Composable
fun Dock(state: KdxState, modifier: Modifier = Modifier) {
    val c = LocalBreeze.current
    val left = state.panelPosition == PanelPosition.LEFT_DOCK
    Column(
        modifier.fillMaxHeight().width(60.dp).background(c.panel.copy(alpha = 0.92f)).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            dockItems(state).forEach { item -> DockButton(state, item, left) }
            state.androidWindows.toList().forEach { a -> AndroidDockButton(state, a, left) }
        }
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                .background(if (state.kickoffOpen) c.accent.copy(alpha = 0.25f) else Color.Transparent)
                .clickable { state.krunnerOpen = false; state.kickoffOpen = !state.kickoffOpen }
                .semantics { contentDescription = "Show Applications" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Apps, null, tint = c.panelText, modifier = Modifier.size(30.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockButton(state: KdxState, item: DockItem, left: Boolean) {
    val c = LocalBreeze.current
    var menu by remember { mutableStateOf(false) }
    val active = item.windows.any { it.id == state.activeId && !it.minimized }
    val label = item.launchable?.label ?: item.windows.first().title
    Box(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (left) RunningDots(item.windows.size)
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (active) c.accent.copy(alpha = 0.25f) else Color.Transparent)
                    .combinedClickable(
                        onClick = {
                            val ws = item.windows
                            when {
                                ws.isEmpty() -> item.launchable?.let { state.launch(it) }
                                ws.size == 1 -> state.taskClicked(ws[0])
                                // several windows: bring the next one forward, like cycling through them
                                else -> state.focus(ws.firstOrNull { it.id != state.activeId && !it.minimized } ?: ws.first { it.id != state.activeId })
                            }
                        },
                        onLongClick = { menu = true },
                    )
                    .semantics { contentDescription = label }
                    .testTag("dock:$label"),
                contentAlignment = Alignment.Center,
            ) {
                item.launchable?.let { LaunchableIcon(it, 34.dp) } ?: WindowIcon(item.windows.first(), 34.dp)
            }
            if (!left) RunningDots(item.windows.size)
        }
        DropdownMenu(menu, { menu = false }) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.SemiBold, color = c.text)
            item.windows.forEach { w ->
                DropdownMenuItem(text = { Text(w.title, maxLines = 1) }, onClick = { menu = false; state.focus(w) })
            }
            item.launchable?.let { l ->
                DropdownMenuItem(text = { Text(if (item.windows.isEmpty()) "Open" else "New window") }, onClick = { menu = false; state.launch(l) })
                DropdownMenuItem(text = { Text(if (state.isPinned(l.key)) "Unpin from dock" else "Pin to dock") }, onClick = { menu = false; state.togglePinned(l.key) })
            }
            if (item.windows.isNotEmpty()) DropdownMenuItem(
                text = { Text(if (item.windows.size > 1) "Close ${item.windows.size} windows" else "Close") },
                onClick = { menu = false; item.windows.forEach { state.close(it) } },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AndroidDockButton(state: KdxState, a: org.kdx.shell.core.AndroidApp, left: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (left) RunningDots(1)
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { state.focusAndroidWindow(a) }, onLongClick = { menu = true })
                    .semantics { contentDescription = a.label },
                contentAlignment = Alignment.Center,
            ) { a.icon?.let { androidx.compose.foundation.Image(it, null, Modifier.size(34.dp)) } }
            if (!left) RunningDots(1)
        }
        AndroidWindowMenu(state, a, menu) { menu = false }
    }
}

/** Ubuntu-style running indicator: one dot per open window (up to three) on the dock's outer edge. */
@Composable
private fun RunningDots(n: Int) {
    val c = LocalBreeze.current
    Column(Modifier.width(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(n.coerceAtMost(3)) { Box(Modifier.size(4.dp).background(c.accent, CircleShape)) }
    }
}

/** The thin bar along the top that goes with the dock: Activities (overview), the clock in the middle, status icons. */
@Composable
fun TopBar(state: KdxState, compact: Boolean, modifier: Modifier = Modifier) {
    val c = LocalBreeze.current
    val now = rememberNow()
    Box(modifier.fillMaxWidth().background(c.panel.copy(alpha = 0.92f)).height(34.dp).padding(horizontal = 6.dp)) {
        Text(
            "Activities", color = c.panelText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(12.dp))
                .clickable { state.closeMenus(); state.overviewOpen = true }.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Text(
            SimpleDateFormat(if (compact) "EEE d" else "EEE d MMM", Locale.getDefault()).format(now) + "  " + formatTime(now, state.use24h, false),
            color = c.panelText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                .clickable { state.kickoffOpen = false; state.calendarOpen = !state.calendarOpen }.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(Modifier.align(Alignment.CenterEnd)) { TrayIcons(state) }
    }
}
