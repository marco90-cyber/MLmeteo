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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * GFS (NOAA) a 0,25° scaricato direttamente dai server NOMADS, solo l'area dell'Europa
 * e del Mediterraneo. La griglia completa viene interpolata in modo morbido sulla mappa,
 * come le cartine "HD" dei siti meteo. Ora per ora fino a 24 ore, poi ogni 3 ore.
 */
object Gfs {
    const val SOUTH = 30.0
    const val NORTH = 55.0
    const val WEST = -15.0
    const val EAST = 40.0
    const val STEP = 0.25
    val NI = ((EAST - WEST) / STEP).roundToInt() + 1
    val NJ = ((NORTH - SOUTH) / STEP).roundToInt() + 1

    lateinit var cacheDir: File

    val layers = listOf(MapLayer.RAIN, MapLayer.TEMP, MapLayer.WIND, MapLayer.GUST, MapLayer.CLOUD,
        MapLayer.CLOUD_LOW, MapLayer.CLOUD_MID, MapLayer.CLOUD_HIGH, MapLayer.VIS,
        MapLayer.PRESSURE, MapLayer.CAPE, MapLayer.T850, MapLayer.FREEZING, MapLayer.SNOW_DEPTH)

    class Data(val run: String, val times: List<LocalDateTime>, val values: List<FloatArray>, val u: List<FloatArray>? = null, val v: List<FloatArray>? = null)

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "MLMeteo/1.0 (+https://github.com/marco90-cyber/MLmeteo)")
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun url(run: ZonedDateTime, step: Int, variable: String, level: String): String {
        val d = run.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val h = run.format(DateTimeFormatter.ofPattern("HH"))
        return "https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25_1hr.pl?dir=%2Fgfs.$d%2F$h%2Fatmos" +
            "&file=gfs.t${h}z.pgrb2.0p25.f${String.format("%03d", step)}&var_$variable=on&lev_$level=on" +
            "&subregion=&toplat=$NORTH&leftlon=$WEST&rightlon=$EAST&bottomlat=$SOUTH"
    }

    /** Primo messaggio GRIB del file, decodificato. */
    private fun field(run: ZonedDateTime, step: Int, variable: String, level: String): FloatArray? {
        val u = url(run, step, variable, level)
        val f = File(cacheDir, "gfs/" + (u.hashCode().toLong() and 0xffffffffL).toString(16) + ".grib")
        val bytes = if (f.exists() && f.length() > 100) f.readBytes() else {
            val b = get(u) ?: return null
            if (b.size < 16 || b[0] != 'G'.code.toByte()) return null
            f.parentFile?.mkdirs()
            try { f.writeBytes(b) } catch (e: Exception) { }
            b
        }
        val len = ByteBuffer.wrap(bytes, 8, 8).order(ByteOrder.BIG_ENDIAN).long.toInt().coerceAtMost(bytes.size)
        val values = try { Grib.decode(bytes.copyOf(len)).values } catch (e: Exception) { return null }
        return if (values.size == NI * NJ) values else null
    }

    private fun cleanCache(keep: Set<String>) {
        File(cacheDir, "gfs").listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    suspend fun load(layer: MapLayer, hours: Int, progress: (Int, Int) -> Unit): Data = coroutineScope {
        val vars: List<Pair<String, String>> = when (layer) {
            MapLayer.RAIN -> listOf("PRATE" to "surface")
            MapLayer.TEMP -> listOf("TMP" to "2_m_above_ground")
            MapLayer.WIND -> listOf("UGRD" to "10_m_above_ground", "VGRD" to "10_m_above_ground")
            MapLayer.GUST -> listOf("GUST" to "surface")
            MapLayer.CLOUD -> listOf("TCDC" to "entire_atmosphere")
            MapLayer.VIS -> listOf("VIS" to "surface")
            MapLayer.CLOUD_LOW -> listOf("LCDC" to "low_cloud_layer")
            MapLayer.CLOUD_MID -> listOf("MCDC" to "middle_cloud_layer")
            MapLayer.CLOUD_HIGH -> listOf("HCDC" to "high_cloud_layer")
            MapLayer.PRESSURE -> listOf("PRMSL" to "mean_sea_level")
            MapLayer.CAPE -> listOf("CAPE" to "surface")
            MapLayer.T850 -> listOf("TMP" to "850_mb")
            MapLayer.FREEZING -> listOf("HGT" to "0C_isotherm")
            MapLayer.SNOW_DEPTH -> listOf("SNOD" to "surface")
            else -> throw IOException("Strato non disponibile con GFS")
        }
        val nowUtc = ZonedDateTime.now(ZoneOffset.UTC)
        // corse ogni 6 ore, disponibili circa 4 ore dopo
        var candidate = nowUtc.minusHours(3).withMinute(0).withSecond(0).withNano(0)
        candidate = candidate.withHour(candidate.hour / 6 * 6)
        var lastError: Exception? = null
        repeat(3) {
            val run = candidate
            candidate = candidate.minusHours(6)
            try {
                val first = (java.time.Duration.between(run, nowUtc).toMinutes() / 60).toInt().coerceAtLeast(0)
                val steps = ArrayList<Int>()
                var s = first
                while (s <= (first + hours).coerceAtMost(120)) {
                    steps += s
                    s += if (s < first + 24) 1 else 3
                }
                // controlla che la corsa ci sia
                withContext(Dispatchers.IO) { field(run, steps.first(), vars[0].first, vars[0].second) }
                    ?: throw IOException("Corsa non ancora disponibile")
                val total = steps.size * vars.size
                var done = 0
                val out = HashMap<String, FloatArray>()
                val jobs = vars.indices.flatMap { vi -> steps.map { vi to it } }
                for (chunk in jobs.chunked(4)) {
                    chunk.map { (vi, st) ->
                        async(Dispatchers.IO) {
                            val f = field(run, st, vars[vi].first, vars[vi].second)
                            synchronized(out) { if (f != null) out["$vi/$st"] = f; done++ }
                            progress(done, total)
                        }
                    }.awaitAll()
                }
                val ok = steps.filter { st -> vars.indices.all { out.containsKey("$it/$st") } }
                if (ok.isEmpty()) throw IOException("GFS non disponibile")
                withContext(Dispatchers.IO) {
                    cleanCache(vars.flatMap { (v, l) -> ok.map { st -> (url(run, st, v, l).hashCode().toLong() and 0xffffffffL).toString(16) + ".grib" } }.toSet())
                }
                val times = ok.map { run.plusHours(it.toLong()).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
                val runId = run.format(DateTimeFormatter.ofPattern("yyyyMMddHH"))
                val data = when (layer) {
                    MapLayer.RAIN -> Data(runId, times, ok.map { st -> out["0/$st"]!!.let { a -> FloatArray(a.size) { a[it] * 3600f } } })
                    MapLayer.PRESSURE -> Data(runId, times, ok.map { st -> out["0/$st"]!!.let { a -> FloatArray(a.size) { a[it] / 100f } } })
                    MapLayer.SNOW_DEPTH -> Data(runId, times, ok.map { st -> out["0/$st"]!!.let { a -> FloatArray(a.size) { a[it] * 100f } } })
                    MapLayer.TEMP, MapLayer.T850 -> Data(runId, times, ok.map { st -> out["0/$st"]!!.let { a -> FloatArray(a.size) { a[it] - 273.15f } } })
                    MapLayer.WIND -> Data(runId, times, emptyList(),
                        ok.map { st -> out["0/$st"]!!.let { a -> FloatArray(a.size) { a[it] * 3.6f } } },
                        ok.map { st -> out["1/$st"]!!.let { a -> FloatArray(a.size) { a[it] * 3.6f } } })
                    MapLayer.GUST -> Data(runId, times, ok.map { st -> out["0/$st"]!!.let { a -> FloatArray(a.size) { a[it] * 3.6f } } })
                    MapLayer.VIS -> Data(runId, times, ok.map { st -> out["0/$st"]!!.let { a -> FloatArray(a.size) { a[it] / 1000f } } })
                    else -> Data(runId, times, ok.map { st -> out["0/$st"]!! })
                }
                return@coroutineScope data
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("GFS non disponibile")
    }

    // ------------------------------------------------------------------ disegno (interpolazione morbida)

    fun sample(values: FloatArray, lat: Double, lon: Double): Float {
        val fy = (lat - SOUTH) / STEP
        val fx = (lon - WEST) / STEP
        if (fy < 0 || fx < 0 || fy > NJ - 1 || fx > NI - 1) return Float.NaN
        val j = fy.toInt().coerceAtMost(NJ - 2); val i = fx.toInt().coerceAtMost(NI - 2)
        val ty = (fy - j).toFloat(); val tx = (fx - i).toFloat()
        val a = values[j * NI + i]; val b = values[j * NI + i + 1]
        val c = values[(j + 1) * NI + i]; val d = values[(j + 1) * NI + i + 1]
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    private const val OUT_W = 1100
    private val OUT_H: Int by lazy {
        val dx = lonToX(EAST) - lonToX(WEST)
        val dy = latToY(SOUTH) - latToY(NORTH)
        (OUT_W * dy / dx).roundToInt()
    }

    fun render(d: Data, k: Int, layer: MapLayer): ImageBitmap? {
        val wind = layer == MapLayer.WIND
        val vals = if (wind) null else d.values.getOrNull(k) ?: return null
        val us = if (wind) d.u?.getOrNull(k) ?: return null else null
        val vs = if (wind) d.v?.getOrNull(k) ?: return null else null
        val h = OUT_H
        val vv = FloatArray(OUT_W * h)
        val y0 = latToY(NORTH); val y1 = latToY(SOUTH)
        val lons = DoubleArray(OUT_W) { i -> WEST + (EAST - WEST) * (i + 0.5) / OUT_W }
        for (j in 0 until h) {
            val lat = yToLat(y0 + (j + 0.5) / h * (y1 - y0))
            for (i in 0 until OUT_W) {
                vv[j * OUT_W + i] = if (wind) {
                    val a = sample(us!!, lat, lons[i]); val b = sample(vs!!, lat, lons[i])
                    sqrt(a * a + b * b)
                } else sample(vals!!, lat, lons[i])
            }
        }
        val px = Paint.pixels(vv, OUT_W, h, layer)
        return imageFromPixels(px, OUT_W, h)
    }
}
