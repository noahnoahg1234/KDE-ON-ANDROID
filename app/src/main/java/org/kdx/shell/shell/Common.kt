package org.kdx.shell.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.kdx.shell.core.AndroidApp
import org.kdx.shell.core.KApp
import org.kdx.shell.core.KdxState
import org.kdx.shell.core.LocalBreeze
import org.kdx.shell.droid.DroidSystem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Terminal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Something Kickoff/KRunner/the desktop can launch: a KDX app or an installed Android app. */
sealed interface Launchable {
    val key: String
    val label: String
    val subtitle: String

    data class Kdx(val app: KApp) : Launchable {
        override val key get() = app.key
        override val label get() = app.title
        override val subtitle get() = app.generic
    }

    data class Droid(val app: AndroidApp) : Launchable {
        override val key get() = app.key
        override val label get() = app.label
        override val subtitle get() = "Android app"
    }

    /** A .desktop entry from DroidSystem (dspkg package or Flatpak). */
    data class Linux(val entry: DroidSystem.DesktopEntry) : Launchable {
        override val key get() = entry.key
        override val label get() = entry.name
        override val subtitle get() = when (entry.source) {
            org.kdx.shell.droid.DroidSystem.EntrySource.FLATHUB -> "Flatpak (Flathub) · "
            org.kdx.shell.droid.DroidSystem.EntrySource.DEBIAN -> "Debian · "
            else -> if (entry.flatpakId != null) "Flatpak · " else "DroidSystem · "
        } + entry.comment.ifEmpty { "Linux app" }
    }
}

fun KdxState.launchables(): List<Launchable> =
    KApp.entries.filterNot { it.droidOnly }.map { Launchable.Kdx(it) } + linuxApps.map { Launchable.Linux(it) } + androidApps.map { Launchable.Droid(it) }

fun KdxState.resolve(key: String): Launchable? =
    if (key.startsWith("kdx:")) runCatching { Launchable.Kdx(KApp.valueOf(key.removePrefix("kdx:"))) }.getOrNull()
    else if (key.startsWith("linux:")) linuxApps.firstOrNull { it.key == key }?.let { Launchable.Linux(it) }
    else androidApps.firstOrNull { it.key == key }?.let { Launchable.Droid(it) }

fun KdxState.launch(l: Launchable) = when (l) {
    is Launchable.Kdx -> open(l.app)
    is Launchable.Droid -> launch(l.app)
    is Launchable.Linux -> when {
        l.entry.kdxApp != null -> open(l.entry.kdxApp!!)
        l.entry.source == org.kdx.shell.droid.DroidSystem.EntrySource.FLATHUB && l.entry.flatpakId != null -> launchFlatpak(l.entry.flatpakId)
        l.entry.source == org.kdx.shell.droid.DroidSystem.EntrySource.DEBIAN && !l.entry.terminal -> launchLinuxGui(l.entry.exec)
        else -> open(KApp.KONSOLE, l.entry.exec)
    }
}

@Composable
fun LaunchableIcon(l: Launchable, size: Dp) {
    when (l) {
        is Launchable.Kdx -> KAppIcon(l.app, size)
        is Launchable.Linux -> l.entry.kdxApp?.let { KAppIcon(it, size) } ?: l.entry.icon?.let { f ->
            val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
                }
            }
            bmp?.let { Image(it, l.label, Modifier.size(size)) } ?: Box(Modifier.size(size))
        } ?: Box(
            Modifier.size(size).clip(RoundedCornerShape(size / 4))
                .background(if (l.entry.flatpakId != null) Color(0xFF4A86CF) else Color(0xFFE95420)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (l.entry.flatpakId != null) Icons.Filled.Inventory2 else Icons.Filled.Terminal, l.label,
                tint = Color.White, modifier = Modifier.size(size * 0.6f),
            )
        }
        is Launchable.Droid -> {
            val bmp = l.app.icon
            if (bmp != null) Image(bmp, l.label, Modifier.size(size))
            else Box(
                Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Color.Gray),
                contentAlignment = Alignment.Center,
            ) { Text(l.label.take(1), color = Color.White) }
        }
    }
}

/** Breeze-style app icon: a rounded gradient tile with a glyph. */
@Composable
fun KAppIcon(app: KApp, size: Dp) {
    val tint = when (app) {
        KApp.DOLPHIN -> Color(0xFF1D99F3)
        KApp.KONSOLE -> Color(0xFF31363B)
        KApp.KATE -> Color(0xFF8E44AD)
        KApp.KCALC -> Color(0xFF16A085)
        KApp.GWENVIEW -> Color(0xFF27AE60)
        KApp.KLIPPER -> Color(0xFFF67400)
        KApp.KNOTES -> Color(0xFFE6B800)
        KApp.SYSMON -> Color(0xFFDA4453)
        KApp.INFOCENTER -> Color(0xFF2980B9)
        KApp.SETTINGS -> Color(0xFF6C7A89)
        KApp.BAZAAR -> Color(0xFF8E44AD)
        KApp.LINUXDISPLAY -> Color(0xFFE95420)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(app.icon, app.title, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    state: KdxState,
    l: Launchable,
    modifier: Modifier = Modifier,
    iconSize: Dp = 44.dp,
    textColor: Color = LocalBreeze.current.text,
    shadow: Boolean = false,
    onLaunched: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(onClick = { state.launch(l); onLaunched() }, onLongClick = { menu = true })
                .padding(6.dp)
                .width(76.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LaunchableIcon(l, iconSize)
            Spacer(Modifier.height(4.dp))
            Text(
                l.label, color = textColor, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
                style = if (shadow) MaterialTheme.typography.bodySmall.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)
                ) else MaterialTheme.typography.bodySmall,
            )
        }
        LaunchableMenu(state, l, menu) { menu = false }
    }
}

@Composable
fun LaunchableMenu(state: KdxState, l: Launchable, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(
            text = { Text(if (state.isFavorite(l.key)) "Remove from Favorites" else "Add to Favorites") },
            onClick = { state.toggleFavorite(l.key); onDismiss() },
        )
        if (l is Launchable.Droid) {
            DropdownMenuItem(text = { Text("App info") }, onClick = { state.appInfo(l.app.pkg); onDismiss() })
            DropdownMenuItem(text = { Text("Uninstall") }, onClick = { state.uninstall(l.app.pkg); onDismiss() })
        }
    }
}

@Composable
fun rememberNow(periodMs: Long = 1000): Date {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = Date()
            delay(periodMs - System.currentTimeMillis() % periodMs)
        }
    }
    return now
}

fun formatTime(d: Date, h24: Boolean, seconds: Boolean): String {
    val p = buildString {
        append(if (h24) "HH:mm" else "h:mm")
        if (seconds) append(":ss")
        if (!h24) append(" a")
    }
    return SimpleDateFormat(p, Locale.getDefault()).format(d)
}

data class Battery(val level: Int, val charging: Boolean, val tempC: Float, val voltage: Int, val health: Int)

@Composable
fun rememberBattery(): Battery {
    val ctx = LocalContext.current
    var b by remember { mutableStateOf(Battery(-1, false, 0f, 0, 0)) }
    DisposableEffect(Unit) {
        fun parse(i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            b = Battery(
                level * 100 / scale,
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
                i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
            )
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = parse(i)
        }
        parse(ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        onDispose { ctx.unregisterReceiver(r) }
    }
    return b
}

/** A Breeze-looking toolbar button used inside KDX apps. */
@Composable
fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, label, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ToolBar(content: @Composable RowScope.() -> Unit) {
    val c = LocalBreeze.current
    Row(
        Modifier.fillMaxWidth().background(c.window).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    HorizontalDivider(color = c.border)
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirm: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
