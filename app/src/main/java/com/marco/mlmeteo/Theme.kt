package com.marco.mlmeteo

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/** Colore del tema: dallo sfondo (Android 12+) o scelto a mano. */
@Composable
fun themeAccent(s: Settings, dark: Boolean): Color {
    val ctx = LocalContext.current
    if (s.useWallpaper && Build.VERSION.SDK_INT >= 31) {
        return if (dark) dynamicDarkColorScheme(ctx).primary else dynamicLightColorScheme(ctx).primary
    }
    return Color(s.manualColor)
}

@Composable
fun MLTheme(s: Settings, tint: Color?, content: @Composable () -> Unit) {
    val dark = isDarkMode(s)
    val accent = themeAccent(s, true)
    val c0 = buildColors(dark, s.mode == "amoled", accent, if (s.weatherTint) tint else null)
    val c = styleColors(s.uiStyle, c0)
    val st = uiStyleOf(s.uiStyle).let { if (it.id == "fluent") it.copy(border = c.outline) else it }
    val scheme = if (c.dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = Color(0xFF16151C),
            primaryContainer = c.accentContainer, onPrimaryContainer = c.onAccentContainer,
            secondaryContainer = c.accentContainer, onSecondaryContainer = c.onAccentContainer,
            background = c.bg, onBackground = c.ink, surface = c.bg, onSurface = c.ink,
            surfaceVariant = c.card2, onSurfaceVariant = c.inkDim,
            surfaceContainerHigh = c.card, surfaceContainer = c.card, surfaceContainerHighest = c.card2,
            outline = c.outline, outlineVariant = c.outline,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White,
            primaryContainer = c.accentContainer, onPrimaryContainer = c.onAccentContainer,
            secondaryContainer = c.accentContainer, onSecondaryContainer = c.onAccentContainer,
            background = c.bg, onBackground = c.ink, surface = c.bg, onSurface = c.ink,
            surfaceVariant = c.card2, onSurfaceVariant = c.inkDim,
            surfaceContainerHigh = c.card, surfaceContainer = c.card, surfaceContainerHighest = c.card2,
            outline = c.outline, outlineVariant = c.outline,
        )
    }
    val switchStyle = com.marco.mltheme.MLThemeSettings.get(LocalContext.current).switchStyle
    CompositionLocalProvider(LocalAppColors provides c, LocalUiStyle provides st, com.marco.mltheme.LocalSwitchStyle provides switchStyle) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

