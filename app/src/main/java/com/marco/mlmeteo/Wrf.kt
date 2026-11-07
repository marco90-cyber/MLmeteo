package com.marco.mlmeteo

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
 * Modello WRF di ItaliaMeteo: 1,5 km sull'Italia, corse alle 00 e 12 UTC, 72 ore ora per ora.
 *
 * GRIB2 su proiezione conica conforme di Lambert (1399x1399), compressione "complex packing
 * con differenze spaziali" (template 5.3). L'app riproietta ogni campo su una griglia Web Mercator
 * più piccola e lo salva a 16 bit (valore x10) per occupare poca memoria.
 */
object Wrf {
    private const val BASE = "https://meteohub.agenziaitaliameteo.it/nwp/WRF"

    // Griglia di uscita (Web Mercator)
    const val NORTH = 49.5
    const val SOUTH = 30.5
    const val WEST = -1.0
    const val EAST = 25.5
    const val W = 900
    val H: Int by lazy {
        val dx = lonToX(EAST) - lonToX(WEST)
        val dy = latToY(SOUTH) - latToY(NORTH)
        (W * dy / dx).roundToInt()
    }
    const val NODATA: Short = Short.MIN_VALUE

    fun contains(lat: Double, lon: Double) = lat in 32.0..47.5 && lon in 2.0..22.0

    class Data(
        val run: String,
        val times: List<LocalDateTime>,
        val values: List<ShortArray>,
        val u: List<ShortArray>? = null,
        val v: List<ShortArray>? = null,
    )

    lateinit var cacheDir: File
    private val offsets = HashMap<String, List<Pair<Long, Long>>>()
    private val fileNames = HashMap<String, String>()

    // ------------------------------------------------------------------ rete (come ICON-2I)

    private fun runs(): List<String> {
        val html = Net.get("$BASE/")
        return Regex("href=\"(\\d{10})/\"").findAll(html).map { it.groupValues[1] }.toList().sorted().reversed()
    }

    private fun fileUrl(run: String, variable: String): String? {
        val key = "$run/$variable"
        fileNames[key]?.let { return it }
        // "t@850" = cartella t, file del livello 850 hPa
        val dir = variable.substringBefore('@')
        val lev = variable.substringAfter('@', "")
        val html = try { Net.get("$BASE/$run/$dir/") } catch (e: Exception) { return null }
        val names = Regex("href=\"([^\"]+\\.grib)\"").findAll(html).map { it.groupValues[1] }.toList()
        val name = (if (lev.isEmpty()) names.firstOrNull() else names.firstOrNull { it.endsWith("isobaricInhPa-$lev.grib") }) ?: return null
        return "$BASE/$run/$dir/$name".also { fileNames[key] = it }
    }

    private fun range(url: String, from: Long, len: Long): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "MLMeteo/1.0 (+https://github.com/marco90-cyber/MLmeteo)")
            c.setRequestProperty("Range", "bytes=$from-${from + len - 1}")
            if (c.responseCode != 206 && c.responseCode != 200) throw IOException("WRF: errore ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }.let { if (it.size > len) it.copyOf(len.toInt()) else it }
        } finally {
            c.disconnect()
        }
    }

    private fun messages(url: String): List<Pair<Long, Long>> {
        offsets[url]?.let { return it }
        val out = ArrayList<Pair<Long, Long>>()
        var off = 0L
        while (out.size < 80) {
            val head = try { range(url, off, 16) } catch (e: Exception) { break }
            if (head.size < 16 || head[0] != 'G'.code.toByte() || head[1] != 'R'.code.toByte()) break
            val len = ByteBuffer.wrap(head, 8, 8).order(ByteOrder.BIG_ENDIAN).long
            out += off to len
            off += len
        }
        if (out.isNotEmpty()) offsets[url] = out
        return out
    }

    private fun messageBytes(url: String, index: Int): ByteArray? {
        val (off, len) = messages(url).getOrNull(index) ?: return null
        val name = "w" + (url.hashCode().toLong() and 0xffffffffL).toString(16) + "_$index.grib"
        val f = File(cacheDir, "wrf/$name")
        if (f.exists() && f.length() == len) return f.readBytes()
        val b = range(url, off, len)
        f.parentFile?.mkdirs()
        try { f.writeBytes(b) } catch (e: Exception) { }
        return b
    }

    private fun cleanCache(keepUrls: Set<String>) {
        val keep = keepUrls.map { "w" + (it.hashCode().toLong() and 0xffffffffL).toString(16) }.toSet()
        File(cacheDir, "wrf").listFiles()?.forEach { if (it.name.substringBefore('_') !in keep) it.delete() }
    }

    // ------------------------------------------------------------------ GRIB2

    private class Grid(
        val nx: Int, val ny: Int, val la1: Double, val lo1: Double, val lad: Double, val lov: Double,
        val dx: Double, val dy: Double, val latin1: Double, val latin2: Double,
    )

    private fun smag(v: Long, bytes: Int): Long {
        val sign = 1L shl (8 * bytes - 1)
        return if (v and sign != 0L) -(v and (sign - 1)) else v
    }

    private class Decoded(val grid: Grid, val values: FloatArray)

    private fun decode(b: ByteArray): Decoded {
        fun u(pos: Int, n: Int): Long { var v = 0L; for (k in 0 until n) v = (v shl 8) or (b[pos + k].toLong() and 0xFF); return v }
        var pos = 16
        val sec = HashMap<Int, Pair<Int, Int>>()
        while (pos + 5 <= b.size) {
            if (b[pos] == '7'.code.toByte() && b[pos + 1] == '7'.code.toByte() && b[pos + 2] == '7'.code.toByte()) break
            val len = u(pos, 4).toInt()
            if (len <= 0) break
            sec[b[pos + 4].toInt()] = pos to len
            pos += len
        }
        val (p3, _) = sec[3] ?: throw IOException("GRIB senza griglia")
        fun o(k: Int) = p3 + k - 1
        if (u(o(13), 2).toInt() != 30) throw IOException("Griglia WRF non riconosciuta")
        val grid = Grid(
            nx = u(o(31), 4).toInt(), ny = u(o(35), 4).toInt(),
            la1 = smag(u(o(39), 4), 4) / 1e6, lo1 = u(o(43), 4) / 1e6,
            lad = smag(u(o(48), 4), 4) / 1e6, lov = u(o(52), 4) / 1e6,
            dx = u(o(56), 4) / 1e3, dy = u(o(60), 4) / 1e3,
            latin1 = smag(u(o(66), 4), 4) / 1e6, latin2 = smag(u(o(70), 4), 4) / 1e6,
        )
        val (p5, _) = sec[5] ?: throw IOException("GRIB senza dati")
        fun q(k: Int) = p5 + k - 1
        val n = u(p5 + 5, 4).toInt()
        val tmpl = u(p5 + 9, 2).toInt()
        val r = ByteBuffer.wrap(b, p5 + 11, 4).order(ByteOrder.BIG_ENDIAN).float.toDouble()
        val e = smag(u(p5 + 15, 2), 2).toInt()
        val d = smag(u(p5 + 17, 2), 2).toInt()
        val nb = b[p5 + 19].toInt() and 0xFF
        val se = 2.0.pow(e)
        val sd = 10.0.pow(-d)
        val out = FloatArray(grid.nx * grid.ny)
        val (p7, l7) = sec[7] ?: throw IOException("GRIB senza dati")
        val start = p7 + 5
        var bit = start.toLong() * 8
        fun rb(nbits: Int): Long {
            var v = 0L
            for (k in 0 until nbits) {
                val byte = b[(bit shr 3).toInt()].toInt()
                v = (v shl 1) or ((byte shr (7 - (bit and 7).toInt())) and 1).toLong()
                bit++
            }
            return v
        }
        fun align() { bit = (bit + 7) / 8 * 8 }
        when (tmpl) {
            0 -> {
                for (i in 0 until n.coerceAtMost(out.size)) out[i] = ((r + (if (nb > 0) rb(nb) else 0L) * se) * sd).toFloat()
            }
            3 -> {
                val miss = b[q(23)].toInt() and 0xFF
                val ng = u(q(32), 4).toInt()
                val wref = b[q(36)].toInt() and 0xFF
                val wbits = b[q(37)].toInt() and 0xFF
                val lref = u(q(38), 4).toInt()
                val linc = b[q(42)].toInt() and 0xFF
                val llast = u(q(43), 4).toInt()
                val lbits = b[q(47)].toInt() and 0xFF
                val order = b[q(48)].toInt() and 0xFF
                val nyb = b[q(49)].toInt() and 0xFF
                if (miss != 0) throw IOException("Valori mancanti non supportati")
                val ival1 = smag(rb(8 * nyb), nyb)
                val ival2 = if (order == 2) smag(rb(8 * nyb), nyb) else 0L
                val minsd = smag(rb(8 * nyb), nyb)
                val refs = LongArray(ng) { rb(nb) }; align()
                val widths = IntArray(ng) { rb(wbits).toInt() + wref }; align()
                val lens = IntArray(ng) { rb(lbits).toInt() * linc + lref }; align()
                lens[ng - 1] = llast
                val x = LongArray(n)
                var k = 0
                for (g in 0 until ng) {
                    val w = widths[g]
                    val ref = refs[g]
                    for (t in 0 until lens[g]) {
                        if (k >= n) break
                        x[k++] = ref + if (w > 0) rb(w) else 0L
                    }
                }
                if (n > 0) x[0] = ival1
                if (order == 2) {
                    if (n > 1) x[1] = ival2
                    for (i in 2 until n) x[i] = x[i] + minsd + 2 * x[i - 1] - x[i - 2]
                } else {
                    for (i in 1 until n) x[i] = x[i] + minsd + x[i - 1]
                }
                for (i in 0 until n.coerceAtMost(out.size)) out[i] = ((r + x[i] * se) * sd).toFloat()
            }
            else -> throw IOException("Compressione WRF non supportata ($tmpl)")
        }
        if (bit > (p7 + l7).toLong() * 8 + 8) throw IOException("Dati WRF incompleti")
        return Decoded(grid, out)
    }

    // ------------------------------------------------------------------ proiezione

    @Volatile private var mapping: IntArray? = null

    /** Indice nel campo WRF per ogni pixel della griglia di uscita (o -1). */
    private fun mappingFor(g: Grid): IntArray {
        mapping?.let { return it }
        val rr = 6371229.0
        val phi1 = Math.toRadians(g.latin1)
        val phi2 = Math.toRadians(g.latin2)
        val phi0 = Math.toRadians(g.lad)
        val l0 = Math.toRadians(g.lov)
        val n = if (abs(g.latin1 - g.latin2) > 1e-6)
            ln(cos(phi1) / cos(phi2)) / ln(tan(PI / 4 + phi2 / 2) / tan(PI / 4 + phi1 / 2)) else sin(phi1)
        val f = cos(phi1) * tan(PI / 4 + phi1 / 2).pow(n) / n
        fun rho(p: Double) = rr * f / tan(PI / 4 + p / 2).pow(n)
        val r0 = rho(phi0)
        fun fwd(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
            val r = rho(Math.toRadians(latDeg))
            var dl = Math.toRadians(lonDeg) - l0
            dl = ((dl + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI
            val th = n * dl
            return r * sin(th) to r0 - r * cos(th)
        }
        val (x0, y0) = fwd(g.la1, g.lo1)
        val h = H
        val out = IntArray(W * h)
        val my0 = latToY(NORTH); val my1 = latToY(SOUTH)
        val mx0 = lonToX(WEST); val mx1 = lonToX(EAST)
        for (j in 0 until h) {
            val lat = yToLat(my0 + (j + 0.5) / h * (my1 - my0))
            for (i in 0 until W) {
                val lon = xToLon(mx0 + (i + 0.5) / W * (mx1 - mx0))
                val (x, y) = fwd(lat, lon)
                val gi = ((x - x0) / g.dx).roundToInt()
                val gj = ((y - y0) / g.dy).roundToInt()
                out[j * W + i] = if (gi in 0 until g.nx && gj in 0 until g.ny) gj * g.nx + gi else -1
            }
        }
        mapping = out
        return out
    }

    private fun project(dec: Decoded, transform: (Float) -> Float): ShortArray {
        val map = mappingFor(dec.grid)
        val v = dec.values
        return ShortArray(map.size) { p ->
            val idx = map[p]
            if (idx < 0) NODATA else (transform(v[idx]) * 10).roundToInt().coerceIn(-32000, 32000).toShort()
        }
    }

    // ------------------------------------------------------------------ caricamento

    private fun runTime(run: String): LocalDateTime {
        val utc = LocalDateTime.parse(run, DateTimeFormatter.ofPattern("yyyyMMddHH"))
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    }

    val layers = listOf(MapLayer.RAIN, MapLayer.TEMP, MapLayer.WIND, MapLayer.GUST, MapLayer.CLOUD, MapLayer.VIS,
        MapLayer.PRESSURE, MapLayer.CAPE, MapLayer.T850)

    suspend fun load(layer: MapLayer, hours: Int, progress: (Int, Int) -> Unit): Data = coroutineScope {
        val vars = when (layer) {
            MapLayer.RAIN -> listOf("tp")
            MapLayer.TEMP -> listOf("2t")
            MapLayer.WIND -> listOf("10u", "10v")
            MapLayer.GUST -> listOf("gust")
            MapLayer.CLOUD -> listOf("tcc")
            MapLayer.VIS -> listOf("vis")
            MapLayer.PRESSURE -> listOf("prmsl")
            MapLayer.CAPE -> listOf("cape")
            MapLayer.T850 -> listOf("t@850")
            else -> throw IOException("Strato non disponibile con WRF")
        }
        val runList = withContext(Dispatchers.IO) { runs() }
        var lastError: Exception? = null
        for (run in runList.take(2)) {
            try {
                val urls = withContext(Dispatchers.IO) { vars.map { fileUrl(run, it) ?: throw IOException("File $it mancante") } }
                val available = withContext(Dispatchers.IO) { urls.minOf { messages(it).size } }
                val rt = runTime(run)
                val first = (java.time.Duration.between(rt, LocalDateTime.now()).toMinutes() / 60).toInt().coerceAtLeast(0)
                if (available < first + 3) throw IOException("Corsa $run incompleta")
                val last = (first + hours).coerceAtMost(available - 1)
                val steps = (first..last).toList()
                val accumulated = layer == MapLayer.RAIN
                val need = (if (accumulated) listOf((first - 1).coerceAtLeast(0)) + steps else steps).distinct()
                val total = need.size * urls.size
                var done = 0
                val raw = HashMap<String, ShortArray>()
                val jobs = urls.flatMapIndexed { vi, url -> need.map { s -> Triple(vi, url, s) } }
                for (chunk in jobs.chunked(3)) {
                    chunk.map { (vi, url, s) ->
                        async(Dispatchers.IO) {
                            val bytes = messageBytes(url, s) ?: throw IOException("Dato mancante")
                            val dec = withContext(Dispatchers.Default) { decode(bytes) }
                            val tf: (Float) -> Float = when (layer) {
                                MapLayer.TEMP, MapLayer.T850 -> { x -> x - 273.15f }
                                MapLayer.PRESSURE -> { x -> x / 100f }
                                MapLayer.WIND, MapLayer.GUST -> { x -> x * 3.6f }
                                MapLayer.VIS -> { x -> x / 1000f } // km
                                else -> { x -> x }
                            }
                            val pr = withContext(Dispatchers.Default) { project(dec, tf) }
                            synchronized(raw) { raw["$vi/$s"] = pr; done++ }
                            progress(done, total)
                        }
                    }.awaitAll()
                }
                withContext(Dispatchers.IO) { cleanCache(urls.toSet()) }
                val times = steps.map { rt.plusHours(it.toLong()) }
                fun f(vi: Int, s: Int) = raw["$vi/$s"]!!
                val data = withContext(Dispatchers.Default) {
                    when (layer) {
                        MapLayer.RAIN -> Data(run, times, steps.map { s ->
                            val a = f(0, s); val p = f(0, (s - 1).coerceAtLeast(0))
                            ShortArray(a.size) { i ->
                                if (a[i] == NODATA) NODATA else if (s == 0) 0.toShort() else (a[i] - p[i]).coerceAtLeast(0).toShort()
                            }
                        })
                        MapLayer.WIND -> Data(run, times, emptyList(), steps.map { f(0, it) }, steps.map { f(1, it) })
                        else -> Data(run, times, steps.map { f(0, it) })
                    }
                }
                return@coroutineScope data
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("WRF non disponibile")
    }

    // ------------------------------------------------------------------ disegno

    fun count(d: Data) = d.times.size

    fun render(d: Data, k: Int, layer: MapLayer): ImageBitmap? {
        val h = H
        val wind = layer == MapLayer.WIND
        val vals = if (wind) null else d.values.getOrNull(k) ?: return null
        val us = if (wind) d.u?.getOrNull(k) ?: return null else null
        val vs = if (wind) d.v?.getOrNull(k) ?: return null else null
        val out = FloatArray(W * h) { p ->
            if (wind) {
                val a = us!![p]; val b = vs!![p]
                if (a == NODATA) Float.NaN else (sqrt((a * a + b * b).toDouble()) / 10.0).toFloat()
            } else {
                val a = vals!![p]
                if (a == NODATA) Float.NaN else a / 10f
            }
        }
        return Paint.bitmap(out, W, h, layer)
    }

    /** Valore (mm/h, °C…) nel pixel della griglia più vicino a (lat, lon). */
    fun sampleAt(vals: ShortArray, lat: Double, lon: Double): Float {
        val i = ((lonToX(lon) - lonToX(WEST)) / (lonToX(EAST) - lonToX(WEST)) * W).toInt()
        val j = ((latToY(lat) - latToY(NORTH)) / (latToY(SOUTH) - latToY(NORTH)) * H).toInt()
        if (i !in 0 until W || j !in 0 until H) return Float.NaN
        val a = vals[j * W + i]
        return if (a == NODATA) Float.NaN else a / 10f
    }

    /** Vento (u, v in km/h) nel pixel della griglia di uscita più vicino a (lat, lon). */
    fun windAt(d: Data, k: Int, lat: Double, lon: Double): Pair<Float, Float>? {
        val us = d.u?.getOrNull(k) ?: return null
        val vs = d.v?.getOrNull(k) ?: return null
        val i = ((lonToX(lon) - lonToX(WEST)) / (lonToX(EAST) - lonToX(WEST)) * W).toInt()
        val j = ((latToY(lat) - latToY(NORTH)) / (latToY(SOUTH) - latToY(NORTH)) * H).toInt()
        if (i !in 0 until W || j !in 0 until H) return null
        val a = us[j * W + i]; val b = vs[j * W + i]
        if (a == NODATA) return null
        return a / 10f to b / 10f
    }
}
