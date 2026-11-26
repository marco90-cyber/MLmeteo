package com.marco.mlmeteo

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.text.Normalizer
import java.util.Locale

/** Un'allerta meteo. [level]: 1 gialla, 2 arancione, 3 rossa. */
data class Alert(
    val id: String,
    val source: String,
    val level: Int,
    val title: String,
    val area: String,
    val start: ZonedDateTime?,
    val end: ZonedDateTime?,
    val detail: String = "",
    /** Tipo di fenomeno: "wind", "rain" o vuoto. */
    val kind: String = "",
)

fun levelName(level: Int) = when (level) { 1 -> "gialla"; 2 -> "arancione"; 3 -> "rossa"; else -> "" }

/**
 * Allerte da due fonti ufficiali:
 * - Protezione Civile: bollettino nazionale di criticità (oggi e domani) sulle 156 zone d'allerta,
 *   cercando il comune della località nell'elenco dei comuni di ogni zona;
 * - MeteoAlarm: allerte dei servizi meteo europei, per regione.
 */
object AlertsRepo {
    private const val DPC_REPO = "pcm-dpc/DPC-Bollettini-Criticita-Idrogeologica-Idraulica"
    lateinit var cacheDir: File

    private class Cached(val at: Long, val ttl: Long, val list: List<Alert>)

    private val cache = HashMap<String, Cached>()

    /** Ultimo risultato valido di una fonte per una località (chiave "località|fonte"): resta in uso se la fonte non risponde. */
    private val lastGood = HashMap<String, List<Alert>>()
    private val lastOk = HashMap<String, Long>()

    /**
     * Esito dell'ultimo controllo delle allerte ufficiali di una località.
     * [failed]: fonti che non hanno risposto (le allerte mostrate possono essere vecchie);
     * [lastOkAt]: ora dell'ultimo controllo riuscito di tutte le fonti (0 = mai).
     */
    class Status(val failed: List<String>, val checkedAt: Long, val lastOkAt: Long)

    val status = mutableStateMapOf<String, Status>()

    /**
     * Allerte ufficiali più, se si passano previsione e impostazioni, quelle calcolate dalla previsione
     * (vento forte e pioggia forte, vedi [SevereAlerts]).
     */
    suspend fun forPlace(p: Place, force: Boolean = false, f: Forecast? = null, s: Settings? = null): List<Alert> {
        val official = official(p, force)
        if (f == null || s == null) return official
        val computed = try { withContext(Dispatchers.Default) { SevereAlerts.compute(p, f, s) } } catch (e: Exception) { emptyList() }
        return (official + computed).sortedWith(compareByDescending<Alert> { it.level }.thenBy { it.start })
    }

    private suspend fun official(p: Place, force: Boolean): List<Alert> = withContext(Dispatchers.IO) {
        cache[p.id]?.let { if (!force && System.currentTimeMillis() - it.at < it.ttl) return@withContext it.list }
        val out = ArrayList<Alert>()
        val failed = ArrayList<String>()
        val sources = ArrayList<String>()
        val italy = p.cc == "it" || (p.cc.isBlank() && p.region.contains("Italia"))
        // Una fonte che non risponde non deve sembrare "nessuna allerta": si tiene l'ultimo risultato buono e si segnala l'errore.
        fun run(name: String, block: () -> List<Alert>) {
            val key = "${p.id}|$name"
            sources += key
            try {
                val r = block()
                lastGood[key] = r
                lastOk[key] = System.currentTimeMillis()
                out += r
            } catch (e: Exception) {
                failed += name
                lastGood[key]?.let { out += it }
            }
        }
        if (italy) run("Protezione Civile") { dpc(p) }
        run("MeteoAlarm") { meteoAlarm(p) }
        val now = ZonedDateTime.now()
        val list = out.filter { it.end == null || it.end.isAfter(now) }
            .sortedWith(compareByDescending<Alert> { it.level }.thenBy { it.start })
        val t = System.currentTimeMillis()
        // con errori si riprova presto (2 minuti), altrimenti dopo 30
        cache[p.id] = Cached(t, if (failed.isEmpty()) 30 * 60_000 else 2 * 60_000, list)
        status[p.id] = Status(failed, t, if (failed.isEmpty()) t else sources.mapNotNull { lastOk[it] }.minOrNull() ?: 0L)
        list
    }

    // ------------------------------------------------------------------ Protezione Civile

    private class DpcZone(val name: String, val comuni: Set<String>, val idraulico: Int, val temporali: Int, val idrogeologico: Int)

    private var dpcStamp = ""
    private var dpcCheckedAt = 0L
    private var dpcZones: Map<LocalDate, List<DpcZone>> = emptyMap()

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(Locale.ITALY), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]"), "")

    private fun level(text: String): Int {
        val t = text.uppercase(Locale.ITALY)
        return when {
            "ROSSA" in t -> 3
            "ARANCIONE" in t -> 2
            "GIALLA" in t -> 1
            else -> 0
        }
    }

    /** Il bollettino più recente: data e ora di emissione (es. 20261031_1420). */
    private fun latestStamp(): String? {
        val arr = JSONArray(Net.get("https://api.github.com/repos/$DPC_REPO/commits?path=files/topojson&per_page=1"))
        val sha = arr.getJSONObject(0).getString("sha")
        val files = JSONObject(Net.get("https://api.github.com/repos/$DPC_REPO/commits/$sha")).getJSONArray("files")
        for (i in 0 until files.length()) {
            val name = files.getJSONObject(i).getString("filename")
            Regex("(\\d{8}_\\d{4})_").find(name)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun loadZones(stamp: String, which: String): List<DpcZone> {
        val f = File(cacheDir, "dpc_${stamp}_$which.json")
        val text = if (f.exists()) f.readText() else {
            Net.get("https://raw.githubusercontent.com/$DPC_REPO/master/files/topojson/${stamp}_$which.json", 30000)
                .also { t -> cacheDir.listFiles()?.filter { it.name.startsWith("dpc_") && !it.name.contains(stamp) }?.forEach { it.delete() }; f.writeText(t) }
        }
        val o = JSONObject(text)
        val objects = o.getJSONObject("objects")
        val out = ArrayList<DpcZone>()
        for (key in objects.keys()) {
            val geoms = objects.getJSONObject(key).optJSONArray("geometries") ?: continue
            for (i in 0 until geoms.length()) {
                val pr = geoms.getJSONObject(i).optJSONObject("properties") ?: continue
                val comuni = pr.optJSONArray("Comuni")?.let { a -> (0 until a.length()).map { norm(a.getString(it)) }.toSet() } ?: emptySet()
                out += DpcZone(
                    pr.optString("Nome zona"), comuni,
                    level(pr.optString("Per rischio idraulico")),
                    level(pr.optString("Per rischio temporali")),
                    level(pr.optString("Per rischio idrogeologico")),
                )
            }
        }
        return out
    }

    private fun dpc(p: Place): List<Alert> {
        if (System.currentTimeMillis() - dpcCheckedAt > 60 * 60_000 || dpcZones.isEmpty()) {
            val stamp = latestStamp() ?: throw IOException("Bollettino della Protezione Civile non trovato")
            if (stamp != dpcStamp || dpcZones.isEmpty()) {
                val d = LocalDate.parse(stamp.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val m = HashMap<LocalDate, List<DpcZone>>()
                try { m[d] = loadZones(stamp, "today") } catch (e: Exception) { }
                try { m[d.plusDays(1)] = loadZones(stamp, "tomorrow") } catch (e: Exception) { }
                if (m.isEmpty()) throw IOException("Bollettino della Protezione Civile non scaricabile")
                dpcZones = m
                dpcStamp = stamp
            }
            dpcCheckedAt = System.currentTimeMillis()
        }
        val key = norm(p.name)
        val zone = ZoneId.of("Europe/Rome")
        val out = ArrayList<Alert>()
        for ((date, zones) in dpcZones) {
            if (date.isBefore(LocalDate.now(zone))) continue
            val matches = zones.filter { key in it.comuni }
            if (matches.isEmpty()) continue
            // un comune può stare in più zone: si prende il livello più alto
            val z = DpcZone(
                matches.joinToString(" / ") { it.name }, emptySet(),
                matches.maxOf { it.idraulico }, matches.maxOf { it.temporali }, matches.maxOf { it.idrogeologico },
            )
            val start = date.atStartOfDay(zone)
            val end = date.plusDays(1).atStartOfDay(zone)
            fun add(kind: String, lv: Int) {
                if (lv > 0) out += Alert("dpc-$dpcStamp-$date-$kind", "Protezione Civile", lv,
                    "Allerta ${levelName(lv)} · $kind", z.name, start, end,
                    "Bollettino di criticità nazionale per la zona \"${z.name}\".")
            }
            add("Temporali", z.temporali)
            add("Rischio idrogeologico", z.idrogeologico)
            add("Rischio idraulico", z.idraulico)
        }
        return out
    }

    // ------------------------------------------------------------------ MeteoAlarm

    private val feeds = mapOf(
        "it" to "italy", "fr" to "france", "de" to "germany", "es" to "spain", "at" to "austria", "ch" to "switzerland",
        "gb" to "united-kingdom", "ie" to "ireland", "pt" to "portugal", "be" to "belgium", "nl" to "netherlands",
        "lu" to "luxembourg", "dk" to "denmark", "se" to "sweden", "no" to "norway", "fi" to "finland", "pl" to "poland",
        "cz" to "czechia", "sk" to "slovakia", "hu" to "hungary", "si" to "slovenia", "hr" to "croatia", "rs" to "serbia",
        "ba" to "bosnia-herzegovina", "me" to "montenegro", "mk" to "republic-of-north-macedonia", "gr" to "greece",
        "cy" to "cyprus", "mt" to "malta", "ro" to "romania", "bg" to "bulgaria", "md" to "moldova", "ua" to "ukraine",
        "ee" to "estonia", "lv" to "latvia", "lt" to "lithuania", "is" to "iceland", "il" to "israel",
    )

    private val eventIt = listOf(
        "thunderstorm" to "Temporali", "rain-flood" to "Piene", "flood" to "Alluvioni", "rain" to "Pioggia",
        "wind" to "Vento", "snow" to "Neve e ghiaccio", "ice" to "Neve e ghiaccio", "fog" to "Nebbia",
        "high-temperature" to "Caldo", "high temperature" to "Caldo", "low-temperature" to "Freddo", "low temperature" to "Freddo",
        "coastal" to "Mareggiate", "forest" to "Incendi boschivi", "avalanche" to "Valanghe",
    )

    private fun tag(s: String, name: String): String =
        Regex("<$name>([\\s\\S]*?)</$name>").find(s)?.groupValues?.get(1)?.trim() ?: ""

    private fun meteoAlarm(p: Place): List<Alert> {
        val slug = feeds[p.cc.ifBlank { "it" }] ?: return emptyList()
        val xml = Net.get("https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-$slug", 20000)
        val region = norm(p.region)
        val out = ArrayList<Alert>()
        for (entry in xml.split("<entry>").drop(1)) {
            val area = tag(entry, "cap:areaDesc")
            if (area.isBlank() || norm(area) !in region) continue
            val event = tag(entry, "cap:event")
            val lv = when {
                event.startsWith("Red", true) -> 3
                event.startsWith("Orange", true) -> 2
                event.startsWith("Yellow", true) -> 1
                else -> 0
            }
            if (lv == 0) continue
            val type = eventIt.firstOrNull { event.lowercase().contains(it.first) }?.second ?: "Meteo"
            fun time(t: String) = try { OffsetDateTime.parse(tag(entry, t)).atZoneSameInstant(ZoneId.systemDefault()) } catch (e: Exception) { null }
            out += Alert(
                "ma-" + tag(entry, "cap:identifier"), "MeteoAlarm", lv,
                "Allerta ${levelName(lv)} · $type", area, time("cap:onset") ?: time("cap:effective"), time("cap:expires"),
                "Avviso dei servizi meteo nazionali (MeteoAlarm) per $area.",
                kind = when (type) { "Vento" -> "wind"; "Pioggia", "Piene", "Alluvioni" -> "rain"; else -> "" },
            )
        }
        return out
    }
}
