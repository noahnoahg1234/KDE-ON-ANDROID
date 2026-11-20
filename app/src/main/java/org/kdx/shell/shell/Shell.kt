package org.kdx.shell.shell

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.Wallpapers
import org.kdx.shell.core.brush
import kotlin.math.abs

/** The whole Plasma-like workspace: wallpaper, desktop, windows, panel and popups. */
@Composable
fun Shell(state: KdxState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        WallpaperLayer(state)
        Column(Modifier.fillMaxSize().imePadding()) {
            if (state.panelOnTop) Panel(state, compact, Modifier.statusBarsPadding())
            Box(
                Modifier.weight(1f).fillMaxWidth().then(
                    if (state.panelOnTop) Modifier.navigationBarsPadding() else Modifier.statusBarsPadding()
                )
            ) {
                DesktopLayer(state, compact)
                WindowLayer(state)
            }
            if (!state.panelOnTop) Panel(state, compact, Modifier.navigationBarsPadding())
        }

        // Popups ----------------------------------------------------------------
        val anyPopup = state.kickoffOpen || state.calendarOpen
        if (anyPopup) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { state.closeMenus() }
            )
        }
        AnimatedVisibility(state.kickoffOpen, enter = fadeIn(), exit = fadeOut()) {
            Kickoff(state, compact)
        }
        if (state.calendarOpen) CalendarPopup(state)
        AnimatedVisibility(state.krunnerOpen, enter = fadeIn(), exit = fadeOut()) {
            KRunner(state)
        }
        AnimatedVisibility(state.overviewOpen, enter = fadeIn(), exit = fadeOut()) {
            Overview(state)
        }
        FullscreenLayer(state)
    }
}

/**
 * A full-screen window (the real KDE Plasma desktop) covers everything, KDX's panel included. A small handle in the
 * corner goes back to KDX (the session keeps running; its button in KDX's task manager brings it back).
 */
@Composable
private fun FullscreenLayer(state: KdxState) {
    val w = state.windows.lastOrNull { it.fullscreen && !it.minimized && it.desktop == state.currentDesktop } ?: return
    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()) {
        org.kdx.shell.apps.AppContent(state, w)
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Box(
                Modifier.size(30.dp).background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape)
                    .clickable { menu = true },
                contentAlignment = Alignment.Center,
            ) { Text("K", color = Color.White.copy(alpha = 0.85f)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Back to KDX") }, onClick = { menu = false; w.minimized = true })
                DropdownMenuItem(text = { Text("Show in a window") }, onClick = { menu = false; w.fullscreen = false; w.maximized = true })
                DropdownMenuItem(text = { Text("End ${w.title}") }, onClick = { menu = false; state.close(w) })
            }
        }
    }
}

@Composable
private fun WallpaperLayer(state: KdxState) {
    val wp = Wallpapers[state.wallpaperIndex.coerceIn(Wallpapers.indices)]
    val custom by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, state.customWallpaper, state.wallpaperVersion) {
        value = if (state.customWallpaper) withContext(Dispatchers.IO) {
            runCatching {
                val f = state.wallpaperFile
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var sample = 1
                while (o.outWidth / sample > 2560 || o.outHeight / sample > 2560) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        } else null
    }
    val img = custom
    if (img != null) {
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(wp.brush())
        // Plasma-ish faceted shards
        val w = size.width
        val h = size.height
        fun shard(pts: List<Offset>, alpha: Float) {
            val p = Path().apply {
                moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
            }
            drawPath(p, wp.shards.copy(alpha = alpha))
        }
        shard(listOf(Offset(0f, h * 0.55f), Offset(w * 0.55f, h), Offset(0f, h)), alpha = 0.35f)
        shard(listOf(Offset(w * 0.25f, h), Offset(w, h * 0.35f), Offset(w, h)), alpha = 0.25f)
        shard(listOf(Offset(w * 0.45f, 0f), Offset(w, 0f), Offset(w, h * 0.45f)), alpha = 0.18f)
        shard(listOf(Offset(0f, 0f), Offset(w * 0.35f, 0f), Offset(0f, h * 0.3f)), alpha = 0.12f)
        drawRect(Color.Black.copy(alpha = 0.08f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopLayer(state: KdxState, compact: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(state.desktopCount) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (abs(total) > size.width / 4f) state.switchDesktop(state.currentDesktop + if (total < 0) 1 else -1)
                    },
                ) { _, d -> total += d }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { state.closeMenus() },
                onLongClick = { menu = true },
            )
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Widgets(state, compact)
            if (state.showDesktopIcons) {
                val favs = state.favorites.mapNotNull { state.resolve(it) }
                LazyVerticalGrid(
                    GridCells.Adaptive(88.dp),
                    Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(favs, key = { it.key }) {
                        AppTile(state, it, textColor = Color.White, shadow = true)
                    }
                }
            }
        }
        DesktopIndicator(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        DropdownMenu(menu, { menu = false }, offset = androidx.compose.ui.unit.DpOffset(16.dp, 0.dp)) {
            DropdownMenuItem(text = { Text("Configure Desktop and Wallpaper…") }, onClick = { menu = false; state.open(KApp.SETTINGS, "wallpaper") })
            DropdownMenuItem(text = { Text("Open Terminal") }, onClick = { menu = false; state.open(KApp.KONSOLE) })
            DropdownMenuItem(text = { Text("Open Home Folder") }, onClick = { menu = false; state.open(KApp.DOLPHIN) })
            DropdownMenuItem(text = { Text("Show Overview") }, onClick = { menu = false; state.overviewOpen = true })
            DropdownMenuItem(text = { Text("Refresh Applications") }, onClick = { menu = false; state.reloadApps() })
        }
    }
}

@Composable
private fun DesktopIndicator(state: KdxState, modifier: Modifier) {
    if (state.desktopCount < 2) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(state.desktopCount) { i ->
            Box(
                Modifier.size(if (i == state.currentDesktop) 10.dp else 7.dp)
                    .background(Color.White.copy(alpha = if (i == state.currentDesktop) 0.95f else 0.45f), androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}
