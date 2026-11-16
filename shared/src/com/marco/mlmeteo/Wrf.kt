package com.marco.mlmeteo

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
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
    private val hub = Meteohub(BASE, "WRF", "wrf", "w", 80) { cacheDir }

    // ------------------------------------------------------------------ GRIB2

    private class Grid(
        val nx: Int, val ny: Int, val la1: Double, val lo1: Double, val lad: Double, val lov: Double,
        val dx: Double, val dy: Double, val latin1: Double, val latin2: Double,
    )

    private class Decoded(val grid: Grid, val values: FloatArray)

    /** Griglia di Lambert del WRF; i valori li spacchetta il lettore GRIB comune. */
    private fun decode(b: ByteArray): Decoded {
        val sec = Grib.sections(b)
        val (p3, _) = sec[3] ?: throw IOException("GRIB senza griglia")
        fun u(pos: Int, n: Int) = Grib.uint(b, pos, n)
        fun o(k: Int) = p3 + k - 1
        if (u(o(13), 2).toInt() != 30) throw IOException("Griglia WRF non riconosciuta")
        val grid = Grid(
            nx = u(o(31), 4).toInt(), ny = u(o(35), 4).toInt(),
            la1 = Grib.smag(u(o(39), 4), 4) / 1e6, lo1 = u(o(43), 4) / 1e6,
            lad = Grib.smag(u(o(48), 4), 4) / 1e6, lov = u(o(52), 4) / 1e6,
            dx = u(o(56), 4) / 1e3, dy = u(o(60), 4) / 1e3,
            latin1 = Grib.smag(u(o(66), 4), 4) / 1e6, latin2 = Grib.smag(u(o(70), 4), 4) / 1e6,
        )
        val packed = Grib.unpack(b, sec)
        val (p7, l7) = sec[7] ?: throw IOException("GRIB senza dati")
        if (packed.endBit > (p7 + l7).toLong() * 8 + 8) throw IOException("Dati WRF incompleti")
        val out = FloatArray(grid.nx * grid.ny)
        for (i in 0 until packed.size.coerceAtMost(out.size)) out[i] = packed.value(i)
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
        val runList = withContext(Dispatchers.IO) { hub.runs() }
        var lastError: Exception? = null
        for (run in runList.take(2)) {
            try {
                val urls = withContext(Dispatchers.IO) { vars.map { hub.fileUrl(run, it) ?: throw IOException("File $it mancante") } }
                val available = withContext(Dispatchers.IO) { urls.minOf { hub.messages(it).size } }
                val rt = Meteohub.runTime(run)
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
                            val bytes = hub.messageBytes(url, s) ?: throw IOException("Dato mancante")
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
                withContext(Dispatchers.IO) { hub.cleanCache(urls.toSet()) }
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
