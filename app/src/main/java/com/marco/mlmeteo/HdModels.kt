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
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ------------------------------------------------------------------ disegno comune

/** Trasforma i valori (uno per pixel) in colori, con le linee di livello per isobare e 850 hPa. */
object Paint {
    fun pixels(vals: FloatArray, w: Int, h: Int, layer: MapLayer): IntArray {
        val stops = layer.stops
        val lo = stops.first().first
        val hi = stops.last().first
        val lut = IntArray(512) { i -> colorAt(lo + (hi - lo) * i / 511.0, stops).toArgb() }
        val px = IntArray(w * h)
        for (p in px.indices) {
            val v = vals[p]
            px[p] = if (v.isNaN() || v < lo) 0 else lut[(((v - lo) / (hi - lo)) * 511).toInt().coerceIn(0, 511)]
        }
        val step = layer.contour
        if (step > 0) {
            val line = 0xEEFFFFFF.toInt()
            val strong = 0xFFFFFFFF.toInt()
            fun band(v: Float) = floor(v / step).toInt()
            for (j in 0 until h - 1) for (i in 0 until w - 1) {
                val p = j * w + i
                val v = vals[p]
                if (v.isNaN()) continue
                val r = vals[p + 1]; val d = vals[p + w]
                val b = band(v)
                if ((!r.isNaN() && band(r) != b) || (!d.isNaN() && band(d) != b)) {
                    val lvl = maxOf(b, if (r.isNaN()) b else band(r), if (d.isNaN()) b else band(d)) * step
                    px[p] = if (layer == MapLayer.T850 && Math.abs(lvl) < 0.01) 0xFF1565C0.toInt()
                    else if (layer == MapLayer.PRESSURE && (lvl.roundToInt() % 20 == 0)) strong else line
                }
            }
        }
        return px
    }

    fun bitmap(vals: FloatArray, w: Int, h: Int, layer: MapLayer): ImageBitmap =
        Bitmap.createBitmap(pixels(vals, w, h, layer), w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Griglia regolare lat/lon (righe da sud a nord). */
class RegGrid(val ni: Int, val nj: Int, val south: Double, val west: Double, val dlat: Double, val dlon: Double) {
    val north get() = south + dlat * (nj - 1)
    val east get() = west + dlon * (ni - 1)

    fun sample(v: FloatArray, lat: Double, lon: Double): Float {
        val fy = (lat - south) / dlat
        val fx = (lon - west) / dlon
        if (fy < 0 || fx < 0 || fy > nj - 1 || fx > ni - 1) return Float.NaN
        val j = fy.toInt().coerceAtMost(nj - 2); val i = fx.toInt().coerceAtMost(ni - 2)
        val ty = (fy - j).toFloat(); val tx = (fx - i).toFloat()
        val a = v[j * ni + i]; val b = v[j * ni + i + 1]
        val c = v[(j + 1) * ni + i]; val d = v[(j + 1) * ni + i + 1]
        if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) {
            return listOf(a, b, c, d).filter { !it.isNaN() }.let { if (it.isEmpty()) Float.NaN else it.average().toFloat() }
        }
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    /** Disegno morbido (bilineare) in proiezione Web Mercator. */
    fun render(layer: MapLayer, outW: Int = 1100, value: (Double, Double) -> Float): ImageBitmap {
        val dx = lonToX(east) - lonToX(west)
        val dy = latToY(south) - latToY(north)
        val h = (outW * dy / dx).roundToInt().coerceAtLeast(1)
        val y0 = latToY(north); val y1 = latToY(south)
        val lons = DoubleArray(outW) { i -> west + (east - west) * (i + 0.5) / outW }
        val vals = FloatArray(outW * h)
        for (j in 0 until h) {
            val lat = yToLat(y0 + (j + 0.5) / h * (y1 - y0))
            for (i in 0 until outW) vals[j * outW + i] = value(lat, lons[i])
        }
        return Paint.bitmap(vals, outW, h, layer)
    }
}

/** Dati di un modello su griglia regolare, pronti da disegnare. */
class GridData(
    val run: String,
    val times: List<LocalDateTime>,
    val grid: RegGrid,
    val values: List<FloatArray>,
    val u: List<FloatArray>? = null,
    val v: List<FloatArray>? = null,
) {
    fun render(k: Int, layer: MapLayer): ImageBitmap? {
        if (layer == MapLayer.WIND) {
            val us = u?.getOrNull(k) ?: return null
            val vs = v?.getOrNull(k) ?: return null
            return grid.render(layer) { lat, lon ->
                val a = grid.sample(us, lat, lon); val b = grid.sample(vs, lat, lon)
                sqrt(a * a + b * b)
            }
        }
        val vals = values.getOrNull(k) ?: return null
        return grid.render(layer) { lat, lon -> grid.sample(vals, lat, lon) }
    }

    fun windAt(k: Int, lat: Double, lon: Double): Pair<Float, Float>? {
        val us = u?.getOrNull(k) ?: return null
        val vs = v?.getOrNull(k) ?: return null
        val a = grid.sample(us, lat, lon); val b = grid.sample(vs, lat, lon)
        return if (a.isNaN() || b.isNaN()) null else a to b
    }

    fun toHiRes(layer: MapLayer) = HiRes(run, times, grid.north, grid.south, grid.west, grid.east,
        render = { k, l -> render(k, l) },
        wind = if (layer == MapLayer.WIND) { k, lat, lon -> windAt(k, lat, lon) } else null)
}

/** Area ritagliata dai modelli europei/globali (Italia e mari vicini). */
private const val CROP_S = 34.0
private const val CROP_N = 50.0
private const val CROP_W = 2.0
private const val CROP_E = 24.0

private fun httpGet(url: String, range: Pair<Long, Long>? = null): ByteArray? {
    val c = URL(url).openConnection() as HttpURLConnection
    return try {
        c.connectTimeout = 20000
        c.readTimeout = 40000
        c.setRequestProperty("User-Agent", "MLMeteo/1.0 (+https://github.com/marco90-cyber/MLmeteo)")
        if (range != null) c.setRequestProperty("Range", "bytes=${range.first}-${range.first + range.second - 1}")
        if (c.responseCode != 200 && c.responseCode != 206) null else c.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
        null
    } finally {
        c.disconnect()
    }
}

private fun localTime(utc: ZonedDateTime): LocalDateTime = utc.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

/** Valori trasformati e (per le grandezze cumulate) trasformati in intensità orarie. */
private class Spec(
    val vars: List<String>,
    val accumulated: Boolean = false,
    val tf: (Float) -> Float = { it },
)

// ------------------------------------------------------------------ DWD: ICON-EU e ICON-D2

/**
 * Modelli del servizio meteo tedesco (opendata.dwd.de), su griglia regolare.
 * ICON-EU: 6,5 km su tutta Europa, ora per ora fino a 78 ore (poi ogni 3).
 * ICON-D2: 2,2 km su Germania, Alpi e Nord Italia (fino a circa 43° N), 48 ore.
 * File GRIB2 compressi bzip2; ICON-EU usa la compressione CCSDS.
 */
class Dwd(private val model: String) {
    private val eu = model == "icon-eu"
    lateinit var cacheDir: File

    val layers: List<MapLayer> = buildList {
        addAll(listOf(MapLayer.RAIN, MapLayer.TEMP, MapLayer.WIND, MapLayer.GUST, MapLayer.CLOUD,
            MapLayer.CLOUD_LOW, MapLayer.CLOUD_MID, MapLayer.CLOUD_HIGH, MapLayer.SNOW, MapLayer.PRESSURE, MapLayer.CAPE))
        add(MapLayer.T850)
        if (!eu) { add(MapLayer.DBZ); add(MapLayer.LPI) }
        addAll(listOf(MapLayer.FREEZING, MapLayer.SNOWLINE, MapLayer.SNOW_DEPTH))
    }

    fun contains(lat: Double, lon: Double) =
        if (eu) lat in CROP_S..CROP_N && lon in CROP_W..CROP_E else lat in 43.4..58.0 && lon in -3.7..20.1

    private fun spec(layer: MapLayer): Spec = when (layer) {
        MapLayer.RAIN -> Spec(listOf("tot_prec"), accumulated = true)
        MapLayer.SNOW -> Spec(listOf("snow_gsp", "snow_con"), accumulated = true)
        MapLayer.TEMP -> Spec(listOf("t_2m")) { it - 273.15f }
        MapLayer.WIND -> Spec(listOf("u_10m", "v_10m")) { it * 3.6f }
        MapLayer.GUST -> Spec(listOf("vmax_10m")) { it * 3.6f }
        MapLayer.CLOUD -> Spec(listOf("clct"))
        MapLayer.CLOUD_LOW -> Spec(listOf("clcl"))
        MapLayer.CLOUD_MID -> Spec(listOf("clcm"))
        MapLayer.CLOUD_HIGH -> Spec(listOf("clch"))
        MapLayer.PRESSURE -> Spec(listOf("pmsl")) { it / 100f }
        MapLayer.CAPE -> Spec(listOf("cape_ml"))
        MapLayer.T850 -> Spec(listOf("t@850")) { it - 273.15f }
        MapLayer.FREEZING -> Spec(listOf("hzerocl")) { if (it < 0) Float.NaN else it }
        MapLayer.SNOWLINE -> Spec(listOf("snowlmt")) { if (it < 0) Float.NaN else it }
        MapLayer.SNOW_DEPTH -> Spec(listOf("h_snow")) { it * 100f }
        MapLayer.DBZ -> Spec(listOf("dbz_cmax"))
        MapLayer.LPI -> Spec(listOf("lpi"))
        else -> throw IOException("Strato non disponibile con questo modello")
    }

    private fun url(run: ZonedDateTime, step: Int, v: String): String {
        val r = run.format(DateTimeFormatter.ofPattern("yyyyMMddHH"))
        val hh = run.format(DateTimeFormatter.ofPattern("HH"))
        val s = String.format("%03d", step)
        return if (v.contains('@')) {
            val (name, lev) = v.split('@')
            if (eu) "https://opendata.dwd.de/weather/nwp/icon-eu/grib/$hh/$name/icon-eu_europe_regular-lat-lon_pressure-level_${r}_${s}_${lev}_${name.uppercase()}.grib2.bz2"
            else "https://opendata.dwd.de/weather/nwp/icon-d2/grib/$hh/$name/icon-d2_germany_regular-lat-lon_pressure-level_${r}_${s}_${lev}_$name.grib2.bz2"
        } else if (eu) "https://opendata.dwd.de/weather/nwp/icon-eu/grib/$hh/$v/icon-eu_europe_regular-lat-lon_single-level_${r}_${s}_${v.uppercase()}.grib2.bz2"
        else "https://opendata.dwd.de/weather/nwp/icon-d2/grib/$hh/$v/icon-d2_germany_regular-lat-lon_single-level_${r}_${s}_2d_$v.grib2.bz2"
    }

    private fun key(u: String) = (u.hashCode().toLong() and 0xffffffffL).toString(16) + ".grib"

    /** Scarica (o legge dalla cache) e decodifica il campo, ritagliato sull'area dell'Italia. */
    private fun field(run: ZonedDateTime, step: Int, v: String): Grib.Field? {
        val u = url(run, step, v)
        val f = File(cacheDir, "$model/" + key(u))
        val bytes = if (f.exists() && f.length() > 100) f.readBytes() else {
            val bz = httpGet(u) ?: return null
            val raw = try { BZip2CompressorInputStream(bz.inputStream()).use { it.readBytes() } } catch (e: Exception) { return null }
            if (raw.size < 16 || raw[0] != 'G'.code.toByte()) return null
            f.parentFile?.mkdirs()
            try { f.writeBytes(raw) } catch (e: Exception) { }
            raw
        }
        val full = try { Grib.decode(Grib.first(bytes)) } catch (e: Exception) { return null }
        return if (eu) Grib.crop(full, CROP_S, CROP_N, CROP_W, CROP_E)
        else Grib.crop(full, full.south, minOf(full.south + full.dlat * (full.nj - 1), 50.0), maxOf(full.west, 2.0), full.west + full.dlon * (full.ni - 1))
    }

    suspend fun load(layer: MapLayer, hours: Int, progress: (Int, Int) -> Unit): GridData = coroutineScope {
        val sp = spec(layer)
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        // corse ogni 3 ore, pronte circa 3 ore dopo (ICON-D2 dopo 2 ore)
        var cand = now.minusHours(if (eu) 3 else 2).withMinute(0).withSecond(0).withNano(0)
        cand = cand.withHour(cand.hour / 3 * 3)
        val maxStep = if (eu) 120 else 48
        var lastError: Exception? = null
        repeat(4) {
            val run = cand
            cand = cand.minusHours(3)
            try {
                val first = (Duration.between(run, now).toMinutes() / 60).toInt().coerceAtLeast(0)
                val steps = ArrayList<Int>()
                var s = first
                while (s <= (first + hours).coerceAtMost(maxStep)) {
                    steps += s
                    // ora per ora nelle prime 24 ore, poi ogni 3 ore (meno dati da scaricare)
                    s = if (s < first + 24 && s < 78) s + 1 else (s / 3 + 1) * 3
                }
                withContext(Dispatchers.IO) { field(run, steps.first(), sp.vars[0]) } ?: throw IOException("Corsa non ancora disponibile")
                val need = (if (sp.accumulated) listOf((first - 1).coerceAtLeast(0)) + steps else steps).distinct()
                val total = need.size * sp.vars.size
                var done = 0
                val out = HashMap<String, Grib.Field>()
                val jobs = sp.vars.indices.flatMap { vi -> need.map { vi to it } }
                for (chunk in jobs.chunked(4)) {
                    chunk.map { (vi, st) ->
                        async(Dispatchers.IO) {
                            val f = field(run, st, sp.vars[vi])
                            synchronized(out) { if (f != null) out["$vi/$st"] = f; done++ }
                            progress(done, total)
                        }
                    }.awaitAll()
                }
                val ok = steps.filter { st ->
                    sp.vars.indices.all { out.containsKey("$it/$st") } &&
                        (!sp.accumulated || st == 0 || sp.vars.indices.all { vi -> out.containsKey("$vi/${need.lastOrNull { p -> p < st } ?: -1}") })
                }
                if (ok.isEmpty()) throw IOException("Dati non disponibili")
                withContext(Dispatchers.IO) {
                    val keep = sp.vars.flatMap { v -> need.map { st -> key(url(run, st, v)) } }.toSet()
                    File(cacheDir, model).listFiles()?.forEach { if (it.name !in keep) it.delete() }
                }
                val g0 = out["0/${ok.first()}"]!!
                val grid = RegGrid(g0.ni, g0.nj, g0.south, g0.west, g0.dlat, g0.dlon)
                val data = withContext(Dispatchers.Default) {
                    fun f(vi: Int, st: Int) = out["$vi/$st"]!!.values
                    val runId = run.format(DateTimeFormatter.ofPattern("yyyyMMddHH"))
                    val times = ok.map { localTime(run.plusHours(it.toLong())) }
                    when {
                        sp.accumulated -> GridData(runId, times, grid, ok.map { st ->
                            val prev = need.lastOrNull { it < st }
                            val res = FloatArray(grid.ni * grid.nj)
                            if (st > 0 && prev != null) {
                                val gap = (st - prev).toFloat()
                                for (vi in sp.vars.indices) {
                                    val a = f(vi, st); val p = f(vi, prev)
                                    for (i in res.indices) res[i] += ((a[i] - p[i]) / gap).coerceAtLeast(0f)
                                }
                            }
                            res
                        })
                        layer == MapLayer.WIND -> GridData(runId, times, grid, emptyList(),
                            ok.map { st -> f(0, st).let { a -> FloatArray(a.size) { sp.tf(a[it]) } } },
                            ok.map { st -> f(1, st).let { a -> FloatArray(a.size) { sp.tf(a[it]) } } })
                        else -> GridData(runId, times, grid, ok.map { st -> f(0, st).let { a -> FloatArray(a.size) { sp.tf(a[it]) } } })
                    }
                }
                return@coroutineScope data
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Modello non disponibile")
    }
}

object IconEu { val m = Dwd("icon-eu") }
object IconD2 { val m = Dwd("icon-d2") }

// ------------------------------------------------------------------ ECMWF (dati aperti, 0,25°)

/**
 * ECMWF IFS, dati aperti a 0,25° (data.ecmwf.int). Corse delle 00 e 12 UTC, ogni 3 ore
 * fino a 144 ore. Per ogni passo c'è un indice JSON con la posizione di ogni campo nel file:
 * l'app scarica solo i campi che servono (richieste HTTP Range).
 */
object Ecmwf {
    lateinit var cacheDir: File

    val layers = listOf(MapLayer.RAIN, MapLayer.TEMP, MapLayer.WIND, MapLayer.GUST, MapLayer.CLOUD,
        MapLayer.CLOUD_LOW, MapLayer.CLOUD_MID, MapLayer.CLOUD_HIGH, MapLayer.SNOW, MapLayer.PRESSURE, MapLayer.CAPE, MapLayer.T850)

    fun contains(lat: Double, lon: Double) = lat in CROP_S..CROP_N && lon in CROP_W..CROP_E

    private fun spec(layer: MapLayer): Spec = when (layer) {
        MapLayer.RAIN -> Spec(listOf("tp"), accumulated = true) { it * 1000f }
        MapLayer.SNOW -> Spec(listOf("sf"), accumulated = true) { it * 1000f }
        MapLayer.TEMP -> Spec(listOf("2t")) { it - 273.15f }
        MapLayer.WIND -> Spec(listOf("10u", "10v")) { it * 3.6f }
        MapLayer.GUST -> Spec(listOf("10fg")) { it * 3.6f }
        MapLayer.CLOUD -> Spec(listOf("tcc")) { it * 100f }
        MapLayer.CLOUD_LOW -> Spec(listOf("lcc")) { it * 100f }
        MapLayer.CLOUD_MID -> Spec(listOf("mcc")) { it * 100f }
        MapLayer.CLOUD_HIGH -> Spec(listOf("hcc")) { it * 100f }
        MapLayer.PRESSURE -> Spec(listOf("msl")) { it / 100f }
        MapLayer.CAPE -> Spec(listOf("mucape"))
        MapLayer.T850 -> Spec(listOf("t@850")) { it - 273.15f }
        else -> throw IOException("Strato non disponibile con ECMWF")
    }

    private fun base(run: ZonedDateTime, step: Int): String {
        val d = run.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val h = run.format(DateTimeFormatter.ofPattern("HH"))
        return "https://data.ecmwf.int/forecasts/$d/${h}z/ifs/0p25/oper/$d${h}0000-${step}h-oper-fc"
    }

    private val indexCache = HashMap<String, List<String>>()

    private fun index(run: ZonedDateTime, step: Int): List<String>? {
        val u = base(run, step) + ".index"
        synchronized(indexCache) { indexCache[u]?.let { return it } }
        val txt = httpGet(u)?.toString(Charsets.UTF_8) ?: return null
        val lines = txt.lines().filter { it.isNotBlank() }
        synchronized(indexCache) { indexCache[u] = lines }
        return lines
    }

    private fun jsonStr(line: String, k: String) = Regex("\"$k\": *\"([^\"]*)\"").find(line)?.groupValues?.get(1)
    private fun jsonNum(line: String, k: String) = Regex("\"$k\": *(\\d+)").find(line)?.groupValues?.get(1)?.toLong()

    private fun field(run: ZonedDateTime, step: Int, v: String): Grib.Field? {
        val (param, lev) = if (v.contains('@')) v.split('@').let { it[0] to it[1] } else v to null
        val name = (base(run, step) + v).hashCode().toLong().and(0xffffffffL).toString(16) + ".grib"
        val f = File(cacheDir, "ecmwf/$name")
        val bytes = if (f.exists() && f.length() > 100) f.readBytes() else {
            val idx = index(run, step) ?: return null
            val line = idx.firstOrNull { l ->
                jsonStr(l, "param") == param && (if (lev == null) jsonStr(l, "levtype") == "sfc" else jsonStr(l, "levelist") == lev)
            } ?: return null
            val off = jsonNum(line, "_offset") ?: return null
            val len = jsonNum(line, "_length") ?: return null
            val b = httpGet(base(run, step) + ".grib2", off to len) ?: return null
            if (b.size < 16 || b[0] != 'G'.code.toByte()) return null
            f.parentFile?.mkdirs()
            try { f.writeBytes(b) } catch (e: Exception) { }
            b
        }
        val full = try { Grib.decode(Grib.first(bytes)) } catch (e: Exception) { return null }
        return Grib.crop(full, CROP_S, CROP_N, CROP_W, CROP_E)
    }

    suspend fun load(layer: MapLayer, hours: Int, progress: (Int, Int) -> Unit): GridData = coroutineScope {
        val sp = spec(layer)
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        // corse 00 e 12 UTC, complete circa 8 ore dopo
        var cand = now.minusHours(8).withMinute(0).withSecond(0).withNano(0)
        cand = cand.withHour(cand.hour / 12 * 12)
        var lastError: Exception? = null
        repeat(3) {
            val run = cand
            cand = cand.minusHours(12)
            try {
                val elapsed = (Duration.between(run, now).toMinutes() / 60).toInt().coerceAtLeast(0)
                val first = elapsed / 3 * 3
                val steps = ArrayList<Int>()
                var s = first
                while (s <= (first + hours).coerceAtMost(144)) { steps += s; s += 3 }
                if (steps.size < 2) steps += first + 3
                withContext(Dispatchers.IO) { index(run, steps.last()) } ?: throw IOException("Corsa non ancora disponibile")
                val need = (if (sp.accumulated && first > 0) listOf(first - 3) + steps else steps).distinct()
                val total = need.size * sp.vars.size
                var done = 0
                val out = HashMap<String, Grib.Field>()
                val jobs = sp.vars.indices.flatMap { vi -> need.map { vi to it } }
                for (chunk in jobs.chunked(4)) {
                    chunk.map { (vi, st) ->
                        async(Dispatchers.IO) {
                            val f = field(run, st, sp.vars[vi])
                            synchronized(out) { if (f != null) out["$vi/$st"] = f; done++ }
                            progress(done, total)
                        }
                    }.awaitAll()
                }
                val ok = steps.filter { st ->
                    sp.vars.indices.all { out.containsKey("$it/$st") } &&
                        (!sp.accumulated || st == 0 || out.containsKey("0/${st - 3}"))
                }
                if (ok.isEmpty()) throw IOException("Dati ECMWF non disponibili")
                withContext(Dispatchers.IO) {
                    val keep = sp.vars.flatMap { v -> need.map { st -> (base(run, st) + v).hashCode().toLong().and(0xffffffffL).toString(16) + ".grib" } }.toSet()
                    File(cacheDir, "ecmwf").listFiles()?.forEach { if (it.name !in keep) it.delete() }
                }
                val g0 = out["0/${ok.first()}"]!!
                val grid = RegGrid(g0.ni, g0.nj, g0.south, g0.west, g0.dlat, g0.dlon)
                val data = withContext(Dispatchers.Default) {
                    fun f(vi: Int, st: Int) = out["$vi/$st"]!!.values
                    val runId = run.format(DateTimeFormatter.ofPattern("yyyyMMddHH"))
                    val times = ok.map { localTime(run.plusHours(it.toLong())) }
                    when {
                        sp.accumulated -> GridData(runId, times, grid, ok.map { st ->
                            val res = FloatArray(grid.ni * grid.nj)
                            if (st > 0) {
                                val a = f(0, st); val p = f(0, st - 3)
                                for (i in res.indices) res[i] = (sp.tf(a[i] - p[i]) / 3f).coerceAtLeast(0f)
                            }
                            res
                        })
                        layer == MapLayer.WIND -> GridData(runId, times, grid, emptyList(),
                            ok.map { st -> f(0, st).let { a -> FloatArray(a.size) { sp.tf(a[it]) } } },
                            ok.map { st -> f(1, st).let { a -> FloatArray(a.size) { sp.tf(a[it]) } } })
                        else -> GridData(runId, times, grid, ok.map { st -> f(0, st).let { a -> FloatArray(a.size) { sp.tf(a[it]) } } })
                    }
                }
                return@coroutineScope data
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("ECMWF non disponibile")
    }
}
