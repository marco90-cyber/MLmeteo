package com.marco.mlmeteo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Set di icone (id, nome, descrizione). Tutte disegnate dall'app, nessuna licenza esterna. */
val IconSets = listOf(
    Triple(1, "Colorate", "Piene e colorate"),
    Triple(4, "3D", "Sfumate, effetto rilievo"),
    Triple(6, "Vivaci", "Nuvole bianche con il bordo, colori pieni"),
    Triple(7, "Pastello", "Colori morbidi con un'ombra leggera"),
    Triple(8, "Raggi tondi", "Sole con raggi spessi e arrotondati"),
    Triple(9, "Morbido", "Nuvole sfumate dal bianco"),
    Triple(10, "Contrasto", "Nuvole scure, sole arancione"),
    Triple(11, "Due toni", "Nuvole chiare con la base più scura"),
)

fun iconSetName(id: Int) = IconSets.firstOrNull { it.first == id }?.second ?: "Colorate"

/** I set tolti (Material, Contorno, Glifi, Duotone) diventano "Colorate". */
fun validIconSet(id: Int) = if (IconSets.any { it.first == id }) id else 1

@Composable
fun WeatherIcon(code: Int, isDay: Boolean, size: Dp = 32.dp, set: Int = AppState.settings.iconSet, phase: Float = 0f, amp: Float = 0f) {
    val c = LocalAppColors.current
    Canvas(Modifier.size(size)) {
        drawWeather(skyOf(code), isDay, set, c.accent, c.ink, phase, amp)
    }
}

private class Pal(
    val set: Int,
    val accent: Color,
    val ink: Color,
    val k: Float,
) {
    val stroke get() = Stroke(width = 2.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun color(kind: String): Color = when (set) {
        6 -> when (kind) {
            "sun", "bolt" -> Color(0xFFFFC531); "moon" -> Color(0xFFF4D77A); "drop" -> Color(0xFF3D8BFF)
            "snow" -> Color(0xFFFFFFFF); "dark" -> Color(0xFF8C99A6); else -> Color(0xFFFFFFFF)
        }
        7 -> when (kind) {
            "sun" -> Color(0xFFFFE29A); "bolt" -> Color(0xFFFFD98A); "moon" -> Color(0xFFFFF1B8); "drop" -> Color(0xFF9EC5FE)
            "snow" -> Color(0xFFF2F5FA); "dark" -> Color(0xFFB7C0CE); else -> Color(0xFFF2F5FA)
        }
        8 -> when (kind) {
            "sun", "bolt" -> Color(0xFFFFB300); "moon" -> Color(0xFFF4D77A); "drop" -> Color(0xFF4FA3FF)
            "snow" -> Color(0xFFDDEEFF); "dark" -> Color(0xFF9AA6B2); else -> Color(0xFFE9EEF4)
        }
        9 -> when (kind) {
            "sun", "bolt" -> Color(0xFFFFC531); "moon" -> Color(0xFFF4D77A); "drop" -> Color(0xFF4FA3FF)
            "snow" -> Color(0xFFE8F1FF); "dark" -> Color(0xFF7D8FA3); else -> Color(0xFFDCE4EE)
        }
        10 -> when (kind) {
            "sun" -> Color(0xFFFF8A3D); "bolt" -> Color(0xFFFFB020); "moon" -> Color(0xFFFFB067); "drop" -> Color(0xFF63B3ED)
            "snow" -> Color(0xFFE2E8F0); "dark" -> Color(0xFF2D3748); else -> Color(0xFF4A5568)
        }
        11 -> when (kind) {
            "sun", "bolt" -> Color(0xFFFFC531); "moon" -> Color(0xFFF4D77A); "drop" -> Color(0xFF4FA3FF)
            "snow" -> Color(0xFFE8F1FF); "dark" -> Color(0xFF8A97A8); else -> Color(0xFFDCE3EC)
        }
        else -> when (kind) {
            "sun", "bolt" -> Color(0xFFFFC64A)
            "moon" -> Color(0xFFF4D77A)
            "drop" -> Color(0xFF5DA8F2)
            "snow" -> Color(0xFFDDEEFF)
            "dark" -> Color(0xFF7F8E99)
            else -> Color(0xFF9EABB5)
        }
    }

    /** Colore della parte bassa (Due toni), del bordo (Vivaci) o dell'ombra (Pastello). */
    fun second(kind: String): Color = when (set) {
        6 -> if (kind == "dark") Color(0xFF5F6B78) else Color(0xFFA9B6C3)
        7 -> if (kind == "dark") Color(0xFF8E98A8) else Color(0xFFC9D3E3)
        11 -> if (kind == "dark") Color(0xFF5E6B7C) else Color(0xFFAEBBCB)
        else -> Color.Transparent
    }

    fun brush(kind: String, top: Float, bottom: Float): Brush? = when (set) {
        4 -> when (kind) {
            "sun" -> Brush.linearGradient(listOf(Color(0xFFFFE38A), Color(0xFFFF9A1F)), Offset(0f, top), Offset(0f, bottom))
            "moon" -> Brush.linearGradient(listOf(Color(0xFFFFF4C2), Color(0xFFE0B84A)), Offset(0f, top), Offset(0f, bottom))
            "dark" -> Brush.linearGradient(listOf(Color(0xFFC9D3DD), Color(0xFF5E7183)), Offset(0f, top), Offset(0f, bottom))
            "cloud" -> Brush.linearGradient(listOf(Color.White, Color(0xFF93A8BC)), Offset(0f, top), Offset(0f, bottom))
            else -> null
        }
        9 -> when (kind) {
            "dark" -> Brush.linearGradient(listOf(Color(0xFFDDE4EC), Color(0xFF6E8196)), Offset(0f, top), Offset(0f, bottom))
            "cloud" -> Brush.linearGradient(listOf(Color.White, Color(0xFFB9C7D6)), Offset(0f, top), Offset(0f, bottom))
            else -> null
        }
        else -> null
    }

    /** Disegna una forma piena secondo lo stile del set. */
    fun DrawScope.shape(path: Path, kind: String, top: Float, bottom: Float) {
        val isCloud = kind == "cloud" || kind == "dark"
        when {
            set == 7 && isCloud -> {
                // ombra leggera spostata in basso a destra
                translate(2.5f * k, 3.5f * k) { drawPath(path, second(kind)) }
                drawPath(path, color(kind))
            }
            set == 6 && isCloud -> {
                drawPath(path, second(kind), style = Stroke(width = 3f * k, join = StrokeJoin.Round))
                drawPath(path, color(kind))
            }
            set == 11 && isCloud -> {
                drawPath(path, color(kind))
                val cut = (top + (bottom - top) * 0.62f) * k
                clipPath(path) { drawRect(second(kind), topLeft = Offset(0f, cut), size = androidx.compose.ui.geometry.Size(size.width, size.height - cut)) }
            }
            else -> {
                val b = brush(kind, top * k, bottom * k)
                if (b != null) drawPath(path, b) else drawPath(path, color(kind))
            }
        }
    }

    /** Spessore delle linee (raggi e gocce). */
    val lineW get() = if (set == 8) 3.6f else 2.8f

    fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float, kind: String, alpha: Float = 1f) {
        if (alpha <= 0.01f) return
        val col = color(kind)
        drawLine(col.copy(alpha = col.alpha * alpha.coerceIn(0f, 1f)), Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = lineW * k, cap = StrokeCap.Round)
    }
}

private fun circle(k: Float, cx: Float, cy: Float, r: Float) =
    Path().apply { addOval(Rect(Offset(cx * k, cy * k), r * k)) }

private fun union(a: Path, b: Path) = Path().apply { op(a, b, PathOperation.Union) }

/** Nuvola: in coordinate 48x48, scala [s] e spostamento [dx], [dy]. */
private fun cloudPath(k: Float, s: Float = 1f, dx: Float = 0f, dy: Float = 0f): Path {
    fun x(v: Float) = v * s + dx
    fun y(v: Float) = v * s + dy
    var p = circle(k, x(17f), y(28f), 8f * s)
    p = union(p, circle(k, x(26f), y(21f), 11f * s))
    p = union(p, circle(k, x(35f), y(28f), 8f * s))
    val r = Path().apply { addRect(Rect(x(17f) * k, y(26f) * k, x(35f) * k, y(36f) * k)) }
    return union(p, r)
}

private fun moonPath(k: Float, cx: Float, cy: Float, r: Float): Path {
    val a = circle(k, cx, cy, r)
    val b = circle(k, cx + r * 0.55f, cy - r * 0.4f, r * 0.85f)
    return Path().apply { op(a, b, PathOperation.Difference) }
}

/** Sole: [rot] gradi di rotazione dei raggi, [pulse] -1..1 allunga/accorcia i raggi. */
private fun Pal.sun(scope: DrawScope, cx: Float, cy: Float, r: Float, rot: Float = 0f, pulse: Float = 0f) = with(scope) {
    val round = set == 8
    val rr = if (round) r * 0.85f else r
    shape(circle(k, cx, cy, rr), "sun", cy - rr, cy + rr)
    val start = if (round) rr + 4.5f else r + 3.5f
    val len = (if (round) rr + 6.5f else r + 7f) + pulse * 1.3f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0 + rot)
        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        line(cx + c * start, cy + s * start, cx + c * len, cy + s * len, "sun")
    }
}

private fun frac(x: Float) = x - kotlin.math.floor(x)

/**
 * Disegna l'icona. [phase] = cicli di animazione trascorsi, [amp] = intensità 0..1:
 * con amp = 0 l'icona è ferma (identica a quella statica), con amp = 1 è animata del tutto.
 * Ogni movimento è moltiplicato per [amp], così l'animazione parte e torna ferma senza scatti.
 */
fun DrawScope.drawWeather(sky: Sky, isDay: Boolean, set: Int, accent: Color, ink: Color, phase: Float = 0f, amp: Float = 0f) {
    val k = size.minDimension / 48f
    val p = Pal(set, accent, ink, k)
    val tw = (2 * Math.PI * phase).toFloat()
    val sway = amp * kotlin.math.sin(tw)
    val still = 1f - amp
    with(p) {
        // gocce che cadono dalla nuvola: quelle ferme sfumano, quelle in movimento compaiono
        fun drops(xs: List<Float>, y0: Float, len: Float, slant: Float, fall: Float) {
            for (x in xs) line(x, y0, x - slant, y0 + len, "drop", still)
            if (amp > 0.01f) xs.forEachIndexed { i, x ->
                val o = frac(phase + i / xs.size.toFloat())
                val y = y0 - 2f + o * fall
                line(x + o * slant * 0.5f, y, x - slant + o * slant * 0.5f, y + len, "drop", amp * (1f - o * o))
            }
        }
        when (sky) {
            Sky.CLEAR -> if (isDay) sun(this@drawWeather, 24f, 24f, 9f, rot = phase * 45f, pulse = sway)
            else {
                rotate(sway * 7f, Offset(24f * k, 24f * k)) { shape(moonPath(k, 24f, 24f, 14f), "moon", 10f, 38f) }
                if (amp > 0.01f) listOf(Triple(9f, 11f, 0f), Triple(40f, 9f, 2f), Triple(39f, 39f, 4f)).forEach { (x, y, ph) ->
                    val tw2 = 0.5f + 0.5f * kotlin.math.sin(tw + ph)
                    drawCircle(color("moon").copy(alpha = amp * tw2), (1.1f + tw2) * k, Offset(x * k, y * k))
                }
            }

            Sky.PARTLY -> {
                if (isDay) sun(this@drawWeather, 17f, 16f, 6.5f, rot = phase * 45f, pulse = sway)
                else shape(moonPath(k, 18f, 15f, 9f), "moon", 6f, 24f)
                shape(cloudPath(k, 0.85f, 8f + sway * 2.2f, 9f), "cloud", 19f, 40f)
            }

            Sky.CLOUDY -> {
                if (amp > 0.01f) shape(cloudPath(k, 0.55f, 2f - sway * 2.5f, 2f), "cloud", 5f, 22f)
                shape(cloudPath(k, 1f, sway * 2.5f, 3f), "cloud", 13f, 39f)
            }

            Sky.FOG -> {
                shape(cloudPath(k, 1f, sway * 1.5f, -8f), "cloud", 2f, 28f)
                line(11f + sway * 3f, 34f, 37f + sway * 3f, 34f, "cloud")
                line(11f - sway * 3f, 39f, 37f - sway * 3f, 39f, "cloud")
                line(14f + sway * 2f, 44f, 34f + sway * 2f, 44f, "cloud")
            }

            Sky.DRIZZLE -> {
                shape(cloudPath(k, 1f, sway * 1.2f, -8f), "cloud", 2f, 28f)
                for (x in listOf(17f, 25f, 33f)) line(x, 34f, x - 0.6f, 36f, "drop", still)
                for (x in listOf(21f, 29f)) line(x, 40f, x - 0.6f, 42f, "drop", still)
                if (amp > 0.01f) listOf(17f, 25f, 33f, 21f, 29f).forEachIndexed { i, x ->
                    val o = frac(phase + i / 5f)
                    line(x, 32f + o * 12f, x - 0.6f, 34f + o * 12f, "drop", amp * (1f - o * o))
                }
            }

            Sky.RAIN -> {
                shape(cloudPath(k, 1f, sway * 1.2f, -8f), "cloud", 2f, 28f)
                drops(listOf(17f, 25f, 33f), 33f, 8f, 2f, 10f)
            }

            Sky.SNOW -> {
                shape(cloudPath(k, 1f, sway * 1.2f, -8f), "cloud", 2f, 28f)
                val flakes = listOf(16f to 35f, 24f to 39f, 32f to 35f, 20f to 44f, 28f to 44f)
                fun flake(x: Float, y: Float, a: Float) {
                    if (a <= 0.01f) return
                    if (set == 6 || set == 10) drawCircle(Color(0xFF8C99A6).copy(alpha = a), 3.1f * k, Offset(x * k, y * k))
                    drawCircle(color("snow").copy(alpha = a), 2.4f * k, Offset(x * k, y * k))
                }
                for ((x, y) in flakes) flake(x, y, still)
                if (amp > 0.01f) flakes.forEachIndexed { i, (x, _) ->
                    val o = frac(phase + i / 5f)
                    flake(x + kotlin.math.sin((o * 2 + i) * Math.PI.toFloat()) * 2f, 32f + o * 14f, amp * (1f - o * o))
                }
            }

            Sky.STORM -> {
                shape(cloudPath(k, 1f, sway * 1.2f, -8f), "dark", 2f, 28f)
                val bolt = Path().apply {
                    moveTo(25f * k, 29f * k); lineTo(18f * k, 39f * k); lineTo(24f * k, 39f * k)
                    lineTo(21f * k, 47f * k); lineTo(31f * k, 35f * k); lineTo(25f * k, 35f * k)
                    lineTo(28f * k, 29f * k); close()
                }
                // lampo: il fulmine sparisce e riappare più luminoso, due volte per ciclo
                val f = frac(phase)
                val flash = if (f < 0.06f || (f in 0.12f..0.17f)) 1f else 0f
                val dim = if (f in 0.06f..0.12f) amp * 0.85f else 0f
                if (dim < 0.8f) shape(bolt, "bolt", 29f, 47f)
                if (flash * amp > 0.01f) drawPath(bolt, Color.White.copy(alpha = 0.75f * amp))
            }
        }
    }
}
