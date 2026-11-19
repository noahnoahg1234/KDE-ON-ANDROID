package org.kdx.shell.apps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.droid.DroidSystem
import java.io.Closeable

/** One thing Bazaar can install: a Flatpak from DroidHub or a dspkg package. */
data class StoreItem(
    val flatpak: Boolean,
    val id: String,
    val name: String,
    val summary: String,
    val description: String,
    val category: String,
    val version: String,
    val developer: String,
    val source: String,
    val installedVersion: String?,
    val permissions: List<String> = emptyList(),
    val runtime: String = "",
    val inRepo: Boolean = true,
) {
    val installed get() = installedVersion != null
    val updatable get() = installed && inRepo && installedVersion != version
    val key get() = (if (flatpak) "fp:" else "pkg:") + id
}

enum class BazaarTab(val label: String, val icon: ImageVector) {
    EXPLORE("Explore", Icons.Filled.Explore),
    INSTALLED("Installed", Icons.Filled.LibraryAddCheck),
    UPDATES("Updates", Icons.Filled.Update),
}

class BazaarSession : Closeable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var items by mutableStateOf<List<StoreItem>>(emptyList())
    var loading by mutableStateOf(true)
    val busy = mutableStateListOf<String>()
    var selected by mutableStateOf<String?>(null)
    var tab by mutableStateOf(BazaarTab.EXPLORE)
    var query by mutableStateOf("")
    var category by mutableStateOf("All")
    var message by mutableStateOf<String?>(null)

    fun load(ds: DroidSystem) {
        scope.launch {
            loading = items.isEmpty()
            items = withContext(Dispatchers.IO) { catalog(ds) }
            loading = false
        }
    }

    /** Runs an install/remove off the main thread, then refreshes the catalog and Kickoff. */
    fun run(state: KdxState, ds: DroidSystem, item: StoreItem, verb: String, block: (DroidSystem, (String) -> Unit) -> Unit) {
        if (item.key in busy) return
        busy += item.key
        scope.launch {
            val log = mutableListOf<String>()
            val r = withContext(Dispatchers.IO) { runCatching { block(ds) { log += it } } }
            items = withContext(Dispatchers.IO) { catalog(ds) }
            busy -= item.key
            message = r.fold({ "$verb ${item.name}" }, { "${item.name}: ${it.message}" })
            state.refreshLinuxApps()
        }
    }

    override fun close() = scope.cancel()
}

private fun catalog(ds: DroidSystem): List<StoreItem> {
    val installedFp = ds.flatpakInstalled().associateBy { it.id }
    val remote = ds.flatpakRemote()
    val fps = remote.map { f ->
        StoreItem(true, f.id, f.name, f.comment, f.description, f.category, f.version, f.developer, "DroidHub (Flatpak)",
            installedFp[f.id]?.version, f.permissions, f.runtime)
    } + installedFp.values.filter { i -> remote.none { it.id == i.id } }.map { f ->
        StoreItem(true, f.id, f.name, f.comment, f.description, f.category, f.version, f.developer, "Local Flatpak",
            f.version, f.permissions, f.runtime, inRepo = false)
    }
    val installedPkgs = ds.installed().filter { it.name != "ds-base" }.associateBy { it.name }
    val repo = ds.available()
    val pkgs = repo.map { p ->
        StoreItem(false, p.name, p.name, p.description, p.description + "\n\nCommand-line tool from DroidSystem's main repository. " +
            "Run it in Konsole by typing ${p.name}.", p.category, p.version, "DroidSystem Project", "main (dspkg)", installedPkgs[p.name]?.version)
    } + installedPkgs.values.filter { i -> repo.none { it.name == i.name } }.map { p ->
        StoreItem(false, p.name, p.name.replaceFirstChar { it.uppercase() }, p.description, p.description, p.category, p.version,
            if (p.name == "bazaar") "DroidSystem Project" else "Installed from file", when (p.source) {
                "deb" -> "Debian package"; "rpm" -> "RPM package"; "arch" -> "Arch package"; "local" -> "DroidSystem package"; else -> p.source
            }, p.version, inRepo = p.name == "bazaar")
    }
    return (fps + pkgs).sortedBy { it.name.lowercase() }
}

private fun categoryIcon(c: String): ImageVector = when (c.lowercase()) {
    "games", "game" -> Icons.Filled.SportsEsports
    "utilities", "utility" -> Icons.Filled.Build
    "office" -> Icons.Filled.Description
    "system" -> Icons.Filled.Settings
    "fun" -> Icons.Filled.EmojiEmotions
    "development" -> Icons.Filled.Code
    "education" -> Icons.Filled.School
    else -> Icons.Filled.Apps
}

private val tileColors = listOf(
    0xFF1D99F3, 0xFF9B59B6, 0xFF27AE60, 0xFFF67400, 0xFFE93A9A, 0xFF16A085, 0xFFDA4453, 0xFF2C3E50, 0xFFFDBC4B, 0xFF3DAEE9,
)

@Composable
private fun StoreIcon(item: StoreItem, size: Dp) {
    if (item.id == "bazaar") { org.kdx.shell.shell.KAppIcon(KApp.BAZAAR, size); return }
    val base = Color(tileColors[(item.id.hashCode() and 0x7fffffff) % tileColors.size])
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4))
            .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.7f)))),
        contentAlignment = Alignment.Center,
    ) { Icon(categoryIcon(item.category), null, tint = Color.White, modifier = Modifier.size(size * 0.55f)) }
}

/** Bazaar – DroidSystem's graphical app store for Flatpaks and dspkg packages. */
@Composable
fun Bazaar(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val ds = state.droid
    val s = remember(w) { w.retain("bazaar") { BazaarSession() } }
    if (ds == null) {
        Box(Modifier.fillMaxSize().background(c.window).padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Bazaar needs the Linux system. Turn on DroidSystem in System Settings.", color = c.inactiveText)
        }
        return
    }
    LaunchedEffect(ds.root.path) { s.load(ds) }
    LaunchedEffect(s.message) { if (s.message != null) { kotlinx.coroutines.delay(3000); s.message = null } }
    val sel = s.items.firstOrNull { it.key == s.selected }
    LaunchedEffect(sel?.name) { w.title = if (sel != null) "${sel.name} — Bazaar" else "Bazaar" }

    Box(Modifier.fillMaxSize().background(c.window)) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(Modifier.fillMaxWidth().background(c.header).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sel != null) IconButton({ s.selected = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                OutlinedTextField(
                    s.query, { s.query = it; s.selected = null }, Modifier.weight(1f).heightIn(min = 48.dp),
                    placeholder = { Text("Search apps and packages") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (s.query.isNotEmpty()) IconButton({ s.query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    shape = RoundedCornerShape(24.dp),
                )
            }
            if (sel == null && s.query.isBlank()) {
                TabRow(BazaarTab.entries.indexOf(s.tab), containerColor = c.header) {
                    BazaarTab.entries.forEach { t ->
                        val n = when (t) {
                            BazaarTab.INSTALLED -> s.items.count { it.installed }
                            BazaarTab.UPDATES -> s.items.count { it.updatable }
                            else -> 0
                        }
                        Tab(s.tab == t, { s.tab = t }, text = { Text(if (n > 0) "${t.label} ($n)" else t.label) },
                            icon = { Icon(t.icon, null, Modifier.size(20.dp)) })
                    }
                }
            }
            HorizontalDivider(color = c.border)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    s.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    sel != null -> AppDetail(state, ds, s, sel)
                    s.query.isNotBlank() -> {
                        val q = s.query.trim()
                        val res = s.items.filter {
                            it.name.contains(q, true) || it.id.contains(q, true) || it.summary.contains(q, true) || it.category.contains(q, true)
                        }
                        if (res.isEmpty()) Text("No results for \"$q\"", Modifier.align(Alignment.Center), color = c.inactiveText)
                        else ItemList(state, ds, s, res)
                    }
                    s.tab == BazaarTab.EXPLORE -> Explore(state, ds, s)
                    s.tab == BazaarTab.INSTALLED -> {
                        val list = s.items.filter { it.installed }
                        if (list.isEmpty()) Text("Nothing installed yet", Modifier.align(Alignment.Center), color = c.inactiveText)
                        else ItemList(state, ds, s, list)
                    }
                    else -> Updates(state, ds, s)
                }
            }
        }
        s.message?.let {
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(16.dp), shape = RoundedCornerShape(8.dp),
                color = c.text, shadowElevation = 6.dp,
            ) { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = c.window, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun Explore(state: KdxState, ds: DroidSystem, s: BazaarSession) {
    val c = LocalBreeze.current
    val cats = listOf("All") + s.items.map { it.category }.distinct().sorted()
    val shown = s.items.filter { s.category == "All" || it.category == s.category }
    val featured = remember(s.items.size) { s.items.filter { it.flatpak && !it.installed }.randomOrNull() ?: s.items.firstOrNull { it.flatpak } }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (featured != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(c.accent, c.accent.copy(alpha = 0.55f), Color(0xFF2C3E50))))
                    .clickable { s.selected = featured.key }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("FEATURED", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(featured.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(featured.summary, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 2)
                }
                Spacer(Modifier.width(12.dp))
                StoreIcon(featured, 64.dp)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cats) { cat ->
                    FilterChip(s.category == cat, { s.category = cat }, label = { Text(cat) },
                        leadingIcon = if (cat != "All") ({ Icon(categoryIcon(cat), null, Modifier.size(16.dp)) }) else null)
                }
            }
        }
        items(shown, key = { it.key }) { item -> AppCard(state, ds, s, item) }
    }
}

@Composable
private fun AppCard(state: KdxState, ds: DroidSystem, s: BazaarSession, item: StoreItem) {
    val c = LocalBreeze.current
    Surface(
        Modifier.fillMaxWidth().clickable { s.selected = item.key }, shape = RoundedCornerShape(10.dp), color = c.view,
        border = BorderStroke(1.dp, c.border.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoreIcon(item, 44.dp)
                Spacer(Modifier.weight(1f))
                if (item.installed) Icon(Icons.Filled.CheckCircle, "Installed", tint = c.positive, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(item.name, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.summary, color = c.inactiveText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            Spacer(Modifier.height(6.dp))
            Text(if (item.flatpak) "Flatpak" else "Package", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ItemList(state: KdxState, ds: DroidSystem, s: BazaarSession, list: List<StoreItem>) {
    val c = LocalBreeze.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.key }) { item ->
            Row(
                Modifier.fillMaxWidth().clickable { s.selected = item.key }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StoreIcon(item, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = c.text, fontWeight = FontWeight.Medium)
                    Text("${item.summary} · ${if (item.flatpak) "Flatpak" else "Package"}", color = c.inactiveText, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ActionButton(state, ds, s, item, compact = true)
            }
            HorizontalDivider(color = c.border.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun Updates(state: KdxState, ds: DroidSystem, s: BazaarSession) {
    val c = LocalBreeze.current
    val ups = s.items.filter { it.updatable }
    Column(Modifier.fillMaxSize()) {
        if (ups.isEmpty()) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CheckCircle, null, tint = c.positive, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("Everything is up to date", color = c.text, fontSize = 18.sp)
                Text("DroidHub and the main repository ship with KDX and update with it.", color = c.inactiveText, fontSize = 13.sp)
            }
        } else {
            Button({ ups.forEach { u -> s.run(state, ds, u, "Updated") { d, log -> if (u.flatpak) d.flatpakInstallBundled(u.id, log) else d.installFromRepo(u.id, log) } } },
                Modifier.padding(12.dp).fillMaxWidth()) { Text("Update All (${ups.size})") }
            ItemList(state, ds, s, ups)
        }
    }
}

@Composable
private fun ActionButton(state: KdxState, ds: DroidSystem, s: BazaarSession, item: StoreItem, compact: Boolean) {
    val c = LocalBreeze.current
    if (item.key in s.busy) {
        CircularProgressIndicator(Modifier.size(if (compact) 24.dp else 32.dp), strokeWidth = 3.dp); return
    }
    fun install() = s.run(state, ds, item, "Installed") { d, log ->
        if (item.flatpak) d.flatpakInstallBundled(item.id, log) else d.installFromRepo(item.id, log)
    }
    fun open() {
        if (item.id == "bazaar") return
        state.open(KApp.KONSOLE, if (item.flatpak) "flatpak run ${item.id}" else item.id)
    }
    when {
        !item.installed -> Button(::install) { Text("Install") }
        item.updatable -> Button(::install) { Text("Update") }
        compact -> OutlinedButton(::open, enabled = item.id != "bazaar") { Text(if (item.id == "bazaar") "Open" else "Run") }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.id != "bazaar") Button(::open) { Text("Open") }
            OutlinedButton({
                s.run(state, ds, item, "Removed") { d, log -> if (item.flatpak) d.flatpakUninstall(item.id) else d.remove(item.id, log) }
            }) { Text("Remove", color = c.negative) }
        }
    }
}

@Composable
private fun AppDetail(state: KdxState, ds: DroidSystem, s: BazaarSession, item: StoreItem) {
    val c = LocalBreeze.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StoreIcon(item, 88.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(item.developer, color = c.accent, fontSize = 14.sp)
                Text(item.summary, color = c.inactiveText, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        ActionButton(state, ds, s, item, compact = false)
        Spacer(Modifier.height(18.dp))
        Text(item.description, color = c.text, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(18.dp))
        Surface(shape = RoundedCornerShape(10.dp), color = c.view, border = BorderStroke(1.dp, c.border.copy(alpha = 0.6f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                listOfNotNull(
                    "Version" to item.version + (item.installedVersion?.takeIf { it != item.version }?.let { " (installed $it)" } ?: ""),
                    "Category" to item.category,
                    "Source" to item.source,
                    "Type" to if (item.flatpak) "Flatpak application" else "dspkg package",
                    "ID" to item.id,
                    item.runtime.takeIf { it.isNotEmpty() }?.let { "Runtime" to it },
                    "Status" to if (item.installed) "Installed" else "Not installed",
                ).forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(k, Modifier.width(96.dp), color = c.inactiveText, fontSize = 13.sp)
                        Text(v, color = c.text, fontSize = 13.sp)
                    }
                }
            }
        }
        if (item.permissions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Permissions", color = c.text, fontWeight = FontWeight.SemiBold)
            item.permissions.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, null, tint = c.inactiveText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(p.replace("filesystems: home", "Can read and write your DroidSystem home folder"), color = c.text, fontSize = 13.sp)
                }
            }
        }
    }
}
