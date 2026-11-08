package com.marco.mlmeteo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Indicatori animati delle schede dei dettagli (umidità, vento, pioggia, pressione, UV, aria).
 * Si riempiono quando compaiono; se le animazioni sono attive hanno un piccolo movimento continuo.
 */

/** Valore che cresce da 0 al valore vero quando la scheda compare. */
@Composable
fun rememberGrow(target: Float): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target, tween(1100, easing = FastOutSlowInEasing)) }
    return a.value
}

/** Secondi trascorsi (0 se le animazioni sono spente). */
@Composable
fun gaugeClock(): Float {
    if (AppState.settings.animations == "off") return 0f
    val tr = rememberInfiniteTransition(label = "indicatori")
    val t by tr.animateFloat(0f, 3600f, infiniteRepeatable(tween(3_600_000, easing = LinearEasing)), label = "t")
    return t
}

/** Umidità: tubo verticale che si riempie d'acqua, con l'onda in superficie. */
@Composable
fun HumidityGauge(modifier: Modifier, fraction: Float) {
    val c = LocalAppColors.current
    val f = rememberGrow(fraction.coerceIn(0f, 1f))
    val t = gaugeClock()
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val tube = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(w / 2))) }
        drawPath(tube, c.outline)
        clipPath(tube) {
            val level = h * (1 - f)
            val wave = Path().apply {
                moveTo(0f, h)
                lineTo(0f, level)
                val steps = 12
                for (i in 0..steps) {
                    val x = w * i / steps
                    lineTo(x, level + sin(t * 3f + i * 0.9f) * 2.2f * density * if (f > 0.02f) 1f else 0f)
                }
                lineTo(w, h)
                close()
            }
            drawPath(wave, Brush.verticalGradient(listOf(Color(0xFF8FD3FF), Color(0xFF3D8BFF)), level, h))
        }
    }
}

/** Scala verticale (pressione, UV): binario colorato e pallino sul valore. */
@Composable
fun VerticalScale(modifier: Modifier, fraction: Float, colors: List<Color>) {
    val c = LocalAppColors.current
    val f = rememberGrow(fraction.coerceIn(0f, 1f))
    val t = gaugeClock()
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val trackW = w * 0.32f
        val x = w * 0.62f
        drawRoundRect(Brush.verticalGradient(colors.reversed().map { it.copy(alpha = 0.55f) }), Offset(x - trackW / 2, 0f), Size(trackW, h), CornerRadius(trackW / 2))
        val r = w * 0.3f
        val y = (h - r) - (h - 2 * r) * f
        val pulse = 1f + 0.08f * sin(t * 2.5f)
        val col = colors[((colors.size - 1) * f).toInt().coerceIn(0, colors.size - 1)]
        drawCircle(col.copy(alpha = 0.25f), r * 1.5f * pulse, Offset(x, y))
        drawCircle(col, r, Offset(x, y))
        drawCircle(c.card, r * 0.45f, Offset(x, y))
        // freccetta a sinistra
        val p = Path().apply { moveTo(0f, y - r * 0.6f); lineTo(r * 0.9f, y); lineTo(0f, y + r * 0.6f); close() }
        drawPath(p, col)
    }
}

/** Bussola del vento: la freccia indica dove va il vento e ondeggia un poco. */
@Composable
fun WindCompass(modifier: Modifier, dirDeg: Double, speedKmh: Double) {
    val c = LocalAppColors.current
    val t = gaugeClock()
    val appear = rememberGrow(1f)
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(c.outline, r - 1.5f * density, style = Stroke(2f * density))
        for (i in 0 until 12) {
            val a = i * PI / 6
            val len = if (i % 3 == 0) 6f else 3f
            drawLine(c.inkDim.copy(alpha = 0.6f),
                Offset(center.x + (r - 4 * density) * cos(a).toFloat(), center.y + (r - 4 * density) * sin(a).toFloat()),
                Offset(center.x + (r - (4 + len) * density) * cos(a).toFloat(), center.y + (r - (4 + len) * density) * sin(a).toFloat()),
                1.5f * density)
        }
        val n = tm.measure("N", TextStyle(color = c.inkDim, fontSize = 10.sp))
        drawText(n, topLeft = Offset(center.x - n.size.width / 2f, 6f * density))
        if (dirDeg.ok()) {
            val wobble = sin(t * (1.5f + (speedKmh / 40.0).toFloat().coerceAtMost(2f))) * (3f + (speedKmh / 10.0).toFloat().coerceAtMost(6f))
            rotate((dirDeg + 180).toFloat() * appear + wobble, center) {
                val tip = Offset(center.x, center.y - r * 0.62f)
                val tail = Offset(center.x, center.y + r * 0.45f)
                drawLine(Color(0xFFEF9F27), tail, tip, 3.5f * density, cap = StrokeCap.Round)
                val head = Path().apply {
                    moveTo(tip.x, tip.y - 3 * density)
                    lineTo(tip.x - 7 * density, tip.y + 9 * density)
                    lineTo(tip.x + 7 * density, tip.y + 9 * density)
                    close()
                }
                drawPath(head, Color(0xFFEF9F27))
            }
        }
    }
}

/** Fondo scala "comodo" per i mm: 2, 5, 10, 20, 50, 100. */
fun beakerMax(mm: Double): Double = listOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0).firstOrNull { mm <= it * 0.92 } ?: ceil(mm / 100) * 100

/** Becher graduato: si riempie in base ai mm di pioggia; se piove, cadono gocce dentro. */
@Composable
fun RainBeaker(modifier: Modifier, mm: Double, raining: Boolean) {
    val c = LocalAppColors.current
    val max = beakerMax(if (mm.ok()) mm else 0.0)
    val f = rememberGrow(((if (mm.ok()) mm else 0.0) / max).toFloat().coerceIn(0f, 1f))
    val t = gaugeClock()
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val top = h * 0.12f; val bottom = h - 2 * density
        val l = w * 0.30f; val r = w * 0.92f
        val glass = Path().apply {
            moveTo(l - 4 * density, top - 3 * density) // beccuccio
            lineTo(l, top)
            lineTo(l, bottom - 6 * density)
            quadraticBezierTo(l, bottom, l + 6 * density, bottom)
            lineTo(r - 6 * density, bottom)
            quadraticBezierTo(r, bottom, r, bottom - 6 * density)
            lineTo(r, top)
        }
        val inner = Path().apply {
            moveTo(l, top); lineTo(l, bottom - 6 * density); quadraticBezierTo(l, bottom, l + 6 * density, bottom)
            lineTo(r - 6 * density, bottom); quadraticBezierTo(r, bottom, r, bottom - 6 * density); lineTo(r, top); close()
        }
        // acqua
        clipPath(inner) {
            val level = bottom - (bottom - top) * f
            val wave = Path().apply {
                moveTo(l, bottom)
                lineTo(l, level)
                for (i in 0..10) {
                    val x = l + (r - l) * i / 10
                    lineTo(x, level + sin(t * 3.2f + i * 0.8f) * 1.8f * density * if (f > 0.01f) 1f else 0f)
                }
                lineTo(r, bottom); close()
            }
            drawPath(wave, Brush.verticalGradient(listOf(Color(0xFF8FD3FF), Color(0xFF1E88E5)), level, bottom))
        }
        drawPath(glass, c.inkDim, style = Stroke(2f * density, cap = StrokeCap.Round))
        // tacche graduate e numeri a sinistra
        for (i in 0..4) {
            val y = bottom - (bottom - top) * i / 4f
            val long = i % 2 == 0
            drawLine(c.inkDim.copy(alpha = 0.8f), Offset(r - (if (long) 12 else 7) * density, y), Offset(r - 2 * density, y), 1.3f * density)
            if (long && i > 0) {
                val v = max * i / 4
                val txt = if (v >= 10 || v % 1.0 == 0.0) "${v.toInt()}" else String.format("%.1f", v).replace('.', ',')
                val m = tm.measure(txt, TextStyle(color = c.inkDim, fontSize = 9.sp))
                drawText(m, topLeft = Offset((l - 6 * density - m.size.width).coerceAtLeast(0f), y - m.size.height / 2f))
            }
        }
        // gocce che cadono nel becher
        if (raining && t > 0f) {
            for (k in 0 until 3) {
                val ph = ((t * 0.9f + k / 3f) % 1f)
                val x = l + (r - l) * (0.3f + 0.2f * k)
                val y = -4 * density + ph * (bottom - (bottom - top) * f + 4 * density)
                drawLine(Color(0xFF3D8BFF).copy(alpha = 1f - ph * 0.6f), Offset(x, y), Offset(x - 1 * density, y + 6 * density), 2.2f * density, cap = StrokeCap.Round)
            }
        }
    }
}

/** Barra dell'aria: dal verde al viola, con il pallino che scivola sul valore. */
@Composable
fun AqiBar(modifier: Modifier, aqi: Double) {
    val c = LocalAppColors.current
    val f = rememberGrow(((if (aqi.ok()) aqi else 0.0) / 120.0).toFloat().coerceIn(0f, 1f))
    Canvas(modifier) {
        val h = size.height
        val bh = h * 0.45f
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF4CAF50), Color(0xFFCDDC39), Color(0xFFFFC107), Color(0xFFFF7043), Color(0xFFE53935), Color(0xFF8E24AA))),
            Offset(0f, (h - bh) / 2), Size(size.width, bh), CornerRadius(bh / 2),
        )
        val x = (size.width - h) * f + h / 2
        drawCircle(c.card, h / 2, Offset(x, h / 2))
        drawCircle(c.ink, h / 2, Offset(x, h / 2), style = Stroke(2f * density))
    }
}
