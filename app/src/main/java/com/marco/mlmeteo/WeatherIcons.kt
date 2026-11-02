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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** I 6 set di icone (disegnati dall'app, nessuna licenza esterna). */
val IconSetNames = listOf(
    "Material" to "Monocromatiche, colore del tema",
    "Colorate" to "Piene e colorate",
    "Contorno" to "Linee colorate",
    "Glifi" to "Minimal, un solo colore",
    "3D" to "Sfumate, effetto rilievo",
    "Duotone" to "Due toni dallo sfondo",
)

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
        0, 5 -> accent
        3 -> ink
        else -> when (kind) {
            "sun", "bolt" -> Color(0xFFFFC64A)
            "moon" -> Color(0xFFF4D77A)
            "drop" -> Color(0xFF5DA8F2)
            "snow" -> Color(0xFFDDEEFF)
            "dark" -> Color(0xFF7F8E99)
            else -> if (set == 2) Color(0xFFB9C4CC) else Color(0xFF9EABB5)
        }
    }

    fun brush(kind: String, top: Float, bottom: Float): Brush? = if (set != 4) null else when (kind) {
        "sun" -> Brush.linearGradient(listOf(Color(0xFFFFE38A), Color(0xFFFF9A1F)), Offset(0f, top), Offset(0f, bottom))
        "moon" -> Brush.linearGradient(listOf(Color(0xFFFFF4C2), Color(0xFFE0B84A)), Offset(0f, top), Offset(0f, bottom))
        "dark" -> Brush.linearGradient(listOf(Color(0xFFC9D3DD), Color(0xFF5E7183)), Offset(0f, top), Offset(0f, bottom))
        "cloud" -> Brush.linearGradient(listOf(Color.White, Color(0xFF93A8BC)), Offset(0f, top), Offset(0f, bottom))
        else -> null
    }

    /** Disegna una forma piena secondo lo stile del set. */
    fun DrawScope.shape(path: Path, kind: String, top: Float, bottom: Float) {
        when (set) {
            2 -> drawPath(path, color(kind), style = stroke)
            5 -> {
                drawPath(path, accent.copy(alpha = 0.3f))
                drawPath(path, accent, style = stroke)
            }
            else -> {
                val b = brush(kind, top * k, bottom * k)
                if (b != null) drawPath(path, b) else drawPath(path, color(kind))
            }
        }
    }

    fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float, kind: String, alpha: Float = 1f) {
        if (alpha <= 0.01f) return
        val col = if (set == 4 && kind == "drop") Color(0xFF4FA3FF) else color(kind)
        drawLine(col.copy(alpha = col.alpha * alpha.coerceIn(0f, 1f)), Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = 2.8f * k, cap = StrokeCap.Round)
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
    shape(circle(k, cx, cy, r), "sun", cy - r, cy + r)
    val len = 7f + pulse * 1.3f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0 + rot)
        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        line(cx + c * (r + 3.5f), cy + s * (r + 3.5f), cx + c * (r + len), cy + s * (r + len), "sun")
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
                    if (set == 2 || set == 5) drawCircle(color("snow").let { if (set == 5) accent else it }.copy(alpha = a), 2.2f * k, Offset(x * k, y * k), style = stroke)
                    else drawCircle((if (set == 0 || set == 3) color("snow") else Color(0xFFDDEEFF)).copy(alpha = a), 2.4f * k, Offset(x * k, y * k))
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
