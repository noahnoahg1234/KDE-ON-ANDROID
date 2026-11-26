package org.kdx.shell.shell

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.kdx.shell.core.DesktopWidget
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.core.WidgetType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Plasma desktop widgets (plasmoids). Long-press the desktop → Add Widgets… to add one; long-press a widget to move
 * or remove it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Widgets(state: KdxState, compact: Boolean) {
    if (state.desktopWidgets.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.desktopWidgets.forEach { w -> key(w.id) { DesktopWidgetView(state, w, compact) } }
    }
}

@Composable
private fun DesktopWidgetView(state: KdxState, w: DesktopWidget, compact: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val longPress = { menu = true }
    Box {
        when (w.type) {
            WidgetType.CLOCK -> ClockWidget(state, compact, longPress)
            WidgetType.ANALOG_CLOCK -> AnalogClockWidget(state, longPress)
            WidgetType.CALENDAR -> CalendarWidget(state, longPress)
            WidgetType.SYSTEM -> SystemWidget(state, longPress)
            WidgetType.BATTERY -> BatteryWidget(state, longPress)
            WidgetType.NOTE -> NotesWidget(state, w, longPress)
        }
        DropdownMenu(menu, { menu = false }) {
            val i = state.desktopWidgets.indexOf(w)
            if (i > 0) DropdownMenuItem(text = { Text("Move earlier") }, leadingIcon = { Icon(Icons.Filled.ArrowBack, null) },
                onClick = { menu = false; state.moveWidget(w, -1) })
            if (i < state.desktopWidgets.lastIndex) DropdownMenuItem(text = { Text("Move later") }, leadingIcon = { Icon(Icons.Filled.ArrowForward, null) },
                onClick = { menu = false; state.moveWidget(w, 1) })
            DropdownMenuItem(text = { Text("Add Widgets…") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                onClick = { menu = false; state.widgetExplorerOpen = true })
            DropdownMenuItem(text = { Text("Remove ${w.type.title}") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { menu = false; state.removeWidget(w) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetCard(
    modifier: Modifier = Modifier,
    bg: Color = Color.Black.copy(alpha = 0.32f),
    onClick: () -> Unit = {},
    onLongClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(bg).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(12.dp),
        content = content,
    )
}

@Composable
private fun ClockWidget(state: KdxState, compact: Boolean, onLongClick: () -> Unit) {
    val now = rememberNow()
    WidgetCard(onClick = { state.calendarOpen = true }, onLongClick = onLongClick) {
        Text(
            formatTime(now, state.use24h, state.showSeconds),
            color = Color.White, fontSize = if (compact) 44.sp else 56.sp, fontWeight = FontWeight.Light,
        )
        Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
    }
}

@Composable
private fun AnalogClockWidget(state: KdxState, onLongClick: () -> Unit) {
    val now = rememberNow()
    val cal = Calendar.getInstance().apply { time = now }
    val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
    val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
    val sec = cal.get(Calendar.SECOND)
    val accent = LocalBreeze.current.accent
    WidgetCard(onClick = { state.calendarOpen = true }, onLongClick = onLongClick) {
        Canvas(Modifier.size(132.dp)) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.White.copy(alpha = 0.12f), r)
            drawCircle(Color.White.copy(alpha = 0.7f), r, style = Stroke(2.dp.toPx()))
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0 - 90).toFloat()
                val len = if (i % 3 == 0) 0.16f else 0.08f
                drawLine(Color.White.copy(alpha = 0.85f), c + Offset(cos(a), sin(a)) * (r * (0.92f - len)), c + Offset(cos(a), sin(a)) * (r * 0.92f),
                    strokeWidth = (if (i % 3 == 0) 3f else 1.5f).dp.toPx(), cap = StrokeCap.Round)
            }
            fun hand(turns: Float, length: Float, width: Float, color: Color) {
                val a = Math.toRadians(turns * 360.0 - 90).toFloat()
                drawLine(color, c, c + Offset(cos(a), sin(a)) * (r * length), strokeWidth = width.dp.toPx(), cap = StrokeCap.Round)
            }
            hand(h / 12f, 0.5f, 5f, Color.White)
            hand(m / 60f, 0.75f, 3.5f, Color.White)
            if (state.showSeconds) hand(sec / 60f, 0.82f, 1.5f, accent)
            drawCircle(accent, 4.dp.toPx(), c)
        }
    }
}

@Composable
private fun CalendarWidget(state: KdxState, onLongClick: () -> Unit) {
    val now = rememberNow(60_000)
    val cal = Calendar.getInstance().apply { time = now }
    val today = cal.get(Calendar.DAY_OF_MONTH)
    val month = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now)
    val first = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
    val firstDow = cal.firstDayOfWeek
    val offset = (first.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val short = java.text.DateFormatSymbols.getInstance().shortWeekdays // index = Calendar.SUNDAY..SATURDAY
    val names = (0 until 7).map { i -> short[(firstDow - 1 + i) % 7 + 1].take(2) }
    val accent = LocalBreeze.current.accent
    WidgetCard(Modifier.width(232.dp), onClick = { state.calendarOpen = true }, onLongClick = onLongClick) {
        Text(month, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
        Row { names.forEach { Text(it, Modifier.width(29.dp), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center) } }
        val cells = offset + days
        for (row in 0 until (cells + 6) / 7) {
            Row {
                for (col in 0 until 7) {
                    val day = row * 7 + col - offset + 1
                    Box(Modifier.size(29.dp, 24.dp), contentAlignment = Alignment.Center) {
                        if (day in 1..days) {
                            val isToday = day == today
                            Box(
                                Modifier.size(22.dp).then(if (isToday) Modifier.background(accent, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) { Text("$day", color = Color.White.copy(alpha = if (isToday) 1f else 0.85f), fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemWidget(state: KdxState, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val battery = rememberBattery()
    var mem by remember { mutableFloatStateOf(0f) }
    var memText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        while (true) {
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            mem = if (mi.totalMem > 0) 1f - mi.availMem.toFloat() / mi.totalMem else 0f
            memText = "%.1f / %.1f GiB".format((mi.totalMem - mi.availMem) / 1073741824.0, mi.totalMem / 1073741824.0)
            delay(2000)
        }
    }
    WidgetCard(Modifier.width(190.dp), onClick = { state.open(KApp.SYSMON) }, onLongClick = onLongClick) {
        Text("System", color = Color.White, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("Memory  $memText", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        LinearProgressIndicator(progress = { mem }, Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Color(0xFF3DAEE9), trackColor = Color.White.copy(alpha = 0.2f))
        if (battery.level >= 0) {
            Text("Battery  ${battery.level}%${if (battery.charging) " ⚡" else ""}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            LinearProgressIndicator(
                progress = { battery.level / 100f }, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = if (battery.level <= 15) Color(0xFFDA4453) else Color(0xFF27AE60), trackColor = Color.White.copy(alpha = 0.2f),
            )
        }
    }
}

@Composable
private fun BatteryWidget(state: KdxState, onLongClick: () -> Unit) {
    val b = rememberBattery()
    val color = when {
        b.charging -> Color(0xFF27AE60)
        b.level in 0..15 -> Color(0xFFDA4453)
        else -> Color.White
    }
    WidgetCard(Modifier.width(170.dp), onClick = { state.open(KApp.INFOCENTER) }, onLongClick = onLongClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (b.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull, null, tint = color, modifier = Modifier.size(28.dp))
            Text(if (b.level >= 0) "${b.level}%" else "–", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.padding(start = 6.dp))
        }
        LinearProgressIndicator(
            progress = { (b.level.coerceAtLeast(0)) / 100f }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
            color = color, trackColor = Color.White.copy(alpha = 0.2f),
        )
        Text(
            (if (b.charging) "Charging" else "On battery") + if (b.tempC > 0) " · %.0f °C".format(b.tempC) else "",
            color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
        )
    }
}

@Composable
private fun NotesWidget(state: KdxState, w: DesktopWidget, onLongClick: () -> Unit) {
    // the first note is shared with the Notes app; added notes have their own text
    val text = w.text ?: state.notes
    WidgetCard(Modifier.width(220.dp).heightIn(min = 120.dp), bg = Color(0xE6F7E07B), onLongClick = onLongClick) {
        Text("Note", color = Color(0xFF5C4A00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        BasicTextField(
            value = text,
            onValueChange = { if (w.text == null) state.notes = it else w.text = it; state.save() },
            textStyle = TextStyle(color = Color(0xFF2B2300), fontSize = 14.sp),
            cursorBrush = SolidColor(Color(0xFF2B2300)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 180.dp),
        )
    }
}

private fun widgetIcon(t: WidgetType): ImageVector = when (t) {
    WidgetType.CLOCK -> Icons.Filled.Schedule
    WidgetType.ANALOG_CLOCK -> Icons.Filled.AccessTime
    WidgetType.CALENDAR -> Icons.Filled.CalendarMonth
    WidgetType.SYSTEM -> Icons.Filled.Memory
    WidgetType.BATTERY -> Icons.Filled.BatteryFull
    WidgetType.NOTE -> Icons.Filled.StickyNote2
}

/** Plasma's widget explorer: every widget, with Add (or "On the desktop" for one that can only be there once). */
@Composable
fun WidgetExplorer(state: KdxState) {
    if (!state.widgetExplorerOpen) return
    val c = LocalBreeze.current
    androidx.compose.ui.window.Dialog({ state.widgetExplorerOpen = false }) {
        Column(Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(12.dp)).background(c.window).padding(16.dp)) {
            Text("Add Widgets", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("Long-press a widget on the desktop to move or remove it.", color = c.inactiveText, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(WidgetType.entries) { t ->
                    val can = state.canAddWidget(t)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = can) { state.addWidget(t) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Icon(widgetIcon(t), null, tint = c.accent)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(t.title, color = c.text, fontSize = 15.sp)
                            Text(t.description, color = c.inactiveText, fontSize = 12.sp)
                        }
                        if (can) FilledTonalButton({ state.addWidget(t) }) { Text("Add") }
                        else Text("On the desktop", color = c.inactiveText, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ state.widgetExplorerOpen = false }) { Text("Done") }
            }
        }
    }
}
