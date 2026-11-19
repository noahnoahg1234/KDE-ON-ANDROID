package org.kdx.shell.apps

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DolphinSession(start: File) {
    var cwd by mutableStateOf(start)
    val back = mutableStateListOf<File>()
    val forward = mutableStateListOf<File>()
    var showHidden by mutableStateOf(false)
    var grid by mutableStateOf(false)
    var filter by mutableStateOf("")
    var refresh by mutableIntStateOf(0)
    val selected = mutableStateListOf<File>()

    fun go(f: File) {
        if (f == cwd) return
        back.add(cwd); forward.clear(); selected.clear(); cwd = f
    }
}

private val textExt = setOf(
    "txt", "md", "log", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "kts", "java", "c", "h", "cpp", "hpp",
    "py", "sh", "rs", "go", "yml", "yaml", "toml", "ini", "conf", "cfg", "csv", "properties", "gradle", "sql", "rc", "desktop",
)
private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

fun File.isTextFile() = extension.lowercase() in textExt || (extension.isEmpty() && length() < 512 * 1024)
fun File.isImageFile() = extension.lowercase() in imageExt

fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

private fun iconFor(f: File): ImageVector = when {
    f.isDirectory -> Icons.Filled.Folder
    f.isImageFile() -> Icons.Filled.Image
    f.extension.lowercase() in setOf("mp3", "ogg", "flac", "wav", "m4a", "opus") -> Icons.Filled.MusicNote
    f.extension.lowercase() in setOf("mp4", "mkv", "webm", "avi", "mov") -> Icons.Filled.Movie
    f.extension.lowercase() == "pdf" -> Icons.Filled.PictureAsPdf
    f.extension.lowercase() in setOf("zip", "tar", "gz", "xz", "7z", "rar") -> Icons.Filled.FolderZip
    f.extension.lowercase() == "apk" -> Icons.Filled.Android
    f.isTextFile() -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

fun openFileExternally(state: KdxState, ctx: android.content.Context, f: File) {
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Open ${f.name} with",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { state.toast("No app can open ${f.name}") }
}

fun shareFile(state: KdxState, ctx: android.content.Context, f: File) {
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share ${f.name}",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { state.toast("Sharing failed") }
}

/** Dolphin – the KDE file manager. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dolphin(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember(w) { w.retain("dolphin") { DolphinSession(w.arg?.let { File(it) } ?: state.homeDir()) } }
    var hasAccess by remember { mutableStateOf(state.hasStorageAccess()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = state.hasStorageAccess(); s.refresh++
    }
    var dialog by remember { mutableStateOf<String?>(null) } // "newFolder", "newFile", "rename"
    var props by remember { mutableStateOf<File?>(null) }
    var confirmDelete by remember { mutableStateOf<List<File>?>(null) }
    var filterOpen by remember { mutableStateOf(false) }

    LaunchedEffect(s.cwd) { w.title = "${s.cwd.name.ifEmpty { "/" }} — Dolphin" }
    // Re-check permission when returning from the Settings screen.
    LaunchedEffect(Unit) {
        while (!hasAccess) {
            kotlinx.coroutines.delay(1500)
            if (state.hasStorageAccess()) { hasAccess = true; if (s.cwd == ctx.filesDir) s.go(state.homeDir()); s.refresh++ }
        }
    }

    val entries by produceState(emptyList<File>(), s.cwd, s.showHidden, s.refresh) {
        value = withContext(Dispatchers.IO) {
            (s.cwd.listFiles()?.toList() ?: emptyList())
                .filter { s.showHidden || !it.name.startsWith(".") }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }
    val shown = if (s.filter.isBlank()) entries else entries.filter { it.name.contains(s.filter, true) }

    fun open(f: File) {
        when {
            f.isDirectory -> s.go(f)
            f.isImageFile() -> state.open(KApp.GWENVIEW, f.path)
            f.isTextFile() -> state.open(KApp.KATE, f.path)
            else -> openFileExternally(state, ctx, f)
        }
    }

    fun paste() {
        val items = state.fileClipboard
        val cut = state.fileClipboardCut
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    items.forEach { src ->
                        var dst = File(s.cwd, src.name)
                        var n = 1
                        while (dst.exists()) dst = File(s.cwd, "${src.nameWithoutExtension} (${n++})${if (src.extension.isNotEmpty()) "." + src.extension else ""}")
                        if (cut && src.renameTo(dst)) return@forEach
                        src.copyRecursively(dst)
                        if (cut) src.deleteRecursively()
                    }
                }.exceptionOrNull()
            }
            if (err != null) state.toast("Paste failed: ${err.message}")
            if (cut) state.fileClipboard = emptyList()
            s.refresh++
        }
    }

    Column(Modifier.fillMaxSize().background(c.view)) {
        ToolBar {
            ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", s.back.isNotEmpty()) {
                s.forward.add(s.cwd); s.cwd = s.back.removeAt(s.back.lastIndex); s.selected.clear()
            }
            ToolButton(Icons.AutoMirrored.Filled.ArrowForward, "Forward", s.forward.isNotEmpty()) {
                s.back.add(s.cwd); s.cwd = s.forward.removeAt(s.forward.lastIndex); s.selected.clear()
            }
            ToolButton(Icons.Filled.ArrowUpward, "Up", s.cwd.parentFile != null) { s.cwd.parentFile?.let { s.go(it) } }
            ToolButton(Icons.Filled.Home, "Home") { s.go(state.homeDir()) }
            Spacer(Modifier.weight(1f))
            if (s.selected.isNotEmpty()) {
                Text("${s.selected.size}", color = c.accent, fontSize = 13.sp)
                ToolButton(Icons.Filled.ContentCopy, "Copy") { state.fileClipboard = s.selected.toList(); state.fileClipboardCut = false; s.selected.clear(); state.toast("Copied") }
                ToolButton(Icons.Filled.ContentCut, "Cut") { state.fileClipboard = s.selected.toList(); state.fileClipboardCut = true; s.selected.clear(); state.toast("Cut") }
                ToolButton(Icons.Filled.Delete, "Delete") { confirmDelete = s.selected.toList() }
                ToolButton(Icons.Filled.Deselect, "Clear selection") { s.selected.clear() }
            } else {
                if (state.fileClipboard.isNotEmpty()) ToolButton(Icons.Filled.ContentPaste, "Paste") { paste() }
                ToolButton(Icons.Filled.CreateNewFolder, "New Folder") { dialog = "newFolder" }
                ToolButton(Icons.Filled.NoteAdd, "New File") { dialog = "newFile" }
                ToolButton(Icons.Filled.FilterList, "Filter") { filterOpen = !filterOpen; if (!filterOpen) s.filter = "" }
                ToolButton(if (s.grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, "View mode") { s.grid = !s.grid }
                ToolButton(if (s.showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Hidden files") { s.showHidden = !s.showHidden }
                ToolButton(Icons.Filled.Terminal, "Open Terminal here") { state.open(KApp.KONSOLE, "cd \"${s.cwd.path}\"") }
            }
        }
        // Places panel (horizontal on phones)
        val root = Environment.getExternalStorageDirectory()
        val places = listOf(
            Triple("Home", Icons.Filled.Home, state.homeDir()),
            Triple("Downloads", Icons.Filled.Download, File(root, "Download")),
            Triple("Documents", Icons.Filled.Description, File(root, "Documents")),
            Triple("Pictures", Icons.Filled.Image, File(root, "Pictures")),
            Triple("Camera", Icons.Filled.PhotoCamera, File(root, "DCIM")),
            Triple("Music", Icons.Filled.MusicNote, File(root, "Music")),
            Triple("KDX data", Icons.Filled.Folder, ctx.filesDir),
            Triple("Root", Icons.Filled.Storage, File("/")),
        )
        LazyRow(
            Modifier.fillMaxWidth().background(c.window).padding(vertical = 4.dp),
            contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(places) { (name, icon, f) ->
                val sel = s.cwd == f
                AssistChip(
                    onClick = { s.go(f) }, label = { Text(name, fontSize = 12.sp) },
                    leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
                    colors = if (sel) AssistChipDefaults.assistChipColors(containerColor = c.accent.copy(alpha = 0.25f)) else AssistChipDefaults.assistChipColors(),
                )
            }
        }
        // Breadcrumb (Dolphin's location bar)
        Row(Modifier.fillMaxWidth().background(c.window).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val parts = generateSequence(s.cwd) { it.parentFile }.toList().reversed()
            parts.forEachIndexed { i, f ->
                Text(
                    if (i == 0) "/" else f.name, color = if (i == parts.lastIndex) c.text else c.accent, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable { s.go(f) }.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                if (i in 1 until parts.lastIndex) Text("›", color = c.inactiveText)
            }
        }
        HorizontalDivider(color = c.border)
        if (filterOpen) OutlinedTextField(
            s.filter, { s.filter = it }, Modifier.fillMaxWidth().padding(6.dp),
            placeholder = { Text("Filter…") }, singleLine = true, leadingIcon = { Icon(Icons.Filled.FilterList, null) },
        )
        if (!hasAccess) {
            Row(Modifier.fillMaxWidth().background(c.neutral.copy(alpha = 0.15f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = c.neutral)
                Text("KDX can only see its own folder. Grant file access to browse your storage.",
                    Modifier.weight(1f).padding(horizontal = 8.dp), color = c.text, fontSize = 12.sp)
                TextButton({
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    } else permLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }) { Text("Grant") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) Text(
                if (s.cwd.canRead()) "Folder is empty" else "Cannot read this folder",
                Modifier.align(Alignment.Center), color = c.inactiveText,
            )
            val onTap: (File) -> Unit = { f ->
                if (s.selected.isNotEmpty()) { if (!s.selected.remove(f)) s.selected.add(f) } else open(f)
            }
            val onLong: (File) -> Unit = { f -> if (f !in s.selected) s.selected.add(f) }
            if (s.grid) {
                LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { f ->
                        Column(
                            Modifier.padding(4.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (f in s.selected) c.accent.copy(alpha = 0.25f) else Color.Transparent)
                                .combinedClickable(onClick = { onTap(f) }, onLongClick = { onLong(f) }).padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            FileThumb(f, 48)
                            Text(f.name, color = c.text, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { f ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (f in s.selected) c.accent.copy(alpha = 0.25f) else Color.Transparent)
                                .combinedClickable(onClick = { onTap(f) }, onLongClick = { menu = true })
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileThumb(f, 32)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text(
                                    (if (f.isDirectory) "${f.list()?.size ?: 0} items" else humanSize(f.length())) +
                                        " · " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified())),
                                    color = c.inactiveText, fontSize = 11.sp,
                                )
                            }
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; open(f) })
                            if (f.isFile) {
                                DropdownMenuItem(text = { Text("Open with Kate") }, onClick = { menu = false; state.open(KApp.KATE, f.path) })
                                DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; openFileExternally(state, ctx, f) })
                                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; shareFile(state, ctx, f) })
                            } else {
                                DropdownMenuItem(text = { Text("Open in New Window") }, onClick = { menu = false; state.open(KApp.DOLPHIN, f.path) })
                                DropdownMenuItem(text = { Text("Open Terminal Here") }, onClick = { menu = false; state.open(KApp.KONSOLE, "cd \"${f.path}\"") })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onLong(f) })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; state.fileClipboard = listOf(f); state.fileClipboardCut = false })
                            DropdownMenuItem(text = { Text("Cut") }, onClick = { menu = false; state.fileClipboard = listOf(f); state.fileClipboardCut = true })
                            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; s.selected.clear(); s.selected.add(f); dialog = "rename" })
                            DropdownMenuItem(text = { Text("Copy Location") }, onClick = { menu = false; state.copyText(f.path) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = listOf(f) })
                            DropdownMenuItem(text = { Text("Properties") }, onClick = { menu = false; props = f })
                        }
                    }
                }
            }
        }
        // Status bar
        HorizontalDivider(color = c.border)
        val folders = entries.count { it.isDirectory }
        Text(
            "$folders folders, ${entries.size - folders} files" +
                (if (state.fileClipboard.isNotEmpty()) " · ${state.fileClipboard.size} in clipboard" else "") +
                " · ${humanSize(s.cwd.usableSpace)} free",
            Modifier.fillMaxWidth().background(c.window).padding(horizontal = 10.dp, vertical = 4.dp),
            color = c.inactiveText, fontSize = 11.sp,
        )
    }

    when (dialog) {
        "newFolder" -> TextInputDialog("New Folder", "New Folder", "Create", { dialog = null }) { name ->
            dialog = null
            if (!File(s.cwd, name).mkdirs()) state.toast("Could not create folder")
            s.refresh++
        }
        "newFile" -> TextInputDialog("New Text File", "Text File.txt", "Create", { dialog = null }) { name ->
            dialog = null
            runCatching { File(s.cwd, name).createNewFile() }.onFailure { state.toast("Could not create file") }
            s.refresh++
        }
        "rename" -> {
            val f = s.selected.firstOrNull()
            if (f == null) dialog = null else TextInputDialog("Rename", f.name, "Rename", { dialog = null }) { name ->
                dialog = null
                if (!f.renameTo(File(f.parentFile, name))) state.toast("Rename failed")
                s.selected.clear(); s.refresh++
            }
        }
    }
    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete permanently?") },
            text = { Text(if (list.size == 1) "\"${list[0].name}\" will be deleted. This cannot be undone." else "${list.size} items will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton({
                    confirmDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { list.all { it.deleteRecursively() } }
                        if (!ok) state.toast("Some items could not be deleted")
                        s.selected.clear(); s.refresh++
                    }
                }) { Text("Delete", color = c.negative) }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
    props?.let { f ->
        val size by produceState<Long?>(null, f) { value = withContext(Dispatchers.IO) { f.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
        AlertDialog(
            onDismissRequest = { props = null },
            title = { Text(f.name) },
            text = {
                Column {
                    Text("Type: ${if (f.isDirectory) "Folder" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "File"}")
                    Text("Location: ${f.parent}")
                    Text("Size: ${size?.let { humanSize(it) } ?: "calculating…"}")
                    Text("Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))}")
                    Text("Permissions: ${if (f.canRead()) "r" else "-"}${if (f.canWrite()) "w" else "-"}${if (f.canExecute()) "x" else "-"}")
                }
            },
            confirmButton = { TextButton({ props = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun FileThumb(f: File, sizeDp: Int) {
    val c = LocalBreeze.current
    if (f.isImageFile()) {
        val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f) { value = loadThumb(f, sizeDp * 3) }
        bmp?.let {
            androidx.compose.foundation.Image(it, null, Modifier.size(sizeDp.dp).clip(RoundedCornerShape(3.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            return
        }
    }
    Icon(iconFor(f), null, tint = if (f.isDirectory) c.accent else c.inactiveText, modifier = Modifier.size(sizeDp.dp))
}
