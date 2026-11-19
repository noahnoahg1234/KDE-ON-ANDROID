package org.kdx.shell.apps

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.shell.TextInputDialog
import org.kdx.shell.shell.ToolBar
import org.kdx.shell.shell.ToolButton
import java.io.File

class KateSession(path: String?) {
    var file by mutableStateOf(path?.let { File(it) })
    var text by mutableStateOf(TextFieldValue(""))
    var saved by mutableStateOf("")
    var loaded by mutableStateOf(path == null)
    var fontSize by mutableFloatStateOf(14f)
    var wrap by mutableStateOf(false)
    val undo = ArrayDeque<TextFieldValue>()
    val redo = ArrayDeque<TextFieldValue>()
    val modified get() = text.text != saved
}

/** Kate – KDE's advanced text editor, with line numbers, find/replace, undo and word wrap. */
@Composable
fun Kate(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val scope = rememberCoroutineScope()
    val s = remember(w) { w.retain("kate") { KateSession(w.arg) } }
    var dialog by remember { mutableStateOf<String?>(null) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var findOpen by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }

    LaunchedEffect(s.file, s.modified) {
        w.title = "${s.file?.name ?: "Untitled"}${if (s.modified) " *" else ""} — Kate"
    }
    LaunchedEffect(s.file) {
        val f = s.file
        if (!s.loaded && f != null) {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    if (f.length() > 4L * 1024 * 1024) error("File is larger than 4 MiB")
                    if (f.exists()) f.readText() else ""
                }
            }
            content.onSuccess { s.text = TextFieldValue(it); s.saved = it }
                .onFailure { state.toast("Cannot open: ${it.message}") }
            s.loaded = true
        }
    }

    fun save(target: File) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { target.parentFile?.mkdirs(); target.writeText(s.text.text) } }
            r.onSuccess { s.file = target; s.saved = s.text.text; state.toast("Saved ${target.name}") }
                .onFailure { state.toast("Save failed: ${it.message}") }
        }
    }

    fun edit(v: TextFieldValue) {
        if (v.text != s.text.text) {
            s.undo.addLast(s.text); if (s.undo.size > 200) s.undo.removeFirst(); s.redo.clear()
        }
        s.text = v
    }

    fun findNext() {
        if (findText.isEmpty()) return
        val from = s.text.selection.max
        var i = s.text.text.indexOf(findText, from, ignoreCase = true)
        if (i < 0) i = s.text.text.indexOf(findText, 0, ignoreCase = true)
        if (i < 0) state.toast("Not found") else s.text = s.text.copy(selection = TextRange(i, i + findText.length))
    }

    Column(Modifier.fillMaxSize().background(c.view)) {
        ToolBar {
            ToolButton(Icons.Filled.NoteAdd, "New") { if (s.modified) confirmNew = true else state.open(KApp.KATE) }
            ToolButton(Icons.Filled.FolderOpen, "Open") {
                state.open(KApp.DOLPHIN, s.file?.parent ?: state.homeDir().path)
            }
            ToolButton(Icons.Filled.Save, "Save", s.modified || s.file == null) { s.file?.let { save(it) } ?: run { dialog = "saveAs" } }
            ToolButton(Icons.Filled.SaveAs, "Save As") { dialog = "saveAs" }
            ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo", s.undo.isNotEmpty()) {
                s.redo.addLast(s.text); s.text = s.undo.removeLast()
            }
            ToolButton(Icons.AutoMirrored.Filled.Redo, "Redo", s.redo.isNotEmpty()) {
                s.undo.addLast(s.text); s.text = s.redo.removeLast()
            }
            ToolButton(Icons.Filled.Search, "Find") { findOpen = !findOpen }
            Spacer(Modifier.weight(1f))
            ToolButton(Icons.AutoMirrored.Filled.WrapText, "Word wrap") { s.wrap = !s.wrap }
            ToolButton(Icons.Filled.ZoomOut, "Smaller") { s.fontSize = (s.fontSize - 1).coerceAtLeast(8f) }
            ToolButton(Icons.Filled.ZoomIn, "Bigger") { s.fontSize = (s.fontSize + 1).coerceAtMost(32f) }
        }
        if (findOpen) {
            Row(Modifier.fillMaxWidth().background(c.window).padding(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(findText, { findText = it }, Modifier.weight(1f), placeholder = { Text("Find") }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(replaceText, { replaceText = it }, Modifier.weight(1f), placeholder = { Text("Replace") }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp))
                ToolButton(Icons.Filled.ArrowDownward, "Find next") { findNext() }
                ToolButton(Icons.Filled.FindReplace, "Replace all") {
                    if (findText.isNotEmpty()) {
                        val n = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE).findAll(s.text.text).count()
                        edit(TextFieldValue(s.text.text.replace(findText, replaceText, ignoreCase = true)))
                        state.toast("Replaced $n")
                    }
                }
            }
        }

        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = s.fontSize.sp, lineHeight = (s.fontSize * 1.45f).sp, color = c.text)
        val lines = s.text.text.count { it == '\n' } + 1
        val vScroll = rememberScrollState()
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll)) {
            if (!s.wrap) {
                Text(
                    (1..lines).joinToString("\n"),
                    Modifier.background(c.window).padding(horizontal = 6.dp, vertical = 6.dp),
                    style = style.copy(color = c.inactiveText, textAlign = TextAlign.End),
                )
            }
            Box(Modifier.weight(1f).then(if (s.wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))) {
                BasicTextField(
                    value = s.text,
                    onValueChange = { edit(it) },
                    textStyle = style,
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.then(if (s.wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 600.dp))
                        .padding(6.dp),
                )
            }
        }
        HorizontalDivider(color = c.border)
        val before = s.text.text.substring(0, s.text.selection.start.coerceIn(0, s.text.text.length))
        val line = before.count { it == '\n' } + 1
        val col = before.length - before.lastIndexOf('\n')
        val words = Regex("\\S+").findAll(s.text.text).count()
        Text(
            "Line $line, Column $col · $lines lines · $words words · ${s.text.text.length} chars · UTF-8" +
                (s.file?.extension?.takeIf { it.isNotEmpty() }?.let { " · ${it.uppercase()}" } ?: ""),
            Modifier.fillMaxWidth().background(c.window).padding(horizontal = 10.dp, vertical = 4.dp),
            color = c.inactiveText, fontSize = 11.sp, maxLines = 1,
        )
    }

    if (dialog == "saveAs") {
        val default = s.file?.path ?: File(
            if (state.hasStorageAccess()) File(Environment.getExternalStorageDirectory(), "Documents") else state.homeDir(),
            "Untitled.txt",
        ).path
        TextInputDialog("Save As", default, "Save", { dialog = null }) { path ->
            dialog = null; save(File(path))
        }
    }
    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text("Unsaved changes") },
        text = { Text("Open a new document in another window? Your changes here stay open.") },
        confirmButton = { TextButton({ confirmNew = false; state.open(KApp.KATE) }) { Text("New Window") } },
        dismissButton = { TextButton({ confirmNew = false }) { Text("Cancel") } },
    )
}
