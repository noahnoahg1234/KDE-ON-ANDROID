package org.kdx.shell.shell

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.kdx.shell.core.Accents
import org.kdx.shell.core.AndroidApp
import org.kdx.shell.core.BreezeTheme
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze

/**
 * KDX's taskbar floating over Android apps. KDX is a full-screen home screen: touching it makes Android bring it to
 * the front, over every floating app window – so with the taskbar inside KDX, opening a second app hid the first one.
 * This taskbar is a separate window above the apps ("Display over other apps"), so using it never covers them.
 */
object OverlayTaskbar {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun canShow(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    fun openPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Shows the floating taskbar while Android app windows are in front of KDX (and it's allowed), else removes it –
     * while KDX itself is in front, its own taskbar is visible.
     */
    fun sync(ctx: Context, state: KdxState, kdxInFront: Boolean) {
        val app = ctx.applicationContext
        val want = !kdxInFront && state.androidAppWindows && state.taskbarOverApps && canShow(app) &&
            org.kdx.shell.core.AndroidWindows.supported(app)
        if (want && view == null) show(app, state) else if (!want) hide(app)
    }

    private fun show(ctx: Context, state: KdxState) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val o = OverlayOwner()
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o); setViewTreeSavedStateRegistryOwner(o)
            setContent {
                BreezeTheme(state.themeMode, Accents[state.accentIndex.coerceIn(Accents.indices)].second) { OverlayBar(state) }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_FOCUSABLE alone puts the window above the on-screen keyboard (it covered the keyboard's bottom row);
            // with ALT_FOCUSABLE_IM it goes below the keyboard
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = if (state.panelPosition == org.kdx.shell.core.PanelPosition.TOP) Gravity.TOP else Gravity.BOTTOM }
        runCatching { wm.addView(v, lp); view = v; owner = o }
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(v) }
        owner?.destroy()
        view = null; owner = null
    }

    /** Lifecycle for Compose in a window that isn't an activity. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        init {
            saved.performAttach(); saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}

/** The floating taskbar: KDX (desktop, Kickoff), all apps, pinned apps, open Android app windows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OverlayBar(state: KdxState) {
    val c = LocalBreeze.current
    var apps by remember { mutableStateOf(false) }
    // collapsed: just a small tab in the corner (for apps that need the bottom of the screen)
    var collapsed by remember { mutableStateOf(false) }
    if (collapsed) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.padding(6.dp).size(36.dp).clip(RoundedCornerShape(18.dp)).background(c.accent.copy(alpha = 0.85f))
                    .clickable { collapsed = false }
                    .semantics { contentDescription = "Show taskbar" },
                contentAlignment = Alignment.Center,
            ) { Text("K", color = Color.White, fontSize = 16.sp) }
        }
        return
    }
    var menuFor by remember { mutableStateOf<AndroidApp?>(null) }
    Column(Modifier.fillMaxWidth()) {
        if (apps) {
            // all Android apps: opening one from here never covers the other windows
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).background(c.window.copy(alpha = 0.97f)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Apps", color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Close, "Close", tint = c.text, modifier = Modifier.clickable { apps = false }.padding(6.dp))
                }
                LazyVerticalGrid(GridCells.Adaptive(76.dp)) {
                    items(state.androidApps, key = { it.key }) { a ->
                        Column(
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable { apps = false; state.launch(a) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppIcon(a, 40)
                            Text(a.label, color = c.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        menuFor?.let { a ->
            Row(Modifier.fillMaxWidth().background(c.window).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, color = c.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp).weight(1f), maxLines = 1)
                TextButton({ menuFor = null; state.maximizeAndroidWindow(a) }) { Text("Maximize") }
                TextButton({ menuFor = null; state.restoreAndroidWindow(a) }) { Text("Restore") }
                TextButton({ menuFor = null; state.removeAndroidWindow(a) }) { Text("Remove") }
                Icon(Icons.Filled.Close, "Close menu", tint = c.text, modifier = Modifier.clickable { menuFor = null }.padding(6.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(c.panel).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // K: KDX's desktop and Kickoff
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(c.accent)
                    .clickable { state.kickoffOpen = true; state.bringKdxToFront() },
                contentAlignment = Alignment.Center,
            ) { Text("K", color = Color.White, fontSize = 18.sp) }
            Icon(Icons.Filled.Apps, "Apps", tint = c.panelText, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable { apps = !apps }.padding(8.dp))
            val pinned = state.pinned.mapNotNull { k -> state.androidApps.firstOrNull { it.key == k } }
            pinned.forEach { a -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable { state.launch(a) }, contentAlignment = Alignment.Center) { AppIcon(a, 28) } }
            Box(Modifier.width(1.dp).height(28.dp).background(c.border))
            LazyRow(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(state.androidWindows.toList(), key = { it.pkg }) { a ->
                    Column(
                        Modifier.height(44.dp).widthIn(min = 44.dp, max = 150.dp).clip(RoundedCornerShape(4.dp))
                            .combinedClickable(onClick = { state.focusAndroidWindow(a) }, onLongClick = { menuFor = a }),
                    ) {
                        Row(Modifier.weight(1f).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a, 24)
                            Spacer(Modifier.width(4.dp))
                            Text(a.label, color = c.panelText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 8.dp).background(c.accent.copy(alpha = 0.7f)))
                    }
                }
            }
            Text(formatTime(rememberNow(), state.use24h, false), color = c.panelText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
            Icon(Icons.Filled.KeyboardArrowDown, "Hide taskbar", tint = c.panelText,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).clickable { apps = false; collapsed = true }.padding(6.dp))
        }
    }
}

@Composable
private fun AppIcon(a: AndroidApp, size: Int) {
    a.icon?.let { Image(it, null, Modifier.size(size.dp)) } ?: Icon(Icons.Filled.Android, null, modifier = Modifier.size(size.dp))
}
