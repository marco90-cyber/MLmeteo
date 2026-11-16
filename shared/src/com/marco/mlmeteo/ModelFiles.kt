package com.marco.mlmeteo

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Pezzi comuni ai modelli scaricati come file GRIB (GFS, ICON, ECMWF, ICON-2I, WRF).

/** Nome breve e stabile per salvare in cache il file di un indirizzo. */
internal fun cacheKey(url: String): String = (url.hashCode().toLong() and 0xffffffffL).toString(16)

/**
 * Legge un GRIB dalla cache oppure lo scarica con [download] e lo salva in [f].
 * Restituisce null se lo scaricamento non riesce o il contenuto non è un GRIB.
 */
internal inline fun cachedGrib(f: File, download: () -> ByteArray?): ByteArray? {
    if (f.exists() && f.length() > 100) return f.readBytes()
    val b = download() ?: return null
    if (b.size < 16 || b[0] != 'G'.code.toByte()) return null
    f.parentFile?.mkdirs()
    try { f.writeBytes(b) } catch (e: Exception) { }
    return b
}

/**
 * Interpolazione bilineare su una griglia regolare di [ni] x [nj] punti.
 * [fx] e [fy] sono la posizione in passi di griglia; fuori dalla griglia restituisce NaN.
 */
fun bilinear(values: FloatArray, ni: Int, nj: Int, fx: Double, fy: Double): Float {
    if (fy < 0 || fx < 0 || fy > nj - 1 || fx > ni - 1) return Float.NaN
    val j = fy.toInt().coerceAtMost(nj - 2); val i = fx.toInt().coerceAtMost(ni - 2)
    val ty = (fy - j).toFloat(); val tx = (fx - i).toFloat()
    val a = values[j * ni + i]; val b = values[j * ni + i + 1]
    val c = values[(j + 1) * ni + i]; val d = values[(j + 1) * ni + i + 1]
    return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
}

/**
 * File dei modelli di ItaliaMeteo (meteohub), usati da ICON-2I e WRF.
 *
 * Ogni variabile ha un file GRIB con un messaggio per ogni ora: si scaricano solo i messaggi
 * che servono con richieste HTTP "Range" e si tengono in cache nella cartella [folder].
 */
internal class Meteohub(
    private val base: String,
    private val label: String,
    private val folder: String,
    private val prefix: String,
    private val maxMessages: Int,
    private val cacheDir: () -> File,
) {
    private val offsets = HashMap<String, List<Pair<Long, Long>>>()
    private val fileNames = HashMap<String, String>()

    /** Corse disponibili (yyyyMMddHH), dalla più recente. */
    fun runs(): List<String> {
        val html = Net.get("$base/")
        return Regex("href=\"(\\d{10})/\"").findAll(html).map { it.groupValues[1] }.toList().sorted().reversed()
    }

    /** Indirizzo del file di una variabile; "T@850" = cartella T, file del livello 850 hPa. */
    fun fileUrl(run: String, variable: String): String? {
        val key = "$run/$variable"
        fileNames[key]?.let { return it }
        val dir = variable.substringBefore('@')
        val lev = variable.substringAfter('@', "")
        val html = try { Net.get("$base/$run/$dir/") } catch (e: Exception) { return null }
        val names = Regex("href=\"([^\"]+\\.grib)\"").findAll(html).map { it.groupValues[1] }.toList()
        val name = (if (lev.isEmpty()) names.firstOrNull() else names.firstOrNull { it.endsWith("isobaricInhPa-$lev.grib") }) ?: return null
        return "$base/$run/$dir/$name".also { fileNames[key] = it }
    }

    private fun range(url: String, from: Long, len: Long): ByteArray {
        val b = try {
            Net.fetch(url, 20000, 30000, from to len)
        } catch (e: IOException) {
            throw IOException("$label: ${e.message}")
        }
        return if (b.size > len) b.copyOf(len.toInt()) else b
    }

    /** Posizione (inizio, lunghezza) di ogni messaggio GRIB nel file: un messaggio per ora. */
    fun messages(url: String): List<Pair<Long, Long>> {
        offsets[url]?.let { return it }
        val out = ArrayList<Pair<Long, Long>>()
        var off = 0L
        while (out.size < maxMessages) {
            val head = try { range(url, off, 16) } catch (e: Exception) { break }
            if (head.size < 16 || head[0] != 'G'.code.toByte() || head[1] != 'R'.code.toByte()) break
            val len = ByteBuffer.wrap(head, 8, 8).order(ByteOrder.BIG_ENDIAN).long
            out += off to len
            off += len
        }
        if (out.isNotEmpty()) offsets[url] = out
        return out
    }

    /** Messaggio numero [index] del file, dalla cache o scaricato. */
    fun messageBytes(url: String, index: Int): ByteArray? {
        val (off, len) = messages(url).getOrNull(index) ?: return null
        val f = File(cacheDir(), "$folder/$prefix${cacheKey(url)}_$index.grib")
        if (f.exists() && f.length() == len) return f.readBytes()
        val b = range(url, off, len)
        f.parentFile?.mkdirs()
        try { f.writeBytes(b) } catch (e: Exception) { }
        return b
    }

    /** Toglie dalla cache i file di corse vecchie. */
    fun cleanCache(keepUrls: Set<String>) {
        val keep = keepUrls.map { prefix + cacheKey(it) }.toSet()
        File(cacheDir(), folder).listFiles()?.forEach { if (it.name.substringBefore('_') !in keep) it.delete() }
    }

    companion object {
        /** Ora locale di una corsa indicata come yyyyMMddHH (UTC). */
        fun runTime(run: String): LocalDateTime {
            val utc = LocalDateTime.parse(run, DateTimeFormatter.ofPattern("yyyyMMddHH"))
            return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        }
    }
}
