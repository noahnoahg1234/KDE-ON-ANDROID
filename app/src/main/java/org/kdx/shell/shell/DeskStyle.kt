package org.kdx.shell.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kdx.shell.apps.openFileExternally
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import java.io.File

private val DESK_PANEL = Color(0xFA1B1F26)
private val DESK_TEXT = Color(0xFFE8EAED)
private val DESK_DIM = Color(0xFF9AA0A8)

/** Desk style: a slim strip of round app icons (the pinned apps) on the desktop's left edge. */
@Composable
fun SideAppStrip(state: KdxState, modifier: Modifier = Modifier) {
    val apps = state.pinned.mapNotNull { state.resolve(it) }
    if (apps.isEmpty()) return
    Column(
        modifier.padding(start = 6.dp).clip(RoundedCornerShape(28.dp)).background(DESK_PANEL)
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        apps.forEach { l ->
            var menu by remember { mutableStateOf(false) }
            Box {
                RoundIcon(l, 44.dp, Modifier.semantics { contentDescription = l.label }, onClick = { state.launch(l) }, onLongClick = { menu = true })
                LaunchableMenu(state, l, menu) { menu = false }
            }
        }
    }
}

/** An app's icon in a round, softly coloured disc (Desk style). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoundIcon(l: Launchable, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color(0xFF2E343F))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) { LaunchableIcon(l, size * 0.62f) }
}

/**
 * Desk style's start menu: big round app icons in a grid (favourites first, then everything), a search line, and
 * Settings, Help and Power along the bottom.
 */
@Composable
fun DeskStartMenu(state: KdxState, compact: Boolean) {
    var query by remember { mutableStateOf("") }
    var help by remember { mutableStateOf(false) }
    var power by remember { mutableStateOf(false) }
    val all = remember(state.androidApps, state.linuxApps) { state.launchables().sortedBy { it.label.lowercase() } }
    val favs = state.favorites.mapNotNull { state.resolve(it) }
    val shown = if (query.isBlank()) favs + all.filter { a -> favs.none { it.key == a.key } }
        else all.filter { it.label.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true) }
    Box(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(8.dp, 8.dp, 8.dp, 56.dp),
        contentAlignment = if (state.panelOnTop) Alignment.TopStart else Alignment.BottomStart,
    ) {
        Column(
            Modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.width(560.dp)).fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(16.dp)).background(DESK_PANEL).clickable(enabled = false) {}.padding(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF2E343F)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = DESK_DIM, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", color = DESK_DIM, fontSize = 14.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = DESK_TEXT, fontSize = 14.sp),
                        cursorBrush = SolidColor(DESK_TEXT), modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.key }) { l ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            RoundIcon(l, 58.dp, onClick = { state.closeMenus(); state.launch(l) }, onLongClick = { menu = true })
                            Spacer(Modifier.height(4.dp))
                            Text(l.label, color = DESK_TEXT, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                        LaunchableMenu(state, l, menu) { menu = false }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                MenuAction(Icons.Filled.Settings, "Settings", Color(0xFF6C7A89)) { state.closeMenus(); state.open(KApp.SETTINGS) }
                MenuAction(Icons.Filled.HelpOutline, "Help", Color(0xFF3DAEE9)) { help = true }
                MenuAction(Icons.Filled.PowerSettingsNew, "Power", Color(0xFFE5484D)) { power = true }
            }
        }
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        title = { Text("KDX help") },
        text = {
            Text("• Tap an app to open it; long-press for favourites and the taskbar\n" +
                "• Windows: drag the title bar to move, double-tap it to maximize, the corner to resize\n" +
                "• Long-press the desktop to add widgets or change the wallpaper\n" +
                "• Android apps open in windows above the taskbar (System Settings → Desktop)\n" +
                "• Linux apps, KDE Plasma and WindSystem: System Settings → Linux System\n" +
                "• If something goes wrong: System Settings → Troubleshooting → Copy diagnostics")
        },
        confirmButton = { TextButton({ help = false }) { Text("OK") } },
    )
    if (power) AlertDialog(
        onDismissRequest = { power = false },
        title = { Text("Power") },
        text = {
            Column {
                PowerRow(Icons.Filled.Close, "Close all windows") { power = false; state.windows.toList().forEach { state.close(it) }; state.closeMenus() }
                PowerRow(Icons.Filled.Lock, "Lock screen") { power = false; state.closeMenus(); state.lockScreen() }
                PowerRow(Icons.Filled.PowerSettingsNew, "Phone's power menu (restart, power off)") { power = false; state.closeMenus(); state.powerMenu() }
            }
        },
        confirmButton = { TextButton({ power = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(label, color = DESK_TEXT, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PowerRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalBreeze.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
        Text(label, color = c.text, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The files and folders in the phone's Desktop folder (shown as big icons on the Desk-style desktop). */
fun desktopFolderEntries(): List<File> {
    val dir = File(android.os.Environment.getExternalStorageDirectory(), "Desktop")
    return (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(60)
}

/** A big folder or file icon on the desktop: folders open in Dolphin, files in the app Android picks. */
@Composable
fun DeskFileTile(state: KdxState, f: File) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable { if (f.isDirectory) state.open(KApp.DOLPHIN, f.path) else openFileExternally(state, ctx, f) }
            .padding(6.dp).width(84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(if (f.isDirectory) Icons.Filled.Folder else fileIcon(f), f.name,
            tint = if (f.isDirectory) Color(0xFFF4C04E) else Color(0xFFDDE3EA), modifier = Modifier.size(56.dp))
        Text(f.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)))
    }
}

private fun fileIcon(f: File): ImageVector = when (f.extension.lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp" -> Icons.Filled.Image
    "mp4", "mkv", "webm", "mov" -> Icons.Filled.Movie
    "mp3", "ogg", "flac", "wav", "m4a" -> Icons.Filled.MusicNote
    "pdf" -> Icons.Filled.PictureAsPdf
    "apk" -> Icons.Filled.Android
    "zip", "tar", "gz", "xz", "7z", "rar" -> Icons.Filled.FolderZip
    else -> Icons.Filled.Description
}
