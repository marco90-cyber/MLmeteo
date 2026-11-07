package com.marco.mlmeteo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Multimodello della mappa (solo pioggia, prossime 6 ore): media pesata dei modelli più dettagliati
 * e affidabili sull'Italia: ICON-2I e WRF (ItaliaMeteo), ICON-EU (DWD) ed ECMWF HD.
 */
object MultiMap {
    const val HOURS = 6
    val members = listOf("icon2i" to 3.0, "wrf" to 2.5, "ecmwf" to 2.0, "iconeu" to 1.5)
    const val LABEL = "Multimodello"

    /** Griglia comune sull'Italia (circa 2 km). */
    val grid = RegGrid(561, 616, 35.5, 5.5, 0.02, 0.025)

    fun contains(lat: Double, lon: Double) = lat in grid.south..grid.north && lon in grid.west..grid.east

    /** [stepH] = ore tra un passo e l'altro (ECMWF ogni 3 ore: il valore vale per le ore prima del passo). */
    private class Member(val weight: Double, val times: List<LocalDateTime>, val stepH: Long = 1, val sample: (Int, Double, Double) -> Float)

    suspend fun load(progress: (Int, Int) -> Unit): GridData {
        val loaded = ArrayList<Member>()
        val total = members.size
        members.forEachIndexed { i, (id, w) ->
            progress(i, total)
            try {
                val m = when (id) {
                    "icon2i" -> Icon2i.load(MapLayer.RAIN, HOURS) { _, _ -> }.let { d ->
                        Member(w, d.times) { k, lat, lon -> d.values.getOrNull(k)?.let { Icon2i.sample(it, lat, lon) } ?: Float.NaN }
                    }
                    "wrf" -> Wrf.load(MapLayer.RAIN, HOURS) { _, _ -> }.let { d ->
                        Member(w, d.times) { k, lat, lon -> d.values.getOrNull(k)?.let { Wrf.sampleAt(it, lat, lon) } ?: Float.NaN }
                    }
                    "ecmwf" -> Ecmwf.load(MapLayer.RAIN, HOURS + 3) { _, _ -> }.let { d ->
                        Member(w, d.times, 3) { k, lat, lon -> d.values.getOrNull(k)?.let { d.grid.sample(it, lat, lon) } ?: Float.NaN }
                    }
                    else -> IconEu.m.load(MapLayer.RAIN, HOURS) { _, _ -> }.let { d ->
                        Member(w, d.times) { k, lat, lon -> d.values.getOrNull(k)?.let { d.grid.sample(it, lat, lon) } ?: Float.NaN }
                    }
                }
                loaded += m
            } catch (e: Exception) { }
        }
        progress(total, total)
        if (loaded.isEmpty()) throw java.io.IOException("Nessun modello disponibile")
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val times = loaded.filter { it.stepH == 1L }.ifEmpty { loaded }.flatMap { it.times }.map { it.truncatedTo(ChronoUnit.HOURS) }.distinct().sorted()
            .filter { !it.isBefore(now) && !it.isAfter(now.plusHours(HOURS.toLong())) }
        if (times.isEmpty()) throw java.io.IOException("Dati non disponibili")
        val values = withContext(Dispatchers.Default) {
            times.map { t ->
                val idx = loaded.map { m ->
                    if (m.stepH == 1L) m.times.indexOfFirst { it.truncatedTo(ChronoUnit.HOURS) == t }
                    // passo che contiene quest'ora (intensità media delle 3 ore prima del passo)
                    else m.times.indexOfFirst { val x = it.truncatedTo(ChronoUnit.HOURS); !x.isBefore(t) && x.isBefore(t.plusHours(m.stepH)) }
                }
                val out = FloatArray(grid.ni * grid.nj)
                for (j in 0 until grid.nj) {
                    val lat = grid.south + j * grid.dlat
                    for (i in 0 until grid.ni) {
                        val lon = grid.west + i * grid.dlon
                        var sum = 0.0; var ws = 0.0
                        loaded.forEachIndexed { mi, m ->
                            val k = idx[mi]
                            if (k >= 0) {
                                val v = m.sample(k, lat, lon)
                                if (!v.isNaN()) { sum += v * m.weight; ws += m.weight }
                            }
                        }
                        out[j * grid.ni + i] = if (ws > 0) (sum / ws).toFloat() else Float.NaN
                    }
                }
                out
            }
        }
        return GridData(now.toString(), times, grid, values)
    }
}
