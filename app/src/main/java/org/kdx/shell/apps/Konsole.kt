package org.kdx.shell.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.*
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.droid.DroidShell
import org.kdx.shell.droid.DroidSystem
import java.io.Closeable
import java.io.File
import java.io.OutputStreamWriter

/** Escape sequences we can't render (cursor movement etc.); SGR colour codes ("…m") are kept. */
private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-ln-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][A-Z0-9]|\u001B[=>]")
private val sgr = Regex("\u001B\\[([0-9;]*)m")

private val ansiColors = listOf(
    0xFF232627, 0xFFED1515, 0xFF11D116, 0xFFF67400, 0xFF1D99F3, 0xFF9B59B6, 0xFF1ABC9C, 0xFFFCFCFC,
    0xFF7F8C8D, 0xFFFF5555, 0xFF1CDC9A, 0xFFFDBC4B, 0xFF3DAEE9, 0xFFC061CB, 0xFF16A085, 0xFFFFFFFF,
).map { Color(it) }

/** Turns text containing ANSI SGR codes into styled text (16 colours, bold, underline, reverse). */
fun ansiToAnnotated(text: String, base: Color): androidx.compose.ui.text.AnnotatedString {
    if (!text.contains('\u001B')) return androidx.compose.ui.text.AnnotatedString(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        var fg: Color? = null; var bgc: Color? = null; var bold = false; var underline = false; var reverse = false
        var pos = 0
        fun flush(end: Int) {
            if (end <= pos) return
            val f = if (reverse) (bgc ?: Color(0xFF232627)) else fg
            val b = if (reverse) (fg ?: base) else bgc
            withStyle(androidx.compose.ui.text.SpanStyle(
                color = f ?: Color.Unspecified, background = b ?: Color.Unspecified,
                fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            )) { append(text.substring(pos, end)) }
        }
        sgr.findAll(text).forEach { m ->
            flush(m.range.first)
            val codes = m.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
            var i = 0
            while (i < codes.size) {
                when (val c = codes[i]) {
                    0 -> { fg = null; bgc = null; bold = false; underline = false; reverse = false }
                    1 -> bold = true
                    4 -> underline = true
                    7 -> reverse = true
                    22 -> bold = false
                    24 -> underline = false
                    27 -> reverse = false
                    in 30..37 -> fg = ansiColors[c - 30]
                    39 -> fg = null
                    in 40..47 -> bgc = ansiColors[c - 40]
                    49 -> bgc = null
                    in 90..97 -> fg = ansiColors[c - 90 + 8]
                    in 100..107 -> bgc = ansiColors[c - 100 + 8]
                    38, 48 -> { // 256-colour: map to nearest of the 16
                        if (codes.getOrNull(i + 1) == 5) {
                            val v = codes.getOrNull(i + 2) ?: 7
                            val col = ansiColors[if (v < 16) v else 7]
                            if (c == 38) fg = col else bgc = col
                            i += 2
                        }
                    }
                }
                i++
            }
            pos = m.range.last + 1
        }
        flush(text.length)
    }
}

class KonsoleSession(
    start: File,
    private val env: Map<String, String>,
    val droid: DroidSystem? = null,
    /** DroidSystem 2: when set, commands run in Debian's bash through proot. */
    val debian: org.kdx.shell.droid.DebianSystem? = null,
    private val guestEnv: Map<String, String> = emptyMap(),
) : Closeable {
    /** Current directory inside Debian (DroidSystem 2). */
    var guestCwd by mutableStateOf("/root")
    /** Variables set with `export` in this session (Debian mode). */
    val exports = linkedMapOf<String, String>()
    private var stateRef: KdxState? = null
    val home: File get() = droid?.home ?: stateRef?.homeDir() ?: File("/")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + org.kdx.shell.core.StartGuard.coroutines)
    val lines = mutableStateListOf<Pair<String, Int>>() // text, kind: 0 out, 1 prompt, 2 err/info
    var cwd by mutableStateOf(start)
    var input by mutableStateOf("")
    var running by mutableStateOf<Process?>(null)
    val history = mutableListOf<String>()
    var histIdx = -1
    var fontSize by mutableFloatStateOf(13f)
    private var stdin: OutputStreamWriter? = null

    init {
        val ds = droid
        val deb = debian
        if (deb != null) {
            runCatching { File(deb.root, "etc/motd").readLines() }.getOrDefault(emptyList()).forEach { lines += it to 2 }
        } else if (ds != null) {
            runCatching { File(ds.root, "etc/motd").readLines() }.getOrDefault(emptyList()).forEach { lines += it to 2 }
        } else {
        lines += "Welcome to Konsole for KDX — Android ${android.os.Build.VERSION.RELEASE} (${System.getProperty("os.arch")})" to 2
        lines += "Commands run in Android's /system/bin/sh. Type 'help' for built-ins." to 2
        }
    }

    fun prompt(): String {
        if (debian != null) {
            val p = if (guestCwd == "/root" || guestCwd.startsWith("/root/")) "~" + guestCwd.removePrefix("/root") else guestCwd
            return "root@droidsystem:$p#"
        }
        val ds = droid
        if (ds != null) {
            val h = ds.home.path
            val p = if (cwd.path == h || cwd.path.startsWith("$h/")) "~" + cwd.path.removePrefix(h) else cwd.path
            return "user@droidsystem:$p\$"
        }
        return "${cwd.path.replace(Regex("^/storage/emulated/0"), "~")} \$"
    }

    /** When set, println output is collected here instead of shown (for `builtin | pipeline`). */
    var capture: MutableList<String>? = null

    fun println(text: String, kind: Int = 0) {
        capture?.let { it += text; return }
        lines += text to kind
    }

    /** Runs a command line as if typed (used by .desktop launchers). */
    fun submitLine(cmd: String) { stateRef?.let { input = cmd; submit(it) } }

    /** Runs blocking DroidSystem work off the main thread, streaming its log into the terminal. */
    fun background(block: suspend ((String) -> Unit) -> Unit) {
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching {
                    block { msg -> scope.launch { lines += msg to (if (msg.startsWith("E:") || msg.startsWith("error")) 2 else 0) } }
                }.onFailure { e -> withContext(Dispatchers.Main) { lines += "error: ${e.message}" to 2 } }
            }
            busy = false
        }
    }

    var busy by mutableStateOf(false)

    fun submit(state: KdxState) {
        stateRef = state
        val cmd = input
        input = ""
        histIdx = -1
        val p = running
        if (p != null) {
            // Feed the running process's stdin, echoing after its prompt like a real terminal.
            if (partialActive && lines.isNotEmpty()) {
                val (text, kind) = lines[lines.lastIndex]
                lines[lines.lastIndex] = "$text\u001B[92m$cmd\u001B[0m" to kind
                partialActive = false
            } else lines += cmd to 1
            runCatching { stdin?.apply { write(cmd + "\n"); flush() } }
            return
        }
        lines += "${prompt()} $cmd" to 1
        val t = cmd.trim()
        if (t.isEmpty()) return
        if (history.lastOrNull() != t) history += t
        when {
            t == "clear" || t == "cls" -> lines.clear()
            t == "exit" -> { lines += "logout" to 2 }
            t == "help" && droid != null -> lines += (DroidShell.help + "Shell built-ins: cd, clear, history, dolphin, kate, gwenview, kcalc").map { it to 2 }
            t == "help" -> lines += listOf(
                "KDX Konsole built-ins:",
                "  cd <dir>     change directory (supports ~ and ..)",
                "  clear        clear the screen",
                "  dolphin [d]  open Dolphin here or in <d>",
                "  kate <file>  edit a file in Kate",
                "  gwenview [f] view images",
                "  kcalc        open the calculator",
                "  history      show command history",
                "Everything else is run with /system/bin/sh -c.",
                "Ctrl+C button stops the running command; input is sent to its stdin.",
            ).map { it to 2 }
            t == "history" -> history.forEachIndexed { i, h -> lines += "%4d  %s".format(i + 1, h) to 0 }
            debian != null && (t == "cd" || t.startsWith("cd ")) -> {
                // resolve inside Debian so symlinks, ~ and $VARS behave exactly like bash
                val deb = debian
                scope.launch {
                    val (rc, out) = withContext(Dispatchers.IO) { runCatching { deb.query("$t && pwd", guestCwd) }.getOrDefault(1 to "cd failed") }
                    if (rc == 0) guestCwd = out.trim().lines().last() else lines += out.trim().ifEmpty { "cd: failed" } to 2
                }
            }
            debian != null && Regex("^export\\s+[A-Za-z_][A-Za-z0-9_]*=.*").matches(t) -> {
                val kv = t.removePrefix("export").trim()
                exports[kv.substringBefore('=')] = kv.substringAfter('=').trim('"', '\'')
            }
            t == "cd" || t.startsWith("cd ") -> {
                val arg = t.removePrefix("cd").trim().trim('"', '\'')
                val target = when {
                    arg.isEmpty() || arg == "~" -> home
                    arg.startsWith("~/") -> File(home, arg.drop(2))
                    arg.startsWith("/") -> File(arg)
                    else -> File(cwd, arg)
                }.let { runCatching { it.canonicalFile }.getOrDefault(it) }
                if (target.isDirectory) cwd = target else lines += "cd: $arg: No such directory" to 2
            }
            t == "dolphin" || t.startsWith("dolphin ") -> state.open(KApp.DOLPHIN, resolve(t.removePrefix("dolphin").trim(), cwd).path)
            t.startsWith("kate ") -> state.open(KApp.KATE, resolve(t.removePrefix("kate").trim(), cwd).path)
            t == "kate" -> state.open(KApp.KATE)
            t == "gwenview" || t.startsWith("gwenview ") -> state.open(KApp.GWENVIEW, resolve(t.removePrefix("gwenview").trim(), cwd).path)
            t == "kcalc" -> state.open(KApp.KCALC)
            else -> { val ds = droid; if (ds == null || !DroidShell.handle(this, state, ds, t)) exec(t) }
        }
    }

    private fun resolve(arg: String, base: File): File {
        val a = arg.trim('"', '\'')
        return when {
            a.isEmpty() -> base
            a.startsWith("/") -> File(a)
            else -> File(base, a)
        }
    }

    /** True while the last line is an unfinished line of program output (e.g. a prompt without newline). */
    private var partialActive = false

    /**
     * Runs [cmd] with /system/bin/sh. In DroidSystem mode the command is prefixed with the system's shell preamble
     * (profile + a function per installed command) so DroidSystem commands also work in pipes and scripts.
     * [stdinData], if given, is fed to the process and its stdin closed (used for `builtin | command`).
     */
    fun exec(cmd: String, stdinData: String? = null) {
        val deb = debian
        val pb = if (deb != null) {
            // Debian bash; DroidSystem's classic commands are added as functions (they live on shared storage)
            val classic = droid?.let { runCatching { it.shellPreamble() }.getOrDefault("") } ?: ""
            deb.command(listOf("/bin/bash", "-lc", "shopt -s expand_aliases; [ -f ~/.bashrc ] && . ~/.bashrc\n$classic$cmd"), guestCwd, guestEnv + exports)
        } else {
            val full = droid?.let { (runCatching { it.shellPreamble() }.getOrDefault("")) + cmd } ?: cmd
            ProcessBuilder("/system/bin/sh", "-c", full).directory(cwd).redirectErrorStream(true).also { it.environment().putAll(env) }
        }
        val proc = runCatching { pb.start() }.getOrElse {
            lines += "konsole: ${it.message}" to 2; return
        }
        running = proc
        if (stdinData != null) {
            stdin = null
            scope.launch(Dispatchers.IO) { runCatching { proc.outputStream.use { it.write(stdinData.toByteArray()) } } }
        } else stdin = OutputStreamWriter(proc.outputStream)
        scope.launch {
            withContext(Dispatchers.IO) {
                val reader = java.io.InputStreamReader(proc.inputStream, Charsets.UTF_8)
                val cb = CharArray(8192)
                val pending = StringBuilder()
                var cr = false
                while (true) {
                    val n = runCatching { reader.read(cb) }.getOrDefault(-1)
                    if (n < 0) break
                    val complete = ArrayList<String>()
                    for (i in 0 until n) {
                        when (val ch = cb[i]) {
                            '\n' -> { complete += clean(pending.toString()); pending.clear(); cr = false }
                            '\r' -> cr = true
                            '\u0007' -> {}
                            else -> { if (cr) { pending.clear(); cr = false }; pending.append(ch) }
                        }
                    }
                    val partial = clean(pending.toString())
                    withContext(Dispatchers.Main) { output(complete, partial) }
                }
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                withContext(Dispatchers.Main) {
                    partialActive = false
                    if (code != 0 && code != 143) lines += "[exit code $code]" to 2
                }
            }
            running = null
            stdin = null
        }
    }

    private fun clean(s: String) = s.replace(ansi, "")

    private fun output(complete: List<String>, partial: String) {
        if (partialActive && lines.isNotEmpty()) lines.removeAt(lines.lastIndex)
        lines.addAll(complete.map { it to 0 })
        partialActive = partial.isNotEmpty()
        if (partialActive) lines += partial to 0
        val overflow = lines.size - 5000
        if (overflow > 0) lines.removeRange(0, overflow)
    }

    fun interrupt() {
        running?.destroy()
        lines += "^C" to 2
    }

    override fun close() {
        running?.destroy()
        scope.cancel()
    }
}

/** Konsole – the KDE terminal emulator (line-based, backed by Android's sh). */
@Composable
fun Konsole(state: KdxState, w: KWindow) {
    val s = remember(w) {
        w.retain("konsole") {
            val ds = state.droid
            val deb = state.debian
            val home = ds?.home ?: state.homeDir()
            val env = mutableMapOf("HOME" to home.path, "TERM" to "dumb")
            if (ds != null) env += mapOf(
                "USER" to "user", "LOGNAME" to "user", "HOSTNAME" to "droidsystem",
                "DROIDSYSTEM_ROOT" to ds.root.path, "XDG_DATA_HOME" to File(home, ".local/share").path,
            )
            KonsoleSession(home.apply { mkdirs() }, env, ds, deb, state.linuxDisplay?.env ?: emptyMap()).also { ks ->
                // An initial command can be passed by KRunner ("> cmd") or Dolphin ("Open terminal here").
                w.arg?.let { ks.input = it; ks.submit(state) }
            }
        }
    }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(s.lines.size) { if (s.lines.isNotEmpty()) list.scrollToItem(s.lines.lastIndex) }
    LaunchedEffect(s.cwd, s.guestCwd, s.running) {
        w.title = if (s.debian != null) "${s.guestCwd.substringAfterLast('/').ifEmpty { "/" }} : ${if (s.running != null) "running" else "bash"} — Konsole"
            else "${s.cwd.name.ifEmpty { "/" }} : ${if (s.running != null) "running" else if (s.droid != null) "droidsystem" else "sh"} — Konsole"
    }
    val bg = Color(0xFF232627)
    val fg = Color(0xFFFCFCFC)
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = s.fontSize.sp, color = fg)

    Column(Modifier.fillMaxSize().background(bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
        ) { runCatching { focus.requestFocus() } }) {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), state = list) {
                itemsIndexed(s.lines) { _, (text, kind) ->
                    val base = when (kind) { 1 -> Color(0xFF1CDC9A); 2 -> Color(0xFF9DA7B3); else -> fg }
                    Text(ansiToAnnotated(text, base), style = style.copy(color = base), softWrap = false)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF31363B)).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.running != null) ">" else s.prompt(), style = style.copy(color = Color(0xFF1CDC9A)), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = s.input, onValueChange = { s.input = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                textStyle = style, singleLine = true, cursorBrush = SolidColor(fg),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { s.submit(state) }),
            )
        }
        // Extra keys row, handy on touch keyboards.
        Row(Modifier.fillMaxWidth().background(Color(0xFF2A2E32)), horizontalArrangement = Arrangement.SpaceEvenly) {
            val keyMod = Modifier.weight(1f)
            ExtraKey("↑", keyMod) {
                if (s.history.isNotEmpty()) {
                    s.histIdx = if (s.histIdx < 0) s.history.lastIndex else (s.histIdx - 1).coerceAtLeast(0)
                    s.input = s.history[s.histIdx]
                }
            }
            ExtraKey("↓", keyMod) {
                if (s.histIdx >= 0) {
                    s.histIdx++
                    if (s.histIdx > s.history.lastIndex) { s.histIdx = -1; s.input = "" } else s.input = s.history[s.histIdx]
                }
            }
            ExtraKey("Tab", keyMod) { complete(s) }
            ExtraKey("|", keyMod) { s.input += "|" }
            ExtraKey("~", keyMod) { s.input += "~" }
            ExtraKey("/", keyMod) { s.input += "/" }
            ExtraKey("^C", keyMod, enabled = s.running != null) { s.interrupt() }
            ExtraKey("A-", keyMod) { s.fontSize = (s.fontSize - 1).coerceAtLeast(8f) }
            ExtraKey("A+", keyMod) { s.fontSize = (s.fontSize + 1).coerceAtMost(28f) }
            ExtraKey("⏎", keyMod) { s.submit(state) }
        }
    }
}

private fun complete(s: KonsoleSession) {
    val deb = s.debian
    val base = if (deb != null) File(deb.root, s.guestCwd.trimStart('/')) else s.cwd
    val parts = s.input.split(' ')
    val last = parts.last()
    val dir = if (last.contains('/')) {
        val p = last.substringBeforeLast('/')
        if (last.startsWith("/")) (if (deb != null) File(deb.root, p.trimStart('/')) else File(p.ifEmpty { "/" })) else File(base, p)
    } else base
    val prefix = last.substringAfterLast('/')
    val matches = dir.list()?.filter { it.startsWith(prefix) }?.sorted() ?: return
    when {
        matches.size == 1 -> {
            val f = File(dir, matches[0])
            val done = last.dropLast(prefix.length) + matches[0] + if (f.isDirectory) "/" else " "
            s.input = (parts.dropLast(1) + done).joinToString(" ")
        }
        matches.size > 1 -> {
            s.lines += matches.joinToString("  ") to 0
            val common = matches.reduce { a, b -> a.commonPrefixWith(b) }
            if (common.length > prefix.length) s.input = (parts.dropLast(1) + (last.dropLast(prefix.length) + common)).joinToString(" ")
        }
    }
}

@Composable
private fun ExtraKey(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(36.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color(0xFFEFF0F1) else Color(0xFF6E7781), fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
}
