package com.marco.mlmeteo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Stato dell'animazione della card principale. */
class FxState {
    /** Cicli dell'icona trascorsi (vedi [drawWeather]). */
    var phase by mutableFloatStateOf(0f)
    /** Intensità 0..1 (0 = fermo). */
    var amp by mutableFloatStateOf(0f)
    /** Secondi trascorsi, per lo sfondo. */
    var time by mutableFloatStateOf(0f)
}

/** Durata di un ciclo dell'icona (secondi), scelta perché ogni tempo abbia il suo ritmo. */
fun fxPeriod(sky: Sky): Float = when (sky) {
    Sky.CLEAR -> 5f
    Sky.PARTLY -> 4.5f
    Sky.CLOUDY -> 4f
    Sky.FOG -> 5f
    Sky.DRIZZLE -> 1.4f
    Sky.RAIN -> 1.1f
    Sky.SNOW -> 2.6f
    Sky.STORM -> 2.4f
}

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/**
 * Fa girare l'animazione.
 * mode "always": parte e resta animata. "once": fermo → si anima → torna fermo, in un numero
 * intero di cicli, così l'icona finisce esattamente nella posizione di partenza.
 * "icons": come "always" (lo sfondo resta fermo). "off": niente.
 * [trigger] fa ripartire l'animazione (apertura, cambio località, aggiornamento, tocco).
 */
@Composable
fun rememberFx(sky: Sky, mode: String, trigger: Any): FxState {
    val st = remember { FxState() }
    LaunchedEffect(sky, mode, trigger) {
        st.phase = 0f; st.amp = 0f
        if (mode == "off") return@LaunchedEffect
        val period = fxPeriod(sky)
        // "una volta": circa 5 secondi, ma sempre un numero intero di cicli
        val cycles = (5f / period).roundToInt().coerceAtLeast(1)
        val total = cycles * period
        var start = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (start < 0) start = now
            val t = (now - start) / 1e9f
            st.time = t
            if (mode == "once") {
                val prog = (t / total).coerceIn(0f, 1f)
                // la velocità cresce e cala dolcemente: si parte e si arriva fermi
                st.phase = cycles * smooth(prog)
                st.amp = smooth(prog / 0.2f) * smooth((1f - prog) / 0.3f)
                if (prog >= 1f) { st.phase = 0f; st.amp = 0f; break }
            } else {
                st.phase = t / period
                st.amp = smooth(t / 0.8f)
            }
        }
    }
    return st
}

/** Colori di fondo della card secondo il tempo (sopra il colore della card). */
private fun tintFor(sky: Sky, isDay: Boolean, dark: Boolean): List<Color> {
    val a = if (dark) 0.42f else 0.30f
    return when (sky) {
        Sky.CLEAR, Sky.PARTLY -> if (isDay) listOf(Color(0xFF4FA3FF).copy(alpha = a), Color(0xFFFFC64A).copy(alpha = a * 0.6f))
            else listOf(Color(0xFF0B1A3F).copy(alpha = a + 0.2f), Color(0xFF283C7A).copy(alpha = a))
        Sky.CLOUDY -> listOf(Color(0xFF8495A6).copy(alpha = a), Color(0xFF5E6B78).copy(alpha = a * 0.7f))
        Sky.FOG -> listOf(Color(0xFFB0B8C0).copy(alpha = a), Color(0xFF8A939C).copy(alpha = a * 0.6f))
        Sky.DRIZZLE, Sky.RAIN -> listOf(Color(0xFF3D5A80).copy(alpha = a + 0.05f), Color(0xFF6C7F94).copy(alpha = a * 0.7f))
        Sky.SNOW -> listOf(Color(0xFFBFD7EA).copy(alpha = a), Color(0xFF8FA9C2).copy(alpha = a * 0.7f))
        Sky.STORM -> listOf(Color(0xFF2B2440).copy(alpha = a + 0.15f), Color(0xFF4A3F6B).copy(alpha = a))
    }
}

private fun hash(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
}

/**
 * Sfondo animato della card: nuvole che passano, pioggia, neve, lampi, nebbia, stelle.
 * [precip] in mm/h regola quante gocce. [amp] fa comparire e sparire le particelle.
 */
@Composable
fun WeatherBackdrop(modifier: Modifier, sky: Sky, isDay: Boolean, precip: Double, fx: FxState, animate: Boolean) {
    val c = LocalAppColors.current
    val tint = remember(sky, isDay, c.dark) { tintFor(sky, isDay, c.dark) }
    Canvas(modifier) {
        drawRect(Brush.linearGradient(tint, Offset(0f, 0f), Offset(size.width, size.height)))
        val amp = if (animate) fx.amp else 0f
        if (amp <= 0.01f) return@Canvas
        val t = fx.time
        when (sky) {
            Sky.CLEAR, Sky.PARTLY -> if (isDay) sunGlow(t, amp) else stars(t, amp)
            else -> {}
        }
        when (sky) {
            Sky.PARTLY, Sky.CLOUDY, Sky.DRIZZLE, Sky.RAIN, Sky.STORM, Sky.SNOW -> clouds(t, amp, if (sky == Sky.STORM) Color(0xFF3A3550) else if (c.dark) Color(0xFFB8C4D0) else Color.White, if (sky == Sky.PARTLY) 3 else 5)
            Sky.FOG -> fog(t, amp, if (c.dark) Color(0xFFC8CED4) else Color.White)
            else -> {}
        }
        when (sky) {
            Sky.DRIZZLE -> rain(t, amp, 0.4)
            Sky.RAIN -> rain(t, amp, precip)
            Sky.STORM -> { rain(t, amp, precip.coerceAtLeast(4.0)); lightning(t, amp) }
            Sky.SNOW -> snow(t, amp, precip)
            else -> {}
        }
    }
}

private fun DrawScope.sunGlow(t: Float, amp: Float) {
    val r = size.minDimension * (0.55f + 0.05f * sin(t * 1.2f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFD36B).copy(alpha = 0.35f * amp), Color.Transparent), Offset(size.width * 0.18f, size.height * 0.45f), r),
        r, Offset(size.width * 0.18f, size.height * 0.45f),
    )
}

private fun DrawScope.stars(t: Float, amp: Float) {
    for (i in 0 until 40) {
        val x = hash(i, 1) * size.width; val y = hash(i, 2) * size.height
        val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * (1.2f + hash(i, 3) * 2f) + i))
        drawCircle(Color.White.copy(alpha = 0.7f * tw * amp), (0.8f + hash(i, 4) * 1.6f) * density, Offset(x, y))
    }
}

private fun DrawScope.clouds(t: Float, amp: Float, col: Color, n: Int) {
    val w = size.width; val h = size.height
    for (i in 0 until n) {
        val speed = 12f + hash(i, 5) * 18f // dp al secondo
        val r = h * (0.25f + hash(i, 6) * 0.25f)
        val span = w + r * 4
        val x = ((hash(i, 7) * span + t * speed * density) % span) - r * 2
        val y = h * (0.1f + hash(i, 8) * 0.8f)
        val a = 0.16f * amp
        val b = Brush.radialGradient(listOf(col.copy(alpha = a), Color.Transparent), Offset(x, y), r * 1.6f)
        drawCircle(b, r * 1.6f, Offset(x, y))
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), Color.Transparent), Offset(x + r, y + r * 0.2f), r * 1.3f), r * 1.3f, Offset(x + r, y + r * 0.2f))
    }
}

private fun DrawScope.fog(t: Float, amp: Float, col: Color) {
    val w = size.width; val h = size.height
    for (i in 0 until 6) {
        val y = h * (0.15f + i * 0.15f)
        val dx = sin(t * 0.25f + i * 1.7f) * w * 0.15f
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = 0.22f * amp), Color.Transparent), w * 0.1f + dx, w * 0.9f + dx),
            Offset(dx, y), Offset(w + dx, y), strokeWidth = h * 0.09f, cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.rain(t: Float, amp: Float, mmh: Double) {
    val w = size.width; val h = size.height
    val n = (25 + mmh * 12).coerceIn(25.0, 120.0).toInt()
    val len = 14f * density
    val speed = h * 1.6f
    for (i in 0 until n) {
        val x0 = hash(i, 9) * (w + h * 0.25f)
        val off = hash(i, 10)
        val sp = speed * (0.8f + hash(i, 11) * 0.5f)
        val y = ((off * (h + len) + t * sp) % (h + len)) - len
        val x = x0 - (y + len) * 0.2f
        drawLine(Color.White.copy(alpha = (0.25f + 0.2f * hash(i, 12)) * amp), Offset(x, y), Offset(x - len * 0.2f, y + len),
            strokeWidth = 1.4f * density, cap = StrokeCap.Round)
    }
}

private fun DrawScope.snow(t: Float, amp: Float, mmh: Double) {
    val w = size.width; val h = size.height
    val n = (30 + mmh * 20).coerceIn(30.0, 90.0).toInt()
    for (i in 0 until n) {
        val r = (1.5f + hash(i, 13) * 2.5f) * density
        val sp = h * (0.12f + hash(i, 14) * 0.12f)
        val y = ((hash(i, 15) * (h + r * 2) + t * sp) % (h + r * 2)) - r
        val x = hash(i, 16) * w + sin(t * (0.8f + hash(i, 17)) + i) * 10f * density
        drawCircle(Color.White.copy(alpha = (0.5f + 0.3f * hash(i, 18)) * amp), r, Offset(x, y))
    }
}

private fun DrawScope.lightning(t: Float, amp: Float) {
    // un lampo ogni ~3 secondi, doppio, che illumina la card
    val cycle = 3.1f
    val f = t / cycle - floor(t / cycle)
    val flash = when {
        f < 0.03f -> 1f
        f < 0.06f -> 0.2f
        f < 0.09f -> 0.8f
        f < 0.16f -> (0.16f - f) / 0.07f * 0.5f
        else -> 0f
    }
    if (flash > 0f) drawRect(Color.White.copy(alpha = 0.22f * flash * amp))
    @Suppress("UNUSED_VARIABLE") val unused = cos(PI)
}
