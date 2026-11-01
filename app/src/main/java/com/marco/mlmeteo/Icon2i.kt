package com.marco.mlmeteo

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Modello ICON-2I di ItaliaMeteo (2,2 km), corsa rapida RUC ogni 3 ore con 24 ore di previsione.
 *
 * I file sono GRIB2 su griglia regolare 761x761 (lat 33,7..48,9 passo 0,02; lon 3..22 passo 0,025),
 * "simple packing" a 16 bit. Un file per variabile contiene un messaggio per ogni ora (0..24):
 * l'app scarica solo i messaggi che servono con richieste HTTP "Range" e li tiene in cache.
 */
object Icon2i {
    private const val BASE = "https://meteohub.agenziaitaliameteo.it/nwp/ICON_2I_RUC"
    const val NI = 761
    const val NJ = 761
    const val LAT0 = 33.7
    const val DLAT = 0.02
    const val LON0 = 3.0
    const val DLON = 0.025
    val LAT1 = LAT0 + DLAT * (NJ - 1)
    val LON1 = LON0 + DLON * (NI - 1)

    fun contains(lat: Double, lon: Double) = lat in (LAT0 + 0.3)..(LAT1 - 0.3) && lon in (LON0 + 0.3)..(LON1 - 0.3)

    /** Un campo per ogni ora. [u] e [v] solo per il vento (km/h). */
    class Data(
        val run: String,
        val times: List<LocalDateTime>,
        val values: List<FloatArray>,
        val u: List<FloatArray>? = null,
        val v: List<FloatArray>? = null,
    )

    lateinit var cacheDir: File

    private val offsets = HashMap<String, List<Pair<Long, Long>>>()
    private val fileNames = HashMap<String, String>()

    // ------------------------------------------------------------------ rete

    private fun runs(): List<String> {
        val html = Net.get("$BASE/")
        return Regex("href=\"(\\d{10})/\"").findAll(html).map { it.groupValues[1] }.toList().sorted().reversed()
    }

    private fun fileUrl(run: String, variable: String): String? {
        val key = "$run/$variable"
        fileNames[key]?.let { return it }
        val html = try { Net.get("$BASE/$run/$variable/") } catch (e: Exception) { return null }
        val name = Regex("href=\"([^\"]+\\.grib)\"").find(html)?.groupValues?.get(1) ?: return null
        val url = "$BASE/$run/$variable/$name"
        fileNames[key] = url
        return url
    }

    private fun range(url: String, from: Long, len: Long): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "MLMeteo/1.0 (+https://github.com/marco90-cyber/MLmeteo)")
            c.setRequestProperty("Range", "bytes=$from-${from + len - 1}")
            if (c.responseCode != 206 && c.responseCode != 200) throw IOException("ICON-2I: errore ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }.let { if (it.size > len) it.copyOf(len.toInt()) else it }
        } finally {
            c.disconnect()
        }
    }

    /** Posizione (inizio, lunghezza) di ogni messaggio GRIB nel file: un messaggio per ora. */
    private fun messages(url: String): List<Pair<Long, Long>> {
        offsets[url]?.let { return it }
        val out = ArrayList<Pair<Long, Long>>()
        var off = 0L
        while (out.size < 40) {
            val head = try { range(url, off, 16) } catch (e: Exception) { break }
            if (head.size < 16 || head[0] != 'G'.code.toByte() || head[1] != 'R'.code.toByte()) break
            val len = ByteBuffer.wrap(head, 8, 8).order(ByteOrder.BIG_ENDIAN).long
            out += off to len
            off += len
        }
        if (out.isNotEmpty()) offsets[url] = out
        return out
    }

    private fun message(url: String, index: Int): FloatArray? {
        val msgs = messages(url)
        val (off, len) = msgs.getOrNull(index) ?: return null
        val name = (url.hashCode().toLong() and 0xffffffffL).toString(16) + "_$index.grib"
        val f = File(cacheDir, "icon2i/$name")
        val bytes = if (f.exists() && f.length() == len) f.readBytes() else {
            val b = range(url, off, len)
            f.parentFile?.mkdirs()
            try { f.writeBytes(b) } catch (e: Exception) { }
            b
        }
        return decode(bytes)
    }

    /** Toglie dalla cache i file di corse vecchie. */
    private fun cleanCache(keepUrls: Set<String>) {
        val keep = keepUrls.map { (it.hashCode().toLong() and 0xffffffffL).toString(16) }.toSet()
        File(cacheDir, "icon2i").listFiles()?.forEach { if (it.name.substringBefore('_') !in keep) it.delete() }
    }

    // ------------------------------------------------------------------ GRIB2 (simple packing)

    private fun signed16(b: ByteBuffer, pos: Int): Int {
        val raw = b.getShort(pos).toInt() and 0xFFFF
        return if (raw and 0x8000 != 0) -(raw and 0x7FFF) else raw
    }

    fun decode(bytes: ByteArray): FloatArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var pos = 16
        var ref = 0f; var e = 0; var d = 0; var bits = 0; var n = 0
        var dataPos = -1; var dataLen = 0
        var template = -1
        while (pos + 5 <= bytes.size) {
            if (bytes[pos] == '7'.code.toByte() && bytes.getOrNull(pos + 1) == '7'.code.toByte()) break // "7777"
            val len = b.getInt(pos)
            val sec = bytes[pos + 4].toInt()
            if (len <= 0) break
            when (sec) {
                5 -> {
                    n = b.getInt(pos + 5)
                    template = b.getShort(pos + 9).toInt() and 0xFFFF
                    ref = b.getFloat(pos + 11)
                    e = signed16(b, pos + 15)
                    d = signed16(b, pos + 17)
                    bits = bytes[pos + 19].toInt() and 0xFF
                }
                7 -> { dataPos = pos + 5; dataLen = len - 5 }
            }
            pos += len
        }
        if (template != 0 || n <= 0) return null
        val out = FloatArray(n)
        val scaleE = 2.0.pow(e)
        val scaleD = 10.0.pow(-d)
        if (bits == 0 || dataPos < 0) {
            java.util.Arrays.fill(out, (ref * scaleD).toFloat())
            return out
        }
        if (bits == 16) {
            for (i in 0 until n) {
                val p = dataPos + i * 2
                if (p + 1 >= dataPos + dataLen) break
                val x = ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)
                out[i] = ((ref + x * scaleE) * scaleD).toFloat()
            }
        } else {
            var bitPos = 0L
            for (i in 0 until n) {
                var x = 0L
                for (k in 0 until bits) {
                    val byteIdx = dataPos + (bitPos shr 3).toInt()
                    val bit = 7 - (bitPos and 7).toInt()
                    x = (x shl 1) or ((bytes[byteIdx].toLong() shr bit) and 1)
                    bitPos++
                }
                out[i] = ((ref + x * scaleE) * scaleD).toFloat()
            }
        }
        return out
    }

    // ------------------------------------------------------------------ caricamento strati

    private fun runTime(run: String): LocalDateTime {
        val utc = LocalDateTime.parse(run, DateTimeFormatter.ofPattern("yyyyMMddHH"))
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    }

    /**
     * Scarica lo strato per le prossime [hours] ore.
     * Usa l'ultima corsa disponibile; se non è ancora completa passa alla precedente.
     */
    suspend fun load(layer: MapLayer, hours: Int, progress: (Int, Int) -> Unit): Data = coroutineScope {
        val vars = when (layer) {
            MapLayer.RAIN -> listOf("TOT_PREC")
            MapLayer.TEMP -> listOf("T_2M")
            MapLayer.WIND -> listOf("U_10M", "V_10M")
            MapLayer.GUST -> listOf("VMAX_10M")
            MapLayer.CLOUD -> listOf("CLCT")
            MapLayer.SNOW -> listOf("SNOW_GSP", "SNOW_CON")
            MapLayer.LPI -> listOf("LPI")
        }
        val accumulated = layer == MapLayer.RAIN || layer == MapLayer.SNOW
        val runList = withContext(Dispatchers.IO) { runs() }
        var lastError: Exception? = null
        for (run in runList.take(3)) {
            try {
                val urls = withContext(Dispatchers.IO) { vars.map { v -> fileUrl(run, v) ?: throw IOException("File $v mancante") } }
                val counts = withContext(Dispatchers.IO) { urls.map { messages(it).size } }
                val available = counts.minOrNull() ?: 0
                val rt = runTime(run)
                val now = LocalDateTime.now()
                val first = (java.time.Duration.between(rt, now).toMinutes() / 60.0).let { kotlin.math.floor(it).toInt() }.coerceAtLeast(0)
                if (available < first + 3) throw IOException("Corsa $run incompleta")
                val last = (first + hours).coerceAtMost(available - 1)
                val steps = (first..last).toList()
                val need = (if (accumulated) listOf((first - 1).coerceAtLeast(0)) + steps else steps).distinct()
                var done = 0
                val total = need.size * urls.size
                // scarica (4 alla volta)
                val fields = HashMap<String, FloatArray>()
                val jobs = urls.flatMapIndexed { vi, url -> need.map { s -> Triple(vi, url, s) } }
                for (chunk in jobs.chunked(4)) {
                    chunk.map { (vi, url, s) ->
                        async(Dispatchers.IO) {
                            val f = message(url, s) ?: throw IOException("Dato mancante")
                            synchronized(fields) { fields["$vi/$s"] = f; done++ }
                            progress(done, total)
                        }
                    }.awaitAll()
                }
                withContext(Dispatchers.IO) { cleanCache(urls.toSet()) }
                val times = steps.map { rt.plusHours(it.toLong()) }
                val result = withContext(Dispatchers.Default) {
                    fun f(vi: Int, s: Int) = fields["$vi/$s"]!!
                    when (layer) {
                        MapLayer.RAIN, MapLayer.SNOW -> Data(run, times, steps.map { s ->
                            val prev = (s - 1).coerceAtLeast(0)
                            val out = FloatArray(NI * NJ)
                            for (vi in vars.indices) {
                                val a = f(vi, s); val p = f(vi, prev)
                                for (i in out.indices) out[i] += if (s == 0) 0f else (a[i] - p[i]).coerceAtLeast(0f)
                            }
                            out
                        })
                        MapLayer.TEMP -> Data(run, times, steps.map { s -> f(0, s).let { a -> FloatArray(a.size) { a[it] - 273.15f } } })
                        MapLayer.WIND -> {
                            val us = steps.map { s -> f(0, s).let { a -> FloatArray(a.size) { a[it] * 3.6f } } }
                            val vs = steps.map { s -> f(1, s).let { a -> FloatArray(a.size) { a[it] * 3.6f } } }
                            Data(run, times, emptyList(), us, vs)
                        }
                        MapLayer.GUST -> Data(run, times, steps.map { s -> f(0, s).let { a -> FloatArray(a.size) { a[it] * 3.6f } } })
                        else -> Data(run, times, steps.map { s -> f(0, s) })
                    }
                }
                return@coroutineScope result
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("ICON-2I non disponibile")
    }

    // ------------------------------------------------------------------ disegno

    /** Altezza dell'immagine in Web Mercator (larghezza = NI). */
    val H: Int by lazy {
        val dx = lonToX(LON1) - lonToX(LON0)
        val dy = latToY(LAT0) - latToY(LAT1)
        (NI * dy / dx).roundToInt()
    }

    private val rowMap: IntArray by lazy {
        val y0 = latToY(LAT1); val y1 = latToY(LAT0)
        IntArray(H) { j ->
            val lat = yToLat(y0 + (j + 0.5) / H * (y1 - y0))
            ((lat - LAT0) / DLAT).roundToInt().coerceIn(0, NJ - 1)
        }
    }

    /** Immagine dell'ora [k]; per il vento la velocità si calcola da u e v. */
    fun renderStep(d: Data, k: Int, layer: MapLayer): ImageBitmap? {
        if (layer == MapLayer.WIND) {
            val u = d.u?.getOrNull(k) ?: return null
            val v = d.v?.getOrNull(k) ?: return null
            return render(FloatArray(u.size) { sqrt(u[it] * u[it] + v[it] * v[it]) }, layer)
        }
        return render(d.values.getOrNull(k) ?: return null, layer)
    }

    fun render(values: FloatArray, layer: MapLayer): ImageBitmap {
        val stops = layer.stops
        val lo = stops.first().first
        val hi = stops.last().first
        val lut = IntArray(512) { k ->
            val v = lo + (hi - lo) * k / 511.0
            colorAt(v, stops).toArgb()
        }
        val px = IntArray(NI * H)
        val rows = rowMap
        for (j in 0 until H) {
            val src = rows[j] * NI
            val dst = j * NI
            for (i in 0 until NI) {
                val v = values[src + i]
                px[dst + i] = if (v.isNaN() || v < lo) 0 else lut[(((v - lo) / (hi - lo)) * 511).toInt().coerceIn(0, 511)]
            }
        }
        return Bitmap.createBitmap(px, NI, H, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Valore nel punto (lat, lon), interpolato. */
    fun sample(values: FloatArray, lat: Double, lon: Double): Float {
        val fy = (lat - LAT0) / DLAT
        val fx = (lon - LON0) / DLON
        if (fy < 0 || fx < 0 || fy > NJ - 1 || fx > NI - 1) return Float.NaN
        val j = fy.toInt().coerceAtMost(NJ - 2); val i = fx.toInt().coerceAtMost(NI - 2)
        val ty = (fy - j).toFloat(); val tx = (fx - i).toFloat()
        val a = values[j * NI + i]; val b = values[j * NI + i + 1]
        val c = values[(j + 1) * NI + i]; val d = values[(j + 1) * NI + i + 1]
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }
}

fun colorAt(v: Double, stops: List<Pair<Double, Color>>): Color {
    if (v <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (a, ca) = stops[i - 1]
        val (b, cb) = stops[i]
        if (v <= b) return lerp(ca, cb, ((v - a) / (b - a)).toFloat())
    }
    return stops.last().second
}
