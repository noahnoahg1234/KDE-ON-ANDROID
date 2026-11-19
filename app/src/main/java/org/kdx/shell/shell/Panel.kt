package org.kdx.shell.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** The Plasma panel: application launcher, pager, task manager and system tray. */
@Composable
fun Panel(state: KdxState, compact: Boolean, modifier: Modifier = Modifier) {
    val c = LocalBreeze.current
    Column(Modifier.fillMaxWidth().background(c.panel).then(modifier)) {
        if (!state.panelOnTop) HorizontalDivider(color = c.border.copy(alpha = 0.6f))
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KickoffButton(state)
            PanelIcon(Icons.Filled.Search, "KRunner", state.krunnerOpen) {
                state.kickoffOpen = false; state.krunnerOpen = !state.krunnerOpen
            }
            if (!compact) PanelIcon(Icons.Filled.GridView, "Overview", state.overviewOpen) {
                state.closeMenus(); state.overviewOpen = true
            }
            Pager(state, compact)
            TaskManager(state, compact, Modifier.weight(1f))
            SystemTray(state, compact)
        }
        if (state.panelOnTop) HorizontalDivider(color = c.border.copy(alpha = 0.6f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KickoffButton(state: KdxState) {
    val c = LocalBreeze.current
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (state.kickoffOpen) c.accent.copy(alpha = 0.25f) else Color.Transparent)
            .combinedClickable(
                onClick = { state.krunnerOpen = false; state.kickoffOpen = !state.kickoffOpen },
                onLongClick = { state.closeMenus(); state.overviewOpen = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        KdxLogo(30.dp)
    }
}

/** The "K" start-menu glyph. */
@Composable
fun KdxLogo(size: androidx.compose.ui.unit.Dp) {
    val c = LocalBreeze.current
    Box(
        Modifier.size(size).clip(CircleShape).background(c.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text("K", color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.55f).sp)
    }
}

@Composable
private fun PanelIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val c = LocalBreeze.current
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
            .background(if (active) c.accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = c.panelText, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Pager(state: KdxState, compact: Boolean) {
    val c = LocalBreeze.current
    if (state.desktopCount < 2) return
    if (compact) {
        Box(
            Modifier.padding(horizontal = 4.dp).size(28.dp, 24.dp)
                .border(1.dp, c.accent, RoundedCornerShape(4.dp))
                .clickable { state.switchDesktop(state.currentDesktop + 1) },
            contentAlignment = Alignment.Center,
        ) { Text("${state.currentDesktop + 1}", color = c.panelText, fontSize = 12.sp) }
        return
    }
    Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(state.desktopCount) { d ->
            val active = d == state.currentDesktop
            val count = state.windows.count { it.desktop == d && !it.minimized }
            Box(
                Modifier.size(34.dp, 26.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) c.accent.copy(alpha = 0.35f) else c.panelText.copy(alpha = 0.08f))
                    .border(1.dp, if (active) c.accent else c.border, RoundedCornerShape(3.dp))
                    .clickable { state.switchDesktop(d) },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (count > 0) "${d + 1}•" else "${d + 1}", color = c.panelText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TaskManager(state: KdxState, compact: Boolean, modifier: Modifier) {
    val tasks = state.windowsOnDesktop().sortedBy { it.id }
    LazyRow(modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(tasks, key = { it.id }) { w -> TaskButton(state, w, compact) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskButton(state: KdxState, w: KWindow, compact: Boolean) {
    val c = LocalBreeze.current
    val active = w.id == state.activeId && !w.minimized
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .height(44.dp)
                .then(if (compact) Modifier.width(44.dp) else Modifier.widthIn(max = 190.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) c.accent.copy(alpha = 0.22f) else Color.Transparent)
                .combinedClickable(onClick = { state.taskClicked(w) }, onLongClick = { menu = true }),
        ) {
            Row(
                Modifier.weight(1f).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
            ) {
                Box(Modifier.alpha(if (w.minimized) 0.55f else 1f)) { WindowIcon(w, 26.dp) }
                if (!compact) {
                    Spacer(Modifier.width(6.dp))
                    Text(w.title, color = c.panelText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Breeze-style indicator line
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .padding(horizontal = if (active) 0.dp else 10.dp)
                    .background(if (active) c.accent else c.inactiveText.copy(alpha = 0.6f))
            )
        }
        WindowMenu(state, w, menu) { menu = false }
    }
}

/** Plasma's window menu, shared by the task manager and the window title bar. */
@Composable
fun WindowMenu(state: KdxState, w: KWindow, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text(if (w.minimized) "Restore" else "Minimize") }, onClick = {
            onDismiss(); if (w.minimized) state.focus(w) else state.minimize(w)
        })
        DropdownMenuItem(text = { Text(if (w.maximized) "Restore Size" else "Maximize") }, onClick = { onDismiss(); state.toggleMaximize(w) })
        DropdownMenuItem(
            text = { Text("Keep Above Others") },
            trailingIcon = { if (w.keepAbove) Icon(Icons.Filled.Check, null) },
            onClick = { onDismiss(); w.keepAbove = !w.keepAbove; state.focus(w) },
        )
        if (state.desktopCount > 1) {
            HorizontalDivider()
            repeat(state.desktopCount) { d ->
                if (d != w.desktop) DropdownMenuItem(text = { Text("Move to Desktop ${d + 1}") }, onClick = {
                    onDismiss(); w.desktop = d
                    if (state.activeId == w.id) state.activeId = null
                })
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Close") },
            leadingIcon = { Icon(Icons.Filled.Close, null) },
            onClick = { onDismiss(); state.close(w) },
        )
    }
}

@Composable
private fun SystemTray(state: KdxState, compact: Boolean) {
    val c = LocalBreeze.current
    val battery = rememberBattery()
    val now = rememberNow()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            PanelIcon(Icons.Filled.ContentPaste, "Klipper", false) { state.open(KApp.KLIPPER) }
            PanelIcon(Icons.Filled.Tune, "Quick settings", false) { state.openAndroidSettings() }
        }
        if (battery.level >= 0) {
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.open(KApp.SYSMON) }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when {
                        battery.charging -> Icons.Filled.BatteryChargingFull
                        battery.level > 80 -> Icons.Filled.BatteryFull
                        battery.level > 50 -> Icons.Filled.Battery5Bar
                        battery.level > 20 -> Icons.Filled.Battery3Bar
                        else -> Icons.Filled.BatteryAlert
                    },
                    "Battery",
                    tint = if (battery.level <= 15 && !battery.charging) c.negative else c.panelText,
                    modifier = Modifier.size(18.dp),
                )
                if (!compact) Text("${battery.level}%", color = c.panelText, fontSize = 12.sp)
            }
        }
        Column(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.kickoffOpen = false; state.calendarOpen = !state.calendarOpen }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(formatTime(now, state.use24h, false), color = c.panelText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (!compact) Text(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now), color = c.panelText, fontSize = 10.sp)
        }
    }
}

/** Digital clock popup with a month calendar, like Plasma's clock applet. */
@Composable
fun CalendarPopup(state: KdxState) {
    val c = LocalBreeze.current
    var offset by remember { mutableIntStateOf(0) }
    val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, offset) }
    val today = Calendar.getInstance()
    val month = cal.get(Calendar.MONTH)
    val year = cal.get(Calendar.YEAR)
    val firstDow = (cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val now = rememberNow()
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 52.dp, top = 52.dp, end = 8.dp),
        contentAlignment = if (state.panelOnTop) Alignment.TopEnd else Alignment.BottomEnd) {
        Surface(
            Modifier.width(300.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(8.dp), color = c.window, shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(formatTime(now, state.use24h, true), fontSize = 32.sp, fontWeight = FontWeight.Light, color = c.text)
                Text(SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now), color = c.inactiveText, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ offset-- }) { Icon(Icons.Filled.ChevronLeft, "Previous") }
                    Text(
                        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time),
                        Modifier.weight(1f).clickable { offset = 0 }, color = c.text, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton({ offset++ }) { Icon(Icons.Filled.ChevronRight, "Next") }
                }
                val dows = (0 until 7).map {
                    val d = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (cal.firstDayOfWeek - 1 + it) % 7 + 1) }
                    SimpleDateFormat("EEE", Locale.getDefault()).format(d.time).take(2)
                }
                Row { dows.forEach { Text(it, Modifier.weight(1f), color = c.inactiveText, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
                val cells = firstDow + days
                for (row in 0 until (cells + 6) / 7) {
                    Row {
                        for (col in 0 until 7) {
                            val day = row * 7 + col - firstDow + 1
                            val isToday = day == today.get(Calendar.DAY_OF_MONTH) && month == today.get(Calendar.MONTH) && year == today.get(Calendar.YEAR)
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(CircleShape)
                                .background(if (isToday) c.accent else Color.Transparent), contentAlignment = Alignment.Center) {
                                if (day in 1..days) Text("$day", color = if (isToday) Color.White else c.text, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
