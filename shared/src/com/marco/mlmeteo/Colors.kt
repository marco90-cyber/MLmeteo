package com.marco.mlmeteo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Immutable
data class AppColors(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val card2: Color,
    val ink: Color,
    val inkDim: Color,
    val outline: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
)

val LocalAppColors = staticCompositionLocalOf<AppColors> { error("AppColors non fornito") }

/**
 * Stile dell'interfaccia: Material You (Android), Fluent (Windows 11) o Vetro scuro
 * (come la bozza per Windows: ardesia scura, schede compatte con bordo leggero).
 */
data class UiStyle(val id: String, val radius: androidx.compose.ui.unit.Dp, val groupRadius: androidx.compose.ui.unit.Dp, val border: Color?)

val UiStyles = listOf(
    Triple("material", "Material You", "Schede arrotondate, colori dallo sfondo"),
    Triple("fluent", "Fluent", "Come Windows 11: angoli piccoli, bordo sottile, grigi neutri"),
    Triple("glass", "Vetro scuro", "Ardesia scura con schede compatte, come la bozza per Windows"),
)

val LocalUiStyle = staticCompositionLocalOf { UiStyle("material", 24.dp, 28.dp, null) }

/** Forma e fondo di una scheda secondo lo stile scelto. */
@androidx.compose.runtime.Composable
fun Modifier.uiCard(color: Color? = null, group: Boolean = false): Modifier {
    val st = LocalUiStyle.current
    val c = LocalAppColors.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (group) st.groupRadius else st.radius)
    var m = this.clip(shape).background(color ?: c.card)
    if (st.border != null) m = m.border(1.dp, st.border, shape)
    return m
}

/** Applica lo stile ai colori. */
fun styleColors(id: String, c: AppColors): AppColors = when (id) {
    "fluent" -> if (c.dark) c.copy(
        bg = Color(0xFF202020), card = Color(0xFF2B2B2B), card2 = Color(0xFF333333),
        ink = Color(0xFFF3F3F3), inkDim = Color(0xFFC5C5C5), outline = Color(0xFF3D3D3D),
    ) else c.copy(
        bg = Color(0xFFF3F3F3), card = Color(0xFFFDFDFD), card2 = Color(0xFFF0F0F0),
        ink = Color(0xFF1B1B1B), inkDim = Color(0xFF5F5F5F), outline = Color(0xFFE3E3E3),
    )
    "glass" -> c.copy(
        dark = true,
        bg = Color(0xFF1C2127), card = Color(0xFF262C34), card2 = Color(0xFF313842),
        ink = Color(0xFFF2F4F7), inkDim = Color(0xFFA9B3BE), outline = Color(0xFF3B434D),
        accentContainer = lerp(Color(0xFF313842), c.accent, 0.35f), onAccentContainer = Color(0xFFF2F4F7),
    )
    else -> c
}

fun uiStyleOf(id: String) = when (id) {
    "fluent" -> UiStyle("fluent", 8.dp, 8.dp, null)
    "glass" -> UiStyle("glass", 14.dp, 16.dp, Color.White.copy(alpha = 0.06f))
    else -> UiStyle("material", 24.dp, 28.dp, null)
}

val SixColors = listOf(
    0xFFA9A6FF to "Lavanda",
    0xFF7FD0FF to "Azzurro",
    0xFF7BDCB5 to "Menta",
    0xFFFFB77C to "Pesca",
    0xFFFF9FB2 to "Rosa",
    0xFFD7C26B to "Ocra",
)

fun buildColors(dark: Boolean, amoled: Boolean, accent: Color, tint: Color?): AppColors {
    fun t(c: Color, amount: Float) = lerp(c, accent, amount).let { if (tint != null) lerp(it, tint, 0.05f) else it }
    return if (dark) {
        val bg = if (amoled) Color.Black else t(Color(0xFF131218), 0.04f)
        val card = if (amoled) t(Color(0xFF121216), 0.05f) else t(Color(0xFF1F1E26), 0.07f)
        val card2 = if (amoled) t(Color(0xFF1E1E24), 0.08f) else t(Color(0xFF2A2932), 0.10f)
        val container = lerp(card, accent, 0.38f)
        AppColors(
            dark = true, bg = bg, card = card, card2 = card2,
            ink = Color(0xFFE6E1EC), inkDim = Color(0xFFC9C5D0), outline = Color(0xFF3A3944),
            accent = accent, accentContainer = container,
            onAccentContainer = if (container.luminance() > 0.45f) Color(0xFF16151C) else Color.White,
        )
    } else {
        val a = if (accent.luminance() > 0.35f) lerp(accent, Color.Black, 0.45f) else accent
        val card = lerp(Color(0xFFF1EFF6), a, 0.06f)
        val container = lerp(Color.White, a, 0.30f)
        AppColors(
            dark = false,
            bg = lerp(Color(0xFFFBF9FE), a, 0.03f).let { if (tint != null) lerp(it, tint, 0.05f) else it },
            card = card, card2 = lerp(Color(0xFFE4E2EC), a, 0.10f),
            ink = Color(0xFF1C1B21), inkDim = Color(0xFF47464F), outline = Color(0xFFCAC7D3),
            accent = a, accentContainer = container,
            onAccentContainer = Color(0xFF16151C),
        )
    }
}

@Composable
fun isDarkMode(s: Settings): Boolean = if (s.uiStyle == "glass") true else when (s.mode) {
    "light" -> false
    "dark", "amoled" -> true
    else -> isSystemInDarkTheme()
}

/** Sfumatura legata al meteo: calda col sole, fredda con pioggia/neve. */
fun weatherTintFor(code: Int?, isDay: Boolean): Color? = when (code?.let { skyOf(it) }) {
    null -> null
    Sky.CLEAR, Sky.PARTLY -> if (isDay) Color(0xFFFFB74D) else Color(0xFF5C6BC0)
    Sky.RAIN, Sky.DRIZZLE, Sky.STORM -> Color(0xFF4FC3F7)
    Sky.SNOW -> Color(0xFFE1F5FE)
    else -> Color(0xFF90A4AE)
}
