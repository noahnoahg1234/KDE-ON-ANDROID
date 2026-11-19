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
import org.kdx.shell.droid.Flathub
import org.kdx.shell.droid.FlathubApp
import org.kdx.shell.droid.FlathubDetails
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

    // ---- Flathub (online catalog) ----
    val sections = mutableStateMapOf<String, List<FlathubApp>>() // popular / trending / recently-added
    var fhError by mutableStateOf<String?>(null)
    var fhOffline by mutableStateOf(false)
    /** "droid", a collection kind ("popular", …) or "cat:<category>"; null = the Explore overview. */
    var browse by mutableStateOf<String?>(null)
    val browseApps = mutableStateListOf<FlathubApp>()
    var browsePage = 1
    var browseMore by mutableStateOf(true)
    var browseLoading by mutableStateOf(false)
    var fhResults by mutableStateOf<List<FlathubApp>>(emptyList())
    var fhSearching by mutableStateOf(false)
    var fhSelected by mutableStateOf<FlathubApp?>(null)
    val fhDetails = mutableStateMapOf<String, FlathubDetails>()
    var fhDetailError by mutableStateOf<String?>(null)

    fun loadFlathub(fh: Flathub, force: Boolean = false) {
        if (sections.isNotEmpty() && !force) return
        scope.launch {
            fhError = null
            for (kind in listOf("trending", "popular", "recently-added")) {
                runCatching { fh.collection(kind, perPage = 20) }
                    .onSuccess { sections[kind] = it; fhOffline = fh.offline }
                    .onFailure { fhError = it.message }
            }
        }
    }

    fun openBrowse(fh: Flathub, what: String?) {
        browse = what
        browseApps.clear(); browsePage = 1; browseMore = what != null && what != "droid"
        if (browseMore) loadMore(fh)
    }

    fun loadMore(fh: Flathub) {
        val what = browse ?: return
        if (browseLoading || !browseMore) return
        browseLoading = true
        scope.launch {
            runCatching {
                if (what.startsWith("cat:")) fh.category(what.removePrefix("cat:"), browsePage) else fh.collection(what, browsePage, 30)
            }.onSuccess { page ->
                if (browse == what) {
                    browseApps.addAll(page.filter { a -> browseApps.none { it.id == a.id } })
                    browsePage++; browseMore = page.size >= 24
                }
            }.onFailure { fhError = it.message; browseMore = false }
            browseLoading = false
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun search(fh: Flathub, q: String) {
        searchJob?.cancel()
        if (q.length < 2) { fhSearching = false; fhResults = emptyList(); return }
        fhSearching = true
        searchJob = scope.launch {
            kotlinx.coroutines.delay(350) // debounce typing
            val r = runCatching { fh.search(q) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) fhError = it.message }
            if (q == query.trim()) { fhResults = r.getOrDefault(emptyList()); fhSearching = false }
        }
    }

    fun openFlathub(fh: Flathub, app: FlathubApp) {
        fhSelected = app; fhDetailError = null
        if (app.id in fhDetails) return
        scope.launch {
            runCatching { fh.details(app) }.onSuccess { fhDetails[app.id] = it }.onFailure { fhDetailError = it.message }
        }
    }

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
    val fh = state.flathub
    val online = state.flathubEnabled
    LaunchedEffect(ds.root.path) { s.load(ds) }
    LaunchedEffect(online) { if (online) s.loadFlathub(fh) }
    LaunchedEffect(s.query, online) { if (online) s.search(fh, s.query.trim()) }
    LaunchedEffect(s.message) { if (s.message != null) { kotlinx.coroutines.delay(3000); s.message = null } }
    val sel = s.items.firstOrNull { it.key == s.selected }
    val fhSel = s.fhSelected
    LaunchedEffect(sel?.name, fhSel?.name) { w.title = (fhSel?.name ?: sel?.name)?.let { "$it — Bazaar" } ?: "Bazaar" }
    val canGoBack = fhSel != null || sel != null || (s.browse != null && s.query.isBlank())
    fun back() {
        when {
            s.fhSelected != null -> s.fhSelected = null
            s.selected != null -> s.selected = null
            else -> s.openBrowse(fh, null)
        }
    }
    var menu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(c.window)) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(Modifier.fillMaxWidth().background(c.header).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canGoBack) IconButton(::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                OutlinedTextField(
                    s.query, { s.query = it; s.selected = null; s.fhSelected = null }, Modifier.weight(1f).heightIn(min = 48.dp),
                    placeholder = { Text(if (online) "Search Flathub and DroidSystem" else "Search apps and packages") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (s.query.isNotEmpty()) IconButton({ s.query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    shape = RoundedCornerShape(24.dp),
                )
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Show Flathub apps") },
                            trailingIcon = { Switch(online, null) },
                            onClick = { state.flathubEnabled = !online; state.save(); menu = false },
                        )
                        DropdownMenuItem(text = { Text("Refresh") }, onClick = {
                            menu = false; s.load(ds); if (online) s.loadFlathub(fh, force = true)
                        })
                    }
                }
            }
            if (sel == null && fhSel == null && s.query.isBlank() && s.browse == null) {
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
                    fhSel != null -> FlathubDetail(state, ds, s, fh, fhSel)
                    sel != null -> AppDetail(state, ds, s, sel)
                    s.query.isNotBlank() -> SearchResults(state, ds, s, fh, online)
                    s.browse != null -> Browse(state, ds, s, fh)
                    s.tab == BazaarTab.EXPLORE -> if (online) FlathubExplore(state, ds, s, fh) else Explore(state, ds, s)
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


// ======================================================================= Flathub UI

/** An icon loaded from Flathub's CDN (cached), with a tinted placeholder while loading. */
@Composable
fun NetImage(fh: Flathub, url: String?, modifier: Modifier, maxPx: Int = 128, contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Fit) {
    val c = LocalBreeze.current
    val img by produceState(url?.let { fh.cachedImage(it) }, url) { if (url != null && value == null) value = fh.image(url, maxPx) }
    val bmp = img
    if (bmp != null) androidx.compose.foundation.Image(bmp, null, modifier, contentScale = contentScale)
    else Box(modifier.background(c.header, RoundedCornerShape(8.dp)))
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = LocalBreeze.current
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
private fun FlathubCard(fh: Flathub, app: FlathubApp, width: Dp?, onClick: () -> Unit) {
    val c = LocalBreeze.current
    Surface(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp), color = c.view, border = BorderStroke(1.dp, c.border.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetImage(fh, app.icon, Modifier.size(48.dp))
                Spacer(Modifier.weight(1f))
                if (app.verified) Icon(Icons.Filled.Verified, "Verified", tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(app.name, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.summary, color = c.inactiveText, fontSize = 12.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Flathub", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                if (app.installsLastMonth > 0) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.Download, null, tint = c.inactiveText, modifier = Modifier.size(12.dp))
                    Text(Flathub.formatInstalls(app.installsLastMonth), color = c.inactiveText, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner(s: BazaarSession) {
    val c = LocalBreeze.current
    val msg = when {
        s.fhOffline -> "You're offline – showing the Flathub catalog Bazaar saw last time."
        s.fhError != null && s.sections.isEmpty() -> "Couldn't load Flathub (${s.fhError}). DroidSystem apps are still available."
        else -> return
    }
    Row(Modifier.fillMaxWidth().background(c.neutral.copy(alpha = 0.15f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = c.neutral, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(msg, color = c.text, fontSize = 12.sp)
    }
}

@Composable
private fun FlathubExplore(state: KdxState, ds: DroidSystem, s: BazaarSession, fh: Flathub) {
    val c = LocalBreeze.current
    val featured = s.sections["trending"]?.firstOrNull() ?: s.sections["popular"]?.firstOrNull()
    val local = s.items.filter { it.flatpak && it.inRepo }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { OfflineBanner(s) }
        if (featured != null) item {
            Row(
                Modifier.padding(10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(c.accent, c.accent.copy(alpha = 0.55f), Color(0xFF2C3E50))))
                    .clickable { s.openFlathub(fh, featured) }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("TRENDING ON FLATHUB", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(featured.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(featured.summary, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 2)
                }
                Spacer(Modifier.width(12.dp))
                NetImage(fh, featured.icon, Modifier.size(72.dp))
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { AssistChip({ s.openBrowse(fh, "droid") }, label = { Text("Runs on DroidSystem") }, leadingIcon = { Icon(Icons.Filled.Terminal, null, Modifier.size(16.dp)) }) }
                items(fh.categories) { (id, label) ->
                    AssistChip({ s.openBrowse(fh, "cat:$id") }, label = { Text(label) }, leadingIcon = { Icon(flathubCategoryIcon(id), null, Modifier.size(16.dp)) })
                }
            }
        }
        item { SectionHeader("Runs on DroidSystem", "See all") { s.openBrowse(fh, "droid") } }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(local, key = { it.key }) { Box(Modifier.width(170.dp)) { AppCard(state, ds, s, it) } }
            }
        }
        listOf("trending" to "Trending on Flathub", "popular" to "Popular on Flathub", "recently-added" to "New on Flathub").forEach { (kind, title) ->
            val apps = s.sections[kind]
            item { SectionHeader(title, "See all") { s.openBrowse(fh, kind) } }
            item {
                if (apps == null) Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    if (s.fhError == null) CircularProgressIndicator(Modifier.size(28.dp)) else Text("Unavailable", color = c.inactiveText)
                } else LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(apps, key = { "$kind:${it.id}" }) { a -> FlathubCard(fh, a, 170.dp) { s.openFlathub(fh, a) } }
                }
            }
        }
        item {
            Text(
                "Catalog from flathub.org. Flathub apps are desktop Linux programs: you can browse them and save them for a " +
                    "Linux PC, while apps marked \"Runs on DroidSystem\" install and run on this phone.",
                Modifier.padding(14.dp), color = c.inactiveText, fontSize = 12.sp,
            )
        }
    }
}

private fun flathubCategoryIcon(id: String): ImageVector = when (id) {
    "audiovideo" -> Icons.Filled.Movie
    "development" -> Icons.Filled.Code
    "education" -> Icons.Filled.School
    "game" -> Icons.Filled.SportsEsports
    "graphics" -> Icons.Filled.Brush
    "network" -> Icons.Filled.Language
    "office" -> Icons.Filled.Description
    "science" -> Icons.Filled.Science
    "system" -> Icons.Filled.Settings
    else -> Icons.Filled.Build
}

@Composable
private fun Browse(state: KdxState, ds: DroidSystem, s: BazaarSession, fh: Flathub) {
    val c = LocalBreeze.current
    val what = s.browse ?: return
    val title = when {
        what == "droid" -> "Runs on DroidSystem"
        what.startsWith("cat:") -> fh.categoryLabel(what.removePrefix("cat:"))
        what == "trending" -> "Trending on Flathub"
        what == "popular" -> "Popular on Flathub"
        what == "recently-added" -> "New on Flathub"
        else -> what
    }
    if (what == "droid") {
        Column { SectionHeader(title); Explore(state, ds, s) }
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        items(s.browseApps, key = { it.id }) { a -> FlathubCard(fh, a, null) { s.openFlathub(fh, a) } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                when {
                    s.browseLoading -> CircularProgressIndicator(Modifier.size(28.dp))
                    s.browseMore -> OutlinedButton({ s.loadMore(fh) }) { Text("Load more") }
                    s.browseApps.isEmpty() -> Text("Nothing here (are you offline?)", color = c.inactiveText)
                }
            }
        }
    }
    // automatically fetch the first page
    LaunchedEffect(what) { if (s.browseApps.isEmpty()) s.loadMore(fh) }
}

@Composable
private fun SearchResults(state: KdxState, ds: DroidSystem, s: BazaarSession, fh: Flathub, online: Boolean) {
    val c = LocalBreeze.current
    val q = s.query.trim()
    val local = s.items.filter {
        it.name.contains(q, true) || it.id.contains(q, true) || it.summary.contains(q, true) || it.category.contains(q, true)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (local.isNotEmpty()) {
            item { SectionHeader("Runs on DroidSystem") }
            items(local, key = { it.key }) { item ->
                Row(Modifier.fillMaxWidth().clickable { s.selected = item.key }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StoreIcon(item, 40.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = c.text, fontWeight = FontWeight.Medium)
                        Text(item.summary, color = c.inactiveText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ActionButton(state, ds, s, item, compact = true)
                }
            }
        }
        if (online) {
            item { SectionHeader("Flathub") }
            if (s.fhSearching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) }
            items(s.fhResults, key = { "fh:" + it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { s.openFlathub(fh, a) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    NetImage(fh, a.icon, Modifier.size(40.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.name, color = c.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (a.verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Verified, null, tint = c.accent, modifier = Modifier.size(14.dp)) }
                        }
                        Text(a.summary, color = c.inactiveText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!s.fhSearching && s.fhResults.isEmpty() && q.length >= 2) item {
                Text("No Flathub results for \"$q\"", Modifier.padding(14.dp), color = c.inactiveText)
            }
        }
    }
}

@Composable
private fun FlathubDetail(state: KdxState, ds: DroidSystem, s: BazaarSession, fh: Flathub, app: FlathubApp) {
    val c = LocalBreeze.current
    val d = s.fhDetails[app.id]
    val a = d?.app ?: app
    var getDialog by remember { mutableStateOf(false) }
    val runnable = remember(a.id, s.items) { s.items.firstOrNull { it.flatpak && it.id == a.id } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetImage(fh, a.icon, Modifier.size(88.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.developer, color = c.accent, fontSize = 14.sp)
                    if (a.verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Verified, "Verified", tint = c.accent, modifier = Modifier.size(16.dp)) }
                }
                Text(a.summary, color = c.inactiveText, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (runnable != null) ActionButton(state, ds, s, runnable, compact = false)
            else Button({ getDialog = true }) { Text("Install") }
            OutlinedButton({ state.openUrl("https://flathub.org/apps/${a.id}") }) { Text("Flathub page") }
        }
        if (runnable == null) Text(
            "Desktop Linux app (${a.arches.joinToString().ifEmpty { "Flatpak" }})",
            Modifier.padding(top = 6.dp), color = c.inactiveText, fontSize = 12.sp,
        )
        if (d == null) {
            Spacer(Modifier.height(24.dp))
            if (s.fhDetailError != null) Text("Couldn't load details: ${s.fhDetailError}", color = c.negative, fontSize = 13.sp)
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        if (d.screenshots.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(d.screenshots) { url ->
                    NetImage(fh, url, Modifier.height(200.dp).width(340.dp).clip(RoundedCornerShape(8.dp)), maxPx = 800,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(d.description, color = c.text, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(10.dp), color = c.view, border = BorderStroke(1.dp, c.border.copy(alpha = 0.6f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                listOfNotNull(
                    d.version?.let { "Version" to it },
                    "Developer" to a.developer,
                    a.license.takeIf { it.isNotEmpty() }?.let { "License" to it.replace("LicenseRef-proprietary", "Proprietary") },
                    d.downloadSize?.let { "Download" to humanSize(it) },
                    d.installedSize?.let { "Installed size" to humanSize(it) },
                    a.installsLastMonth.takeIf { it > 0 }?.let { "Installs" to "${Flathub.formatInstalls(it)} last month" },
                    "Architectures" to a.arches.joinToString().ifEmpty { "?" },
                    "Category" to fh.categoryLabel(a.category),
                    "ID" to a.id,
                ).forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(k, Modifier.width(110.dp), color = c.inactiveText, fontSize = 13.sp)
                        Text(v, color = c.text, fontSize = 13.sp)
                    }
                }
                d.homepage?.let { h -> TextButton({ state.openUrl(h) }, contentPadding = PaddingValues(0.dp)) { Text("Website") } }
            }
        }
        if (d.permissions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Permissions", color = c.text, fontWeight = FontWeight.SemiBold)
            d.permissions.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, null, tint = c.inactiveText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(p, color = c.text, fontSize = 13.sp)
                }
            }
        }
    }
    if (getDialog) AlertDialog(
        onDismissRequest = { getDialog = false },
        icon = { Icon(Icons.Filled.Info, null) },
        title = { Text("Install ${a.name}?") },
        text = {
            Text(
                "${a.name} is a desktop Linux app from Flathub. Android doesn't let apps run desktop Linux programs, so it " +
                    "can't run on this phone, even inside DroidSystem.\n\nYou can save its .flatpakref to install it on a Linux PC " +
                    "(flatpak install ${a.id}.flatpakref), or look for an Android version.",
            )
        },
        confirmButton = {
            TextButton({
                getDialog = false
                s.scope.launch {
                    runCatching { fh.saveFlatpakref(a.id, java.io.File(ds.home, "Downloads")) }
                        .onSuccess { s.message = "Saved ${it.name} to DroidSystem Downloads" }
                        .onFailure { s.message = "Couldn't download: ${it.message}" }
                }
            }) { Text("Save .flatpakref") }
        },
        dismissButton = {
            TextButton({ getDialog = false; state.openUrl("https://play.google.com/store/search?q=" + android.net.Uri.encode(a.name) + "&c=apps") }) {
                Text("Find on Google Play")
            }
        },
    )
}
