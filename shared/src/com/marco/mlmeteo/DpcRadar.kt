package com.marco.mlmeteo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Radar nazionale della Protezione Civile (prodotto SRI: intensità di pioggia al suolo, mm/h).
 *
 * Ogni file è un GeoTIFF 1200x1400 float32 compresso LZW, proiezione Trasversa di Mercatore
 * (lat 42°, lon 12,5°), pixel di 1 km. L'app lo riproietta in Web Mercator su una griglia fissa
 * e salva ogni frame come intensità a 8 bit (scala logaritmica) per occupare poca memoria.
 * Con gli ultimi frame calcola anche il movimento della pioggia e la "spinge" in avanti
 * fino a 60 minuti (radar previsto).
 */
object DpcRadar {
    // Area coperta (gradi)
    const val NORTH = 47.6
    const val SOUTH = 35.0
    const val WEST = 4.5
    const val EAST = 20.5

    /** Griglia di uscita in Web Mercator. */
    const val W = 1000
    val H: Int by lazy {
        val dx = lonToX(EAST) - lonToX(WEST)
        val dy = latToY(SOUTH) - latToY(NORTH)
        (W * dy / dx).roundToInt()
    }

    const val NODATA: Byte = -1 // 255
    private const val Q_SCALE = 36.0

    fun contains(lat: Double, lon: Double) = lat in 36.0..47.2 && lon in 6.0..19.0

    class Frame(val time: Long, val q: ByteArray, val forecast: Boolean)

    // ------------------------------------------------------------------ rete

    private fun postJson(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "MLMeteo/1.0 (+https://github.com/marco90-cyber/MLmeteo)")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) throw IOException("Radar Protezione Civile: errore ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun lastTime(): Long {
        val o = JSONObject(Net.get("https://radar-api.protezionecivile.it/findLastProductByType?type=SRI"))
        return o.getJSONArray("lastProducts").getJSONObject(0).getLong("time")
    }

    private fun downloadTif(time: Long): ByteArray? {
        val o = JSONObject(postJson("https://radar-api.protezionecivile.it/downloadProduct", "{\"productType\":\"SRI\",\"productDate\":$time}"))
        val url = o.optString("url")
        if (url.isBlank()) return null
        return Net.getBytes(url, 30000)
    }

    /**
     * Scarica gli ultimi [count] frame ogni [stepMin] minuti e aggiunge il previsto.
     * [progress] riceve (scaricati, totali).
     */
    suspend fun load(count: Int = 7, stepMin: Int = 10, progress: (Int, Int) -> Unit = { _, _ -> }): List<Frame> = coroutineScope {
        val last = withContext(Dispatchers.IO) { lastTime() }
        val times = (count - 1 downTo 0).map { last - it * stepMin * 60_000L }
        var done = 0
        val map = withContext(Dispatchers.Default) { mapping }
        val frames = times.chunked(3).flatMap { chunk ->
            chunk.map { t ->
                async(Dispatchers.IO) {
                    val bytes = try { downloadTif(t) } catch (e: Exception) { null }
                    val q = bytes?.let { b -> withContext(Dispatchers.Default) { try { quantize(decodeTiff(b), map) } catch (e: Exception) { null } } }
                    done++
                    progress(done, times.size)
                    q?.let { Frame(t, it, false) }
                }
            }.awaitAll()
        }.filterNotNull()
        if (frames.isEmpty()) throw IOException("Radar Protezione Civile non disponibile")
        val future = withContext(Dispatchers.Default) { nowcast(frames, stepMin) }
        frames + future
    }

    /** Solo l'ultima immagine del radar (per il widget). */
    suspend fun latest(): Frame = withContext(Dispatchers.IO) {
        val t = lastTime()
        val b = downloadTif(t) ?: throw IOException("Radar non disponibile")
        Frame(t, quantize(decodeTiff(b), mapping), false)
    }

    /** Colore (ARGB) del valore quantizzato [q]. */
    fun colorOf(q: Int): Int = palette[q and 0xFF]

    // ------------------------------------------------------------------ TIFF

    class Raster(val w: Int, val h: Int, val data: FloatArray, val originX: Double, val originY: Double, val px: Double, val py: Double)

    fun decodeTiff(b: ByteArray): Raster {
        val le = b[0] == 'I'.code.toByte()
        val bb = ByteBuffer.wrap(b).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val ifd = bb.getInt(4)
        val n = bb.getShort(ifd).toInt() and 0xFFFF
        var w = 0; var h = 0; var comp = 1; var predictor = 1; var bps = 32
        var stripOff = IntArray(0); var stripLen = IntArray(0)
        var originX = -600000.0; var originY = 650000.0; var px = 1000.0; var py = 1000.0
        fun values(type: Int, count: Int, valuePos: Int): IntArray {
            val size = when (type) { 3 -> 2; 4 -> 4; else -> 1 }
            val pos = if (size * count <= 4) valuePos else bb.getInt(valuePos)
            return IntArray(count) { i -> if (type == 3) bb.getShort(pos + i * 2).toInt() and 0xFFFF else bb.getInt(pos + i * 4) }
        }
        fun doubles(count: Int, valuePos: Int): DoubleArray {
            val pos = bb.getInt(valuePos)
            return DoubleArray(count) { i -> bb.getDouble(pos + i * 8) }
        }
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            val tag = bb.getShort(e).toInt() and 0xFFFF
            val type = bb.getShort(e + 2).toInt() and 0xFFFF
            val count = bb.getInt(e + 4)
            when (tag) {
                256 -> w = values(type, 1, e + 8)[0]
                257 -> h = values(type, 1, e + 8)[0]
                258 -> bps = values(type, 1, e + 8)[0]
                259 -> comp = values(type, 1, e + 8)[0]
                317 -> predictor = values(type, 1, e + 8)[0]
                273 -> stripOff = values(type, count, e + 8)
                279 -> stripLen = values(type, count, e + 8)
                33550 -> if (type == 12 && count >= 2) doubles(count, e + 8).let { px = it[0]; py = it[1] }
                33922 -> if (type == 12 && count >= 6) doubles(count, e + 8).let { originX = it[3] - it[0] * px; originY = it[4] + it[1] * py }
            }
        }
        if (bps != 32 || w <= 0 || h <= 0 || stripOff.isEmpty()) throw IOException("Formato radar non riconosciuto")
        val raw = ByteArray(w * h * 4)
        val rowsPerStrip = h / stripOff.size
        for (s in stripOff.indices) {
            val outOff = s * rowsPerStrip * w * 4
            val outLen = (rowsPerStrip * w * 4).coerceAtMost(raw.size - outOff)
            if (comp == 5) lzw(b, stripOff[s], stripLen[s], raw, outOff, outLen)
            else System.arraycopy(b, stripOff[s], raw, outOff, outLen)
        }
        val data = FloatArray(w * h)
        val rb = ByteBuffer.wrap(raw).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        if (predictor == 3) throw IOException("Predittore TIFF non supportato")
        for (i in data.indices) data[i] = rb.getFloat(i * 4)
        return Raster(w, h, data, originX, originY, px, py)
    }

    /** Decompressione LZW nella variante TIFF (MSB, cambio anticipato della lunghezza codice). */
    private fun lzw(input: ByteArray, off: Int, len: Int, out: ByteArray, outOff: Int, outLen: Int) {
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until 256) { suffix[i] = i.toByte(); length[i] = 1; prefix[i] = -1 }
        var pos = off
        val end = (off + len).coerceAtMost(input.size)
        var bitBuf = 0L
        var bitCnt = 0
        var codeLen = 9
        var next = 258
        var old = -1
        var op = outOff
        val outEnd = outOff + outLen
        fun read(): Int {
            while (bitCnt < codeLen) {
                if (pos >= end) return 257
                bitBuf = (bitBuf shl 8) or (input[pos++].toLong() and 0xFF)
                bitCnt += 8
            }
            val c = ((bitBuf shr (bitCnt - codeLen)) and ((1L shl codeLen) - 1)).toInt()
            bitCnt -= codeLen
            bitBuf = bitBuf and ((1L shl bitCnt) - 1)
            return c
        }
        fun write(code: Int): Boolean {
            val l = length[code]
            if (op + l > outEnd) return false
            var c = code
            var i = op + l - 1
            while (i >= op) {
                out[i] = suffix[c]
                c = prefix[c]
                i--
            }
            op += l
            return true
        }
        while (op < outEnd) {
            var code = read()
            if (code == 257) break
            if (code == 256) {
                codeLen = 9; next = 258
                code = read()
                if (code == 257) break
                if (!write(code)) break
                old = code
                continue
            }
            if (old < 0) { if (!write(code)) break; old = code; continue }
            val first: Byte
            if (code < next) {
                val start = op
                if (!write(code)) break
                first = out[start]
            } else {
                // caso KwKwK
                val start = op
                if (!write(old)) break
                first = out[start]
                if (op >= outEnd) break
                out[op++] = first
            }
            if (next < 4096) {
                prefix[next] = old
                suffix[next] = first
                length[next] = length[old] + 1
                next++
                if (next == (1 shl codeLen) - 1 && codeLen < 12) codeLen++
            }
            old = code
        }
    }

    // ------------------------------------------------------------------ proiezione

    /** Per ogni pixel della griglia di uscita, l'indice del pixel radar (o -1). */
    val mapping: IntArray by lazy { buildMapping(1200, 1400, -600000.0, 650000.0, 1000.0, 1000.0) }

    private fun buildMapping(sw: Int, sh: Int, ox: Double, oy: Double, px: Double, py: Double): IntArray {
        val a = 6378137.0
        val f = 1 / 298.257223563
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)
        val lat0 = Math.toRadians(42.0)
        val lon0 = Math.toRadians(12.5)
        val e4 = e2 * e2; val e6 = e4 * e2
        fun m(phi: Double) = a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
            (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
            (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
            (35 * e6 / 3072) * sin(6 * phi))
        val m0 = m(lat0)
        val x0 = lonToX(WEST); val x1 = lonToX(EAST)
        val y0 = latToY(NORTH); val y1 = latToY(SOUTH)
        val h = H
        val out = IntArray(W * h)
        val lons = DoubleArray(W) { i -> Math.toRadians(xToLon(x0 + (i + 0.5) / W * (x1 - x0))) }
        for (j in 0 until h) {
            val phi = Math.toRadians(yToLat(y0 + (j + 0.5) / h * (y1 - y0)))
            val sp = sin(phi); val cp = cos(phi); val tp = tan(phi)
            val nn = a / sqrt(1 - e2 * sp * sp)
            val t = tp * tp
            val c = ep2 * cp * cp
            val mm = m(phi)
            for (i in 0 until W) {
                val aa = (lons[i] - lon0) * cp
                val a2 = aa * aa
                val x = nn * (aa + (1 - t + c) * a2 * aa / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a2 * a2 * aa / 120)
                val y = mm - m0 + nn * tp * (a2 / 2 + (5 - t + 9 * c + 4 * c * c) * a2 * a2 / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a2 * a2 * a2 / 720)
                val col = ((x - ox) / px).toInt()
                val row = ((oy - y) / py).toInt()
                out[j * W + i] = if (col in 0 until sw && row in 0 until sh) row * sw + col else -1
            }
        }
        return out
    }

    private fun quantize(r: Raster, map: IntArray): ByteArray {
        val q = ByteArray(map.size)
        for (p in map.indices) {
            val idx = map[p]
            if (idx < 0 || idx >= r.data.size) { q[p] = NODATA; continue }
            val v = r.data[idx]
            q[p] = when {
                v.isNaN() || v < -1f -> NODATA
                v < 0.1f -> 0
                else -> (ln(1.0 + v) / ln(2.0) * Q_SCALE).roundToInt().coerceIn(1, 254).toByte()
            }
        }
        return q
    }

    fun mmh(q: Int): Double = 2.0.pow(q / Q_SCALE) - 1

    // ------------------------------------------------------------------ colori

    val colorStops = listOf(
        0.1 to Color(0xAA9BE7FF), 0.5 to Color(0xCC00C8FF), 1.0 to Color(0xDD0096FF), 2.0 to Color(0xDD00B400),
        5.0 to Color(0xEE00E600), 10.0 to Color(0xEEFFFF00), 20.0 to Color(0xEEFF9600), 40.0 to Color(0xEEFF0000),
        70.0 to Color(0xEEC800FF),
    )

    private val palette: IntArray by lazy {
        IntArray(256) { q ->
            when (q) {
                0, 255 -> 0
                else -> {
                    val v = mmh(q)
                    var col = colorStops.first().second
                    for (k in 1 until colorStops.size) {
                        val (a, ca) = colorStops[k - 1]
                        val (b, cb) = colorStops[k]
                        if (v <= b) { col = lerp(ca, cb, ((v - a) / (b - a)).toFloat().coerceIn(0f, 1f)); break }
                        col = cb
                    }
                    col.toArgb()
                }
            }
        }
    }

    fun render(q: ByteArray): ImageBitmap {
        val h = H
        val px = IntArray(W * h)
        val pal = palette
        for (i in px.indices) px[i] = pal[q[i].toInt() and 0xFF]
        return imageFromPixels(px, W, h)
    }

    // ------------------------------------------------------------------ radar previsto

    /**
     * Stima il movimento della pioggia tra il penultimo frame (20 min prima) e l'ultimo
     * confrontando blocchi, poi sposta l'ultimo frame in avanti di 10..60 minuti.
     */
    private fun nowcast(frames: List<Frame>, stepMin: Int): List<Frame> {
        if (frames.size < 3) return emptyList()
        val now = frames.last()
        val prev = frames[frames.size - 3] // 20 minuti prima
        val gapMin = ((now.time - prev.time) / 60_000.0).coerceAtLeast(1.0)
        val h = H
        // griglia ridotta 4x (valore massimo nel blocco)
        val f = 4
        val dw = W / f; val dh = h / f
        fun down(q: ByteArray): IntArray {
            val d = IntArray(dw * dh)
            for (y in 0 until dh) for (x in 0 until dw) {
                var mx = 0
                for (yy in 0 until f) for (xx in 0 until f) {
                    val v = q[(y * f + yy) * W + x * f + xx].toInt() and 0xFF
                    if (v != 255 && v > mx) mx = v
                }
                d[y * dw + x] = mx
            }
            return d
        }
        val a = down(prev.q)
        val b = down(now.q)
        val bs = 12
        val step = 6
        val r = 6
        val bx = (dw - bs) / step + 1
        val by = (dh - bs) / step + 1
        val vx = DoubleArray(bx * by) { Double.NaN }
        val vy = DoubleArray(bx * by) { Double.NaN }
        val goodX = ArrayList<Double>(); val goodY = ArrayList<Double>()
        for (j in 0 until by) for (i in 0 until bx) {
            val ox = i * step; val oy = j * step
            var wet = 0
            for (y in 0 until bs) for (x in 0 until bs) if (b[(oy + y) * dw + ox + x] > 20) wet++
            if (wet < bs * bs / 10) continue
            var best = Long.MAX_VALUE; var bdx = 0; var bdy = 0
            for (dy in -r..r) for (dx in -r..r) {
                var cost = 0L
                for (y in 0 until bs) {
                    val sy = oy + y - dy
                    for (x in 0 until bs) {
                        val sx = ox + x - dx
                        val pv = if (sx in 0 until dw && sy in 0 until dh) a[sy * dw + sx] else 0
                        cost += abs(b[(oy + y) * dw + ox + x] - pv)
                    }
                    if (cost >= best) break
                }
                if (cost < best || (cost == best && dx * dx + dy * dy < bdx * bdx + bdy * bdy)) { best = cost; bdx = dx; bdy = dy }
            }
            vx[j * bx + i] = bdx.toDouble(); vy[j * bx + i] = bdy.toDouble()
            goodX += bdx.toDouble(); goodY += bdy.toDouble()
        }
        if (goodX.isEmpty()) return emptyList()
        val medX = goodX.sorted()[goodX.size / 2]
        val medY = goodY.sorted()[goodY.size / 2]
        // riempi i buchi con la mediana e leviga (media 3x3)
        val fx = DoubleArray(vx.size) { if (vx[it].isNaN()) medX else vx[it] }
        val fy = DoubleArray(vy.size) { if (vy[it].isNaN()) medY else vy[it] }
        val sx = DoubleArray(fx.size); val sy = DoubleArray(fy.size)
        for (j in 0 until by) for (i in 0 until bx) {
            var tx = 0.0; var ty = 0.0; var nn = 0
            for (jj in (j - 1)..(j + 1)) for (ii in (i - 1)..(i + 1)) {
                if (ii in 0 until bx && jj in 0 until by) { tx += fx[jj * bx + ii]; ty += fy[jj * bx + ii]; nn++ }
            }
            sx[j * bx + i] = tx / nn; sy[j * bx + i] = ty / nn
        }
        // velocità per pixel (pixel pieni per minuto), interpolando i centri dei blocchi
        fun field(px: Int, py: Int, arr: DoubleArray): Double {
            val gx = ((px.toDouble() / f - bs / 2.0) / step).coerceIn(0.0, (bx - 1).toDouble())
            val gy = ((py.toDouble() / f - bs / 2.0) / step).coerceIn(0.0, (by - 1).toDouble())
            val i0 = gx.toInt().coerceAtMost(bx - 2).coerceAtLeast(0); val j0 = gy.toInt().coerceAtMost(by - 2).coerceAtLeast(0)
            val i1 = (i0 + 1).coerceAtMost(bx - 1); val j1 = (j0 + 1).coerceAtMost(by - 1)
            val tx = (gx - i0).coerceIn(0.0, 1.0); val ty = (gy - j0).coerceIn(0.0, 1.0)
            val top = arr[j0 * bx + i0] * (1 - tx) + arr[j0 * bx + i1] * tx
            val bot = arr[j1 * bx + i0] * (1 - tx) + arr[j1 * bx + i1] * tx
            return (top * (1 - ty) + bot * ty) * f / gapMin
        }
        // campo a risoluzione ridotta per velocità
        val cw = W / 8; val ch = h / 8
        val cvx = FloatArray(cw * ch); val cvy = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            cvx[y * cw + x] = field(x * 8 + 4, y * 8 + 4, sx).toFloat()
            cvy[y * cw + x] = field(x * 8 + 4, y * 8 + 4, sy).toFloat()
        }
        val out = ArrayList<Frame>()
        for (k in 1..(60 / stepMin)) {
            val lead = k * stepMin
            val q = ByteArray(W * h)
            for (y in 0 until h) {
                val cy = (y / 8).coerceAtMost(ch - 1)
                for (x in 0 until W) {
                    val cx = (x / 8).coerceAtMost(cw - 1)
                    val srcX = (x - cvx[cy * cw + cx] * lead).roundToInt()
                    val srcY = (y - cvy[cy * cw + cx] * lead).roundToInt()
                    q[y * W + x] = if (srcX in 0 until W && srcY in 0 until h) {
                        val v = now.q[srcY * W + srcX]
                        // fuori dalla copertura radar di oggi: lascia la copertura del frame attuale
                        if (v == NODATA) now.q[y * W + x].let { if (it == NODATA) NODATA else 0 } else v
                    } else now.q[y * W + x].let { if (it == NODATA) NODATA else 0 }
                }
            }
            out += Frame(now.time + lead * 60_000L, q, true)
        }
        return out
    }
}

@Suppress("unused")
private fun deg(x: Double) = x * 180 / PI
