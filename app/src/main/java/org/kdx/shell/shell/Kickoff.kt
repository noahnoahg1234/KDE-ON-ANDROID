package org.kdx.shell.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze

private enum class KickoffTab(val label: String, val icon: ImageVector) {
    FAVORITES("Favorites", Icons.Filled.Star),
    APPS("Applications", Icons.Filled.Apps),
    PLACES("Places", Icons.Filled.Folder),
    POWER("Leave", Icons.Filled.PowerSettingsNew),
}

/** Kickoff, the Plasma application launcher. */
@Composable
fun Kickoff(state: KdxState, compact: Boolean) {
    val c = LocalBreeze.current
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(KickoffTab.FAVORITES) }
    var category by remember { mutableStateOf("All") }

    Box(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(start = 4.dp, end = 4.dp, top = if (state.panelOnTop) 50.dp else 8.dp, bottom = if (state.panelOnTop) 8.dp else 50.dp),
        contentAlignment = if (state.panelOnTop) Alignment.TopStart else Alignment.BottomStart,
    ) {
        Surface(
            Modifier
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(620.dp))
                .fillMaxHeight(if (compact) 0.85f else 0.75f)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(10.dp),
            color = c.window.copy(alpha = 0.98f),
            shadowElevation = 16.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
        ) {
            Column {
                // Header: user + search
                Row(
                    Modifier.fillMaxWidth().background(c.header).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KdxLogo(36.dp)
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        placeholder = { Text("Search…") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            searchResults(state, query).firstOrNull()?.let { state.launch(it) }
                        }),
                    )
                }
                HorizontalDivider(color = c.border)

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (query.isNotBlank()) {
                        val results = searchResults(state, query)
                        if (results.isEmpty()) {
                            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                                Text("No matches", color = c.inactiveText)
                                TextButton({ state.kickoffOpen = false; state.krunnerOpen = true }) { Text("Search with KRunner") }
                            }
                        } else LazyColumn(Modifier.fillMaxSize()) {
                            items(results, key = { it.key }) { AppRow(state, it) }
                        }
                    } else when (tab) {
                        KickoffTab.FAVORITES -> {
                            val favs = state.favorites.mapNotNull { state.resolve(it) }
                            if (favs.isEmpty()) Text(
                                "Long-press any application to add it to Favorites.",
                                Modifier.padding(24.dp), color = c.inactiveText,
                            )
                            LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxSize().padding(6.dp)) {
                                items(favs, key = { it.key }) { AppTile(state, it) }
                            }
                        }
                        KickoffTab.APPS -> Column {
                            val cats = listOf("All", "KDX") + KApp.entries.map { it.category }.distinct() +
                                (if (state.linuxApps.isNotEmpty()) listOf("Linux") else emptyList()) + "Android"
                            LazyRow(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(cats) { cat ->
                                    FilterChip(category == cat, { category = cat }, label = { Text(cat) })
                                }
                            }
                            val list = state.launchables().filter {
                                when (category) {
                                    "All" -> true
                                    "KDX" -> it is Launchable.Kdx
                                    "Android" -> it is Launchable.Droid
                                    "Linux" -> it is Launchable.Linux
                                    else -> it is Launchable.Kdx && it.app.category == category
                                }
                            }.sortedBy { it.label.lowercase() }
                            if (state.appsLoading && category != "KDX") LinearProgressIndicator(Modifier.fillMaxWidth())
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(list, key = { it.key }) { AppRow(state, it) }
                            }
                        }
                        KickoffTab.PLACES -> PlacesList(state)
                        KickoffTab.POWER -> PowerList(state)
                    }
                }

                HorizontalDivider(color = c.border)
                Row(Modifier.fillMaxWidth().background(c.header)) {
                    KickoffTab.entries.forEach { t ->
                        val sel = t == tab && query.isBlank()
                        Column(
                            Modifier.weight(1f).clickable { tab = t; query = "" }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(t.icon, t.label, tint = if (sel) c.accent else c.inactiveText, modifier = Modifier.size(22.dp))
                            Text(t.label, fontSize = 11.sp, color = if (sel) c.accent else c.inactiveText)
                        }
                    }
                }
            }
        }
    }
}

fun searchResults(state: KdxState, query: String): List<Launchable> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return state.launchables().mapNotNull { l ->
        val label = l.label.lowercase()
        val score = when {
            label == q -> 0
            label.startsWith(q) -> 1
            label.split(' ').any { it.startsWith(q) } -> 2
            label.contains(q) -> 3
            l is Launchable.Kdx && (l.app.generic.lowercase().contains(q) || l.app.keywords.contains(q)) -> 4
            l is Launchable.Droid && l.app.pkg.lowercase().contains(q) -> 5
            l is Launchable.Linux && (l.entry.comment.lowercase().contains(q) || (l.entry.flatpakId ?: "").lowercase().contains(q)) -> 5
            else -> null
        }
        score?.let { it to l }
    }.sortedWith(compareBy({ it.first }, { it.second.label.lowercase() })).map { it.second }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(state: KdxState, l: Launchable) {
    val c = LocalBreeze.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { state.launch(l) }, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaunchableIcon(l, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(l.subtitle, color = c.inactiveText, fontSize = 12.sp, maxLines = 1)
            }
            if (state.isFavorite(l.key)) Icon(Icons.Filled.Star, null, tint = c.accent, modifier = Modifier.size(16.dp))
        }
        LaunchableMenu(state, l, menu) { menu = false }
    }
}

@Composable
private fun ListAction(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    val c = LocalBreeze.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = c.text, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = c.inactiveText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlacesList(state: KdxState) {
    val root = android.os.Environment.getExternalStorageDirectory().path
    LazyColumn {
        item { ListAction(Icons.Filled.Home, "Home", state.homeDir().path) { state.open(KApp.DOLPHIN, state.homeDir().path) } }
        item { ListAction(Icons.Filled.Download, "Downloads") { state.open(KApp.DOLPHIN, "$root/Download") } }
        item { ListAction(Icons.Filled.Description, "Documents") { state.open(KApp.DOLPHIN, "$root/Documents") } }
        item { ListAction(Icons.Filled.Image, "Pictures") { state.open(KApp.DOLPHIN, "$root/Pictures") } }
        item { ListAction(Icons.Filled.PhotoCamera, "Camera") { state.open(KApp.DOLPHIN, "$root/DCIM") } }
        item { ListAction(Icons.Filled.MusicNote, "Music") { state.open(KApp.DOLPHIN, "$root/Music") } }
        item { ListAction(Icons.Filled.Movie, "Videos") { state.open(KApp.DOLPHIN, "$root/Movies") } }
        item { ListAction(Icons.Filled.Storage, "Root", "/") { state.open(KApp.DOLPHIN, "/") } }
    }
}

@Composable
private fun PowerList(state: KdxState) {
    LazyColumn {
        item { ListAction(Icons.Filled.DesktopWindows, "Show Desktop", "Minimize all windows") { state.closeMenus(); state.windowsOnDesktop().forEach { state.minimize(it) } } }
        item { ListAction(Icons.Filled.CloseFullscreen, "Close All Windows", "End this session's windows") { state.closeMenus(); state.windows.toList().forEach { state.close(it) } } }
        item { ListAction(Icons.Filled.Refresh, "Refresh Applications", "Rescan installed Android apps") { state.reloadApps() } }
        item { ListAction(Icons.Filled.Settings, "Android Settings") { state.openAndroidSettings() } }
        item { ListAction(Icons.Filled.Home, "Default Home App", "Choose whether KDX is your launcher") { state.openHomeSettings() } }
        item { ListAction(Icons.Filled.Info, "About KDX") { state.open(KApp.INFOCENTER) } }
    }
}
