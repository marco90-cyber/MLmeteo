package com.marco.mlmeteo

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Conversioni e testi secondo le unità scelte. */
class Fmt(private val s: Settings) {

    fun tempValue(c: Double): Double = if (s.tempUnit == "F") c * 9 / 5 + 32 else c

    fun temp(c: Double): String = if (!c.ok()) "—" else "${tempValue(c).roundToInt()}°"

    fun wind(kmh: Double): String {
        if (!kmh.ok()) return "—"
        return when (s.windUnit) {
            "ms" -> "${(kmh / 3.6).roundToInt()} m/s"
            "kn" -> "${(kmh / 1.852).roundToInt()} nodi"
            "mph" -> "${(kmh / 1.609).roundToInt()} mph"
            "bft" -> "${beaufort(kmh)} Bft"
            else -> "${kmh.roundToInt()} km/h"
        }
    }

    /** Solo il numero (per la card grande) e l'unità a parte. */
    fun windParts(kmh: Double): Pair<String, String> {
        val full = wind(kmh)
        val i = full.indexOf(' ')
        return if (i < 0) full to "" else full.substring(0, i) to full.substring(i + 1)
    }

    private fun beaufort(kmh: Double): Int {
        val limits = listOf(1, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118)
        return limits.indexOfFirst { kmh < it }.let { if (it < 0) 12 else it }
    }

    fun rain(mm: Double): String {
        if (!mm.ok()) return "—"
        return if (s.rainUnit == "in") String.format(Locale.ITALY, "%.2f in", mm / 25.4)
        else if (mm < 10) String.format(Locale.ITALY, "%.1f mm", mm) else "${mm.roundToInt()} mm"
    }

    fun pressure(hpa: Double): String {
        if (!hpa.ok()) return "—"
        return when (s.pressureUnit) {
            "mmhg" -> "${(hpa * 0.750062).roundToInt()} mmHg"
            "inhg" -> String.format(Locale.ITALY, "%.2f inHg", hpa * 0.02953)
            else -> "${hpa.roundToInt()} hPa"
        }
    }

    fun visibility(m: Double): String {
        if (!m.ok()) return "—"
        return if (s.visUnit == "mi") String.format(Locale.ITALY, "%.1f mi", m / 1609.0)
        else if (m < 10_000) String.format(Locale.ITALY, "%.1f km", m / 1000) else "${(m / 1000).roundToInt()} km"
    }

    fun time(t: LocalDateTime?): String {
        if (t == null) return "—"
        return t.format(DateTimeFormatter.ofPattern(if (s.clock24) "HH:mm" else "h:mm a", Locale.ITALY))
    }

    fun hourLabel(t: LocalDateTime): String =
        t.format(DateTimeFormatter.ofPattern(if (s.clock24) "HH:mm" else "h a", Locale.ITALY))

    /**
     * Testo pioggia secondo la scelta (% / mm / entrambe).
     * Se la fonte non dà la probabilità, mostra i mm.
     */
    fun rainShort(prob: Double, mm: Double): String {
        val p = if (prob.ok() && prob >= 1) "${prob.roundToInt()}%" else ""
        val m = if (mm.ok() && mm >= 0.1) rain(mm) else ""
        return when (s.rainDisplay) {
            "mm" -> m
            "both" -> listOf(p, m).filter { it.isNotEmpty() }.joinToString(" · ")
            else -> if (prob.ok()) p else m
        }
    }
}
