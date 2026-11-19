package org.kdx.shell.shell

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kdx.shell.core.Calc
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.core.Units

/** A single KRunner match. */
class RunnerMatch(
    val title: String,
    val subtitle: String,
    val icon: ImageVector? = null,
    val launchable: Launchable? = null,
    val run: () -> Unit,
)

/** Web shortcuts, as in KDE's "Web Search Keywords". */
private val webShortcuts = listOf(
    Triple("gg", "Google", "https://www.google.com/search?q="),
    Triple("dd", "DuckDuckGo", "https://duckduckgo.com/?q="),
    Triple("wp", "Wikipedia", "https://en.wikipedia.org/wiki/Special:Search?search="),
    Triple("yt", "YouTube", "https://www.youtube.com/results?search_query="),
    Triple("gh", "GitHub", "https://github.com/search?q="),
    Triple("kde", "KDE Store", "https://store.kde.org/search?search="),
    Triple("map", "OpenStreetMap", "https://www.openstreetmap.org/search?query="),
    Triple("play", "Google Play", "https://play.google.com/store/search?q="),
)

private val settingsActions = listOf(
    Triple("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, Icons.Filled.Wifi),
    Triple("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, Icons.Filled.Bluetooth),
    Triple("Display", Settings.ACTION_DISPLAY_SETTINGS, Icons.Filled.BrightnessMedium),
    Triple("Sound", Settings.ACTION_SOUND_SETTINGS, Icons.Filled.VolumeUp),
    Triple("Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS, Icons.Filled.BatterySaver),
    Triple("Applications", Settings.ACTION_APPLICATION_SETTINGS, Icons.Filled.Apps),
    Triple("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, Icons.Filled.LocationOn),
    Triple("Date & time", Settings.ACTION_DATE_SETTINGS, Icons.Filled.Schedule),
    Triple("Language", Settings.ACTION_LOCALE_SETTINGS, Icons.Filled.Language),
    Triple("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, Icons.Filled.Accessibility),
    Triple("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Icons.Filled.DeveloperMode),
    Triple("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Icons.Filled.Storage),
    Triple("Security", Settings.ACTION_SECURITY_SETTINGS, Icons.Filled.Security),
)

fun runnerMatches(state: KdxState, raw: String): List<RunnerMatch> {
    val q = raw.trim()
    if (q.isEmpty()) return emptyList()
    val out = mutableListOf<RunnerMatch>()

    // Calculator
    val expr = q.removePrefix("=")
    Calc.tryEval(expr)?.let { v ->
        if (expr.any { !it.isDigit() && it != '.' } || q.startsWith("=")) {
            val r = Calc.format(v)
            out += RunnerMatch("= $r", "Calculator – tap to copy", Icons.Filled.Calculate) { state.copyText(r); state.toast("Copied $r") }
        }
    }
    // Unit conversion
    Units.convert(q)?.let { r ->
        out += RunnerMatch(r, "Unit Converter – tap to copy", Icons.Filled.SwapHoriz) {
            state.copyText(r.substringAfter("= ")); state.toast("Copied")
        }
    }
    // Web shortcuts  "gg:kde plasma"
    val colon = q.indexOf(':')
    if (colon > 0) {
        val kw = q.substring(0, colon).lowercase()
        val term = q.substring(colon + 1).trim()
        webShortcuts.firstOrNull { it.first == kw }?.let { (_, name, url) ->
            if (term.isNotEmpty()) out += RunnerMatch("Search $name for \"$term\"", "Web Search Keywords", Icons.Filled.TravelExplore) {
                state.openUrl(url + Uri.encode(term))
            }
        }
    }
    // URLs
    if (Regex("""^(https?://)?[\w-]+(\.[\w-]+)+(/\S*)?$""").matches(q) && !q.first().isDigit()) {
        val url = if (q.startsWith("http")) q else "https://$q"
        out += RunnerMatch("Open $url", "Location", Icons.Filled.Public) { state.openUrl(url) }
    }
    // Shell command "> ls -la" or "$ cmd" runs in Konsole
    if (q.startsWith(">") || q.startsWith("$")) {
        val cmd = q.drop(1).trim()
        if (cmd.isNotEmpty()) out += RunnerMatch("Run \"$cmd\" in Konsole", "Command Line", Icons.Filled.Terminal) {
            state.open(KApp.KONSOLE, cmd)
        }
    }
    // Paths
    if (q.startsWith("/") || q.startsWith("~")) {
        val path = if (q.startsWith("~")) state.homeDir().path + q.drop(1) else q
        out += RunnerMatch("Open $path", "Dolphin", Icons.Filled.Folder) { state.open(KApp.DOLPHIN, path) }
    }
    // Desktops
    Regex("""^(?:desktop|desk)\s*(\d)$""", RegexOption.IGNORE_CASE).matchEntire(q)?.let {
        val d = it.groupValues[1].toInt() - 1
        if (d in 0 until state.desktopCount) out += RunnerMatch("Switch to Desktop ${d + 1}", "Virtual Desktops", Icons.Filled.ViewCarousel) {
            state.closeMenus(); state.switchDesktop(d)
        }
    }
    // Applications
    searchResults(state, q).take(12).forEach { l ->
        out += RunnerMatch(l.label, l.subtitle, launchable = l) { state.launch(l) }
    }
    // Open windows
    state.windows.filter { it.title.contains(q, ignoreCase = true) }.forEach { w ->
        out += RunnerMatch("Switch to ${w.title}", "Window on Desktop ${w.desktop + 1}", w.app.icon) { state.closeMenus(); state.focus(w) }
    }
    // Android settings pages
    settingsActions.filter { it.first.contains(q, ignoreCase = true) }.forEach { (name, action, icon) ->
        out += RunnerMatch(name, "Android Settings", icon) { state.openIntent(Intent(action)) }
    }
    // Clipboard history
    state.clipboardHistory.filter { q.length >= 3 && it.contains(q, ignoreCase = true) }.take(3).forEach { t ->
        out += RunnerMatch(t.lines().first().take(60), "Clipboard – tap to copy", Icons.Filled.ContentPaste) { state.copyText(t); state.closeMenus() }
    }
    // Fallback web search
    if (out.none { it.launchable != null } && colon < 0) {
        out += RunnerMatch("Search the web for \"$q\"", "Web Search (DuckDuckGo)", Icons.Filled.TravelExplore) {
            state.openUrl("https://duckduckgo.com/?q=" + Uri.encode(q))
        }
    }
    return out
}

/** KRunner, the Plasma search-and-run bar. */
@Composable
fun KRunner(state: KdxState) {
    val c = LocalBreeze.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val matches = remember(query, state.androidApps, state.windows.size) { runnerMatches(state, query) }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f)).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
    ) { state.krunnerOpen = false }.statusBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Surface(
            Modifier.padding(12.dp).widthIn(max = 640.dp).fillMaxWidth().clickable(enabled = false) {},
            shape = RoundedCornerShape(10.dp), color = c.window, shadowElevation = 16.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
        ) {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(8.dp).focusRequester(focus),
                    placeholder = { Text("Search apps, calculate, convert, gg:query, > command…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { IconButton({ if (query.isEmpty()) state.krunnerOpen = false else query = "" }) { Icon(Icons.Filled.Close, "Close") } },
                    singleLine = true, shape = RoundedCornerShape(6.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { matches.firstOrNull()?.run?.invoke() }),
                )
                if (query.isBlank()) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        Text("Try:", color = c.inactiveText, fontSize = 12.sp)
                        listOf("2^10 + sqrt(81)", "10 km to mi", "gg:plasma mobile", "> uname -a", "~/Download", "desktop 2")
                            .forEach { ex ->
                                Text(ex, color = c.accent, fontSize = 13.sp, modifier = Modifier.clickable { query = ex }.padding(vertical = 3.dp))
                            }
                    }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(matches) { i, m ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (i == 0) c.accent.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { m.run() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            when {
                                m.launchable != null -> LaunchableIcon(m.launchable, 32.dp)
                                m.icon != null -> Icon(m.icon, null, tint = c.accent, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(m.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(m.subtitle, color = c.inactiveText, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
