package org.kdx.shell.shell

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import java.text.SimpleDateFormat
import java.util.Locale

/** Plasma desktop widgets (plasmoids): Digital Clock, System Load and Sticky Note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Widgets(state: KdxState, compact: Boolean) {
    if (!state.showClockWidget && !state.showNotesWidget && !state.showSystemWidget) return
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.showClockWidget) ClockWidget(state, compact)
        if (state.showSystemWidget) SystemWidget(state)
        if (state.showNotesWidget) NotesWidget(state)
    }
}

@Composable
private fun WidgetCard(modifier: Modifier = Modifier, bg: Color = Color.Black.copy(alpha = 0.32f), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(12.dp), content = content)
}

@Composable
private fun ClockWidget(state: KdxState, compact: Boolean) {
    val now = rememberNow()
    WidgetCard(Modifier.clickable { state.calendarOpen = true }) {
        Text(
            formatTime(now, state.use24h, state.showSeconds),
            color = Color.White, fontSize = if (compact) 44.sp else 56.sp, fontWeight = FontWeight.Light,
        )
        Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
    }
}

@Composable
private fun SystemWidget(state: KdxState) {
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
    WidgetCard(Modifier.width(190.dp).clickable { state.open(KApp.SYSMON) }) {
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
private fun NotesWidget(state: KdxState) {
    WidgetCard(Modifier.width(220.dp).heightIn(min = 120.dp), bg = Color(0xE6F7E07B)) {
        Text("Note", color = Color(0xFF5C4A00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        BasicTextField(
            value = state.notes,
            onValueChange = { state.notes = it; state.save() },
            textStyle = TextStyle(color = Color(0xFF2B2300), fontSize = 14.sp),
            cursorBrush = SolidColor(Color(0xFF2B2300)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 180.dp),
        )
    }
}
