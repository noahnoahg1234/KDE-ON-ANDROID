package org.kdx.shell.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Colours modelled on KDE's Breeze colour schemes. */
@Immutable
data class BreezeColors(
    val dark: Boolean,
    val accent: Color,
    val window: Color,
    val view: Color,
    val header: Color,
    val text: Color,
    val inactiveText: Color,
    val border: Color,
    val panel: Color,
    val panelText: Color,
    val negative: Color = Color(0xFFDA4453),
    val positive: Color = Color(0xFF27AE60),
    val neutral: Color = Color(0xFFF67400),
)

val Accents = listOf(
    "Breeze Blue" to Color(0xFF3DAEE9),
    "Plasma Blue" to Color(0xFF1D99F3),
    "Purple" to Color(0xFF9B59B6),
    "Pink" to Color(0xFFE93A9A),
    "Red" to Color(0xFFE93D58),
    "Orange" to Color(0xFFF67400),
    "Yellow" to Color(0xFFFDBC4B),
    "Green" to Color(0xFF27AE60),
    "Teal" to Color(0xFF1ABC9C),
    "Graphite" to Color(0xFF7F8C8D),
)

fun breezeColors(dark: Boolean, accent: Color) = if (dark) BreezeColors(
    dark = true,
    accent = accent,
    window = Color(0xFF202326),
    view = Color(0xFF141618),
    header = Color(0xFF272B2F),
    text = Color(0xFFFCFCFC),
    inactiveText = Color(0xFFA1A9B1),
    border = Color(0xFF3E4348),
    panel = Color(0xE6202326),
    panelText = Color(0xFFFCFCFC),
) else BreezeColors(
    dark = false,
    accent = accent,
    window = Color(0xFFEFF0F1),
    view = Color(0xFFFFFFFF),
    header = Color(0xFFDEE0E2),
    text = Color(0xFF232629),
    inactiveText = Color(0xFF707D8A),
    border = Color(0xFFBCC0C4),
    panel = Color(0xE6EFF0F1),
    panelText = Color(0xFF232629),
)

val LocalBreeze = staticCompositionLocalOf { breezeColors(false, Accents[0].second) }

enum class ThemeMode(val label: String) { SYSTEM("Follow Android"), LIGHT("Breeze Light"), DARK("Breeze Dark") }

@Composable
fun BreezeTheme(mode: ThemeMode, accent: Color, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = breezeColors(dark, accent)
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent,
        background = c.window, onBackground = c.text, surface = c.window, onSurface = c.text,
        surfaceVariant = c.header, onSurfaceVariant = c.inactiveText, outline = c.border,
        surfaceContainer = c.window, surfaceContainerHigh = c.header, surfaceContainerHighest = c.header,
        error = c.negative,
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent,
        background = c.window, onBackground = c.text, surface = c.window, onSurface = c.text,
        surfaceVariant = c.header, onSurfaceVariant = c.inactiveText, outline = c.border,
        surfaceContainer = c.window, surfaceContainerHigh = c.view, surfaceContainerHighest = c.header,
        error = c.negative,
    )
    CompositionLocalProvider(LocalBreeze provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Built-in wallpapers, loosely inspired by Plasma's default artwork. */
data class Wallpaper(val name: String, val stops: List<Color>, val shards: Color)

val Wallpapers = listOf(
    Wallpaper("Next", listOf(Color(0xFF0E2A47), Color(0xFF1D99F3), Color(0xFF8ED1FC)), Color(0x332ECCFA)),
    Wallpaper("Twilight", listOf(Color(0xFF14121F), Color(0xFF4A2D6E), Color(0xFFE05C8A)), Color(0x33FF9AC1)),
    Wallpaper("Honeywave", listOf(Color(0xFF3A1C0B), Color(0xFFF67400), Color(0xFFFDBC4B)), Color(0x33FFE4A0)),
    Wallpaper("Canopy", listOf(Color(0xFF0B2614), Color(0xFF1E8449), Color(0xFFA9DFBF)), Color(0x3358D68D)),
    Wallpaper("Shell", listOf(Color(0xFF0A0B0D), Color(0xFF232629), Color(0xFF4D5560)), Color(0x22FFFFFF)),
)

fun Wallpaper.brush() = Brush.linearGradient(stops)
