package org.kdx.shell.apps

import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KWindow
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.shell.ToolBar
import org.kdx.shell.shell.ToolButton
import java.io.File

suspend fun loadThumb(f: File, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxPx && o.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

class GwenSession(var folder: File, initial: File?) {
    var current by mutableStateOf(initial)
}

/** Gwenview – image viewer with a browse grid and zoomable view mode. */
@Composable
fun Gwenview(state: KdxState, w: KWindow) {
    val c = LocalBreeze.current
    val ctx = LocalContext.current
    val s = remember(w) {
        w.retain("gwen") {
            val f = w.arg?.let { File(it) }
            when {
                f == null -> GwenSession(
                    listOf("DCIM/Camera", "Pictures", "DCIM").map { File(Environment.getExternalStorageDirectory(), it) }
                        .firstOrNull { it.isDirectory && state.hasStorageAccess() } ?: state.homeDir(), null,
                )
                f.isDirectory -> GwenSession(f, null)
                else -> GwenSession(f.parentFile ?: f, f)
            }
        }
    }
    val images by produceState(emptyList<File>(), s.folder) {
        value = withContext(Dispatchers.IO) {
            s.folder.listFiles()?.filter { it.isFile && it.isImageFile() }?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }
    LaunchedEffect(s.current, s.folder) { w.title = "${s.current?.name ?: s.folder.name} — Gwenview" }

    Column(Modifier.fillMaxSize().background(if (s.current != null) Color(0xFF101214) else c.view)) {
        val cur = s.current
        if (cur == null) {
            ToolBar {
                ToolButton(Icons.Filled.ArrowUpward, "Parent folder", s.folder.parentFile != null) { s.folder.parentFile?.let { s.folder = it; s.current = null } }
                Text(s.folder.path, Modifier.weight(1f).padding(horizontal = 6.dp), color = c.text, fontSize = 13.sp, maxLines = 1)
                ToolButton(Icons.Filled.Folder, "Browse in Dolphin") { state.open(KApp.DOLPHIN, s.folder.path) }
            }
            if (images.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No images in this folder.\nOpen an image from Dolphin to view it here.", color = c.inactiveText)
            }
            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
                items(images, key = { it.path }) { f ->
                    val thumb by produceState<ImageBitmap?>(null, f) { value = loadThumb(f, 256) }
                    Box(Modifier.padding(2.dp).aspectRatio(1f).background(c.header).clickable { s.current = f }) {
                        thumb?.let { Image(it, f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
        } else {
            val idx = images.indexOf(cur)
            ToolBar {
                ToolButton(Icons.Filled.GridView, "Browse") { s.current = null }
                ToolButton(Icons.AutoMirrored.Filled.NavigateBefore, "Previous", idx > 0) { s.current = images[idx - 1] }
                ToolButton(Icons.AutoMirrored.Filled.NavigateNext, "Next", idx in 0 until images.lastIndex) { s.current = images[idx + 1] }
                Text(if (idx >= 0) "${idx + 1} / ${images.size}" else "", Modifier.weight(1f), color = c.inactiveText, fontSize = 12.sp)
                ToolButton(Icons.Filled.Share, "Share") { shareFile(state, ctx, cur) }
                ToolButton(Icons.Filled.Wallpaper, "Set as Wallpaper") {
                    runCatching { cur.copyTo(state.wallpaperFile, overwrite = true) }
                        .onSuccess { state.customWallpaper = true; state.wallpaperVersion++; state.save(); state.toast("Wallpaper set") }
                }
                ToolButton(Icons.AutoMirrored.Filled.OpenInNew, "Open With") { openFileExternally(state, ctx, cur) }
            }
            key(cur.path) {
                val full by produceState<ImageBitmap?>(null, cur) { value = loadThumb(cur, 2048) }
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                val ts = rememberTransformableState { z, pan, _ ->
                    scale = (scale * z).coerceIn(1f, 8f)
                    offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                }
                Box(
                    Modifier.fillMaxSize().transformable(ts)
                        .pointerInput(cur) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f }) },
                    contentAlignment = Alignment.Center,
                ) {
                    val img = full
                    if (img == null) CircularProgressIndicator()
                    else Image(
                        img, cur.name,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                        },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}
