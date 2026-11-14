package com.marco.mlmeteo

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Allerte calcolate dalla previsione oraria della fonte scelta, come quelle di Today Weather
 * ("Allerta grave per vento", "Allerta grave per forte pioggia").
 *
 * Soglie (prossime 48 ore):
 * - Vento: raffiche ≥ 60 km/h (gialla), ≥ 75 (arancione, "grave"), ≥ 100 (rossa, "estrema");
 *   oppure vento medio ≥ 40 / 55 / 75 km/h.
 * - Pioggia: intensità ≥ 10 / 20 / 40 mm/h, oppure accumulo dell'evento ≥ 30 / 50 / 80 mm.
 * Le ore consecutive sopra soglia (con buchi fino a 2 ore) formano un unico evento.
 */
object SevereAlerts {
    const val SOURCE_PREFIX = "Previsione"
    private const val HOURS_AHEAD = 48L
    private const val MAX_GAP = 2

    fun compute(p: Place, f: Forecast, s: Settings): List<Alert> {
        // ora locale della località adesso (la previsione può essere stata scaricata ore fa)
        val elapsed = (System.currentTimeMillis() - f.fetchedAt).coerceAtLeast(0)
        val now = f.localNow.plusNanos(elapsed * 1_000_000).withMinute(0).withSecond(0).withNano(0)
        val hours = f.hours.filter { !it.time.isBefore(now) && it.time.isBefore(now.plusHours(HOURS_AHEAD)) }
        if (hours.isEmpty()) return emptyList()
        val out = ArrayList<Alert>()
        if (s.alertWind) out += wind(p, f, s, hours)
        if (s.alertRain) out += rain(p, f, s, hours)
        return out
    }

    private fun windLevel(h: Hour): Int {
        val g = if (h.gust.ok()) h.gust else 0.0
        val w = if (h.wind.ok()) h.wind else 0.0
        val byGust = when { g >= 100 -> 3; g >= 75 -> 2; g >= 60 -> 1; else -> 0 }
        val byWind = when { w >= 75 -> 3; w >= 55 -> 2; w >= 40 -> 1; else -> 0 }
        return maxOf(byGust, byWind)
    }

    private fun rainLevel(mmH: Double) = when { mmH >= 40 -> 3; mmH >= 20 -> 2; mmH >= 10 -> 1; else -> 0 }
    private fun totalLevel(mm: Double) = when { mm >= 80 -> 3; mm >= 50 -> 2; mm >= 30 -> 1; else -> 0 }

    /** Raggruppa le ore che soddisfano [hit] in eventi contigui (tollerando brevi pause). */
    private fun events(hours: List<Hour>, hit: (Hour) -> Boolean): List<List<Hour>> {
        val out = ArrayList<List<Hour>>()
        var cur = ArrayList<Hour>()
        var gap = 0
        for (h in hours) {
            if (hit(h)) {
                cur.add(h); gap = 0
            } else if (cur.isNotEmpty()) {
                gap++
                if (gap > MAX_GAP) { out.add(cur); cur = ArrayList(); gap = 0 }
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun zoned(t: LocalDateTime) = t.atZone(ZoneId.systemDefault())

    private fun title(level: Int, what: String) = when (level) {
        3 -> "Allerta estrema per $what"
        2 -> "Allerta grave per $what"
        else -> "Allerta per $what"
    }

    private fun wind(p: Place, f: Forecast, s: Settings, hours: List<Hour>): List<Alert> {
        val fmt = Fmt(s)
        return events(hours) { windLevel(it) > 0 }.map { ev ->
            val lv = ev.maxOf { windLevel(it) }
            val peak = ev.maxByOrNull { if (it.gust.ok()) it.gust else it.wind }!!
            val maxWind = ev.mapNotNull { h -> h.wind.takeIf { it.ok() } }.maxOrNull()
            val detail = buildString {
                if (peak.gust.ok()) append("Raffiche fino a ${fmt.wind(peak.gust)} verso le ${fmt.time(peak.time)}")
                if (maxWind != null) append(if (isEmpty()) "Vento fino a ${fmt.wind(maxWind)}" else ", vento medio fino a ${fmt.wind(maxWind)}")
                if (peak.windDir.ok()) append(" da ${windDirLong(peak.windDir)}")
                append(". Fissa gli oggetti all'aperto e fai attenzione agli alberi.")
            }
            Alert(
                id = "fc-wind-${ev.first().time.toLocalDate()}-$lv",
                source = "$SOURCE_PREFIX ${f.sourceName}",
                level = lv,
                title = title(lv, if (lv == 1) "vento forte" else "vento"),
                area = p.name,
                start = zoned(ev.first().time),
                end = zoned(ev.last().time.plusHours(1)),
                detail = detail,
                kind = "wind",
            )
        }
    }

    private fun rain(p: Place, f: Forecast, s: Settings, hours: List<Hour>): List<Alert> {
        val fmt = Fmt(s)
        fun mm(h: Hour) = if (h.precip.ok()) h.precip else 0.0
        // ore "bagnate" (≥ 4 mm/h), poi il livello dipende dall'intensità massima e dall'accumulo
        return events(hours) { mm(it) >= 4.0 }.mapNotNull { ev ->
            val total = ev.sumOf { mm(it) }
            val peak = ev.maxByOrNull { mm(it) }!!
            val lv = maxOf(rainLevel(mm(peak)), totalLevel(total))
            if (lv == 0) return@mapNotNull null
            val storm = ev.any { skyOf(it.code) == Sky.STORM }
            Alert(
                id = "fc-rain-${ev.first().time.toLocalDate()}-$lv",
                source = "$SOURCE_PREFIX ${f.sourceName}",
                level = lv,
                title = title(lv, if (lv == 1) "pioggia forte" else "forte pioggia"),
                area = p.name,
                start = zoned(ev.first().time),
                end = zoned(ev.last().time.plusHours(1)),
                detail = "Circa ${fmt.rain(total)} in totale, fino a ${fmt.rain(mm(peak))} in un'ora verso le ${fmt.time(peak.time)}" +
                    (if (storm) ", con temporali" else "") + ". Possibili allagamenti: evita sottopassi e corsi d'acqua.",
                kind = "rain",
            )
        }
    }
}
