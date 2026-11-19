package org.kdx.shell.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kdx.shell.core.Calc
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.shell.ToolBar
import org.kdx.shell.shell.ToolButton

/** Dispatches a window to the right application UI. */
@Composable
fun AppContent(state: KdxState, w: KWindow) {
    when (w.app) {
        KApp.DOLPHIN -> Dolphin(state, w)
        KApp.KONSOLE -> Konsole(state, w)
        KApp.KATE -> Kate(state, w)
        KApp.KCALC -> KCalc(state, w)
        KApp.GWENVIEW -> Gwenview(state, w)
        KApp.KLIPPER -> Klipper(state)
        KApp.KNOTES -> KNotes(state)
        KApp.SYSMON -> SystemMonitor(state, w)
        KApp.INFOCENTER -> InfoCenter(state)
        KApp.SETTINGS -> SystemSettings(state, w)
    }
}

class CalcSession {
    var expr by mutableStateOf("")
    var result by mutableStateOf("0")
    var degrees by mutableStateOf(true)
    var memory by mutableDoubleStateOf(0.0)
    val tape = mutableStateListOf<String>()
}

/** KCalc – scientific calculator with memory and a history tape. */
@Composable
fun KCalc(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val s = remember(w) { w.retain("kcalc") { CalcSession() } }
    fun preview() = runCatching { Calc.format(Calc.eval(s.expr, s.degrees)) }.getOrNull()
    fun press(k: String) {
        when (k) {
            "C" -> { s.expr = ""; s.result = "0" }
            "⌫" -> s.expr = s.expr.dropLast(1)
            "=" -> runCatching { Calc.eval(s.expr, s.degrees) }.onSuccess {
                val r = Calc.format(it); s.tape.add(0, "${s.expr} = $r"); s.result = r; s.expr = r
            }.onFailure { s.result = "Error" }
            "MC" -> s.memory = 0.0
            "MR" -> s.expr += Calc.format(s.memory)
            "M+" -> runCatching { s.memory += Calc.eval(s.expr.ifEmpty { s.result }, s.degrees) }
            "M−" -> runCatching { s.memory -= Calc.eval(s.expr.ifEmpty { s.result }, s.degrees) }
            "x²" -> s.expr = "(${s.expr})^2"
            "1/x" -> s.expr = "1/(${s.expr})"
            "±" -> s.expr = if (s.expr.startsWith("-(")) s.expr.removePrefix("-(").removeSuffix(")") else "-(${s.expr})"
            "sin", "cos", "tan", "ln", "log", "sqrt", "abs" -> s.expr += "$k("
            "√" -> s.expr += "sqrt("
            else -> s.expr += k
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.window)) {
        val wide = maxWidth > 520.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(8.dp)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.view).padding(10.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        "${if (s.degrees) "DEG" else "RAD"}${if (s.memory != 0.0) "  M" else ""}",
                        Modifier.fillMaxWidth().clickable { s.degrees = !s.degrees }, color = c.inactiveText, fontSize = 11.sp,
                    )
                    Text(s.expr.ifEmpty { " " }, color = c.inactiveText, fontSize = 18.sp, maxLines = 2, fontFamily = FontFamily.Monospace)
                    Text(
                        (if (s.expr.isNotEmpty()) preview() else null) ?: s.result,
                        color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val rows = listOf(
                    listOf("MC", "MR", "M+", "M−", "C"),
                    listOf("sin", "cos", "tan", "(", ")"),
                    listOf("ln", "log", "√", "x²", "^"),
                    listOf("7", "8", "9", "÷", "⌫"),
                    listOf("4", "5", "6", "×", "1/x"),
                    listOf("1", "2", "3", "−", "π"),
                    listOf("0", ".", "±", "+", "="),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rows.forEach { row ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { k ->
                                val isNum = k.length == 1 && (k[0].isDigit() || k == ".")
                                val bg = when {
                                    k == "=" -> c.accent
                                    k == "C" -> c.negative.copy(alpha = 0.85f)
                                    isNum -> c.view
                                    else -> c.header
                                }
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(bg)
                                        .clickable { press(if (k == "−") "-" else k) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(k, color = if (k == "=" || k == "C") Color.White else c.text, fontSize = 17.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (wide) {
                Column(Modifier.width(200.dp).fillMaxHeight().background(c.view).padding(8.dp)) {
                    Text("History", color = c.inactiveText, fontSize = 12.sp)
                    LazyColumn {
                        items(s.tape) { t ->
                            Text(t, color = c.text, fontSize = 13.sp, modifier = Modifier.clickable { s.expr = t.substringAfter("= ") }.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Klipper – clipboard history. */
@Composable
fun Klipper(state: KdxState) {
    val c = LocalBreeze.current
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.view)) {
        ToolBar {
            OutlinedTextField(filter, { filter = it }, Modifier.weight(1f).padding(4.dp), placeholder = { Text("Search clipboard history…") }, singleLine = true)
            ToolButton(Icons.Filled.DeleteSweep, "Clear history") { state.clipboardHistory.clear() }
        }
        val items = state.clipboardHistory.filter { filter.isBlank() || it.contains(filter, true) }
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Clipboard history is empty.\nAnything you copy while KDX is open appears here.", color = c.inactiveText, textAlign = TextAlign.Center)
        }
        LazyColumn {
            items(items) { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { state.copyText(t); state.toast("Copied to clipboard") }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.ContentPaste, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(t, Modifier.weight(1f), color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    IconButton({ state.open(KApp.KATE); state.toast("Paste into the new document") }) { Icon(Icons.Filled.EditNote, "Edit") }
                    IconButton({ state.clipboardHistory.remove(t) }) { Icon(Icons.Filled.Close, "Remove") }
                }
                HorizontalDivider(color = c.border.copy(alpha = 0.5f))
            }
        }
    }
}

/** KNotes – the desktop sticky note, in a window. */
@Composable
fun KNotes(state: KdxState) {
    Column(Modifier.fillMaxSize().background(Color(0xFFF7E07B)).padding(12.dp)) {
        BasicTextField(
            value = state.notes,
            onValueChange = { state.notes = it; state.save() },
            textStyle = TextStyle(color = Color(0xFF2B2300), fontSize = 16.sp),
            cursorBrush = SolidColor(Color(0xFF2B2300)),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        )
    }
}
