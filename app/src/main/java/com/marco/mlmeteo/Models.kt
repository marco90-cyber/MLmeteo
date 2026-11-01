package com.marco.mlmeteo

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/** Tipo di icona meteo (la variante notte si sceglie con isDay). */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

/** Valori mancanti = Double.NaN. */
data class Current(
    val temp: Double,
    val feels: Double,
    val code: Int,
    val isDay: Boolean,
    val humidity: Double,
    val dewPoint: Double,
    val windSpeed: Double,
    val windDir: Double,
    val windGusts: Double,
    val pressure: Double,
    val visibility: Double,
    val uv: Double,
    val precip: Double,
)

data class Hour(
    val time: LocalDateTime,
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val precipProb: Double,
    val precip: Double,
)

data class Day(
    val date: LocalDate,
    val code: Int,
    val tMax: Double,
    val tMin: Double,
    val precipProb: Double,
    val precipSum: Double,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val uvMax: Double,
)

data class Forecast(
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
    val sourceName: String,
    val fetchedAt: Long,
    /** Ora locale della località al momento del download. */
    val localNow: LocalDateTime,
)

data class AirQuality(val aqi: Double, val pm25: Double, val pm10: Double)

data class Place(
    val id: String,
    val name: String,
    val region: String,
    val lat: Double,
    val lon: Double,
    val gps: Boolean = false,
    /** Codice paese (it, fr…), per le allerte MeteoAlarm. */
    val cc: String = "",
)

fun Double.ok() = !isNaN()

// ------------------------------------------------------------------ codici meteo WMO

fun skyOf(code: Int): Sky = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    51, 53, 55, 56, 57 -> Sky.DRIZZLE
    61, 63, 65, 66, 67, 80, 81, 82 -> Sky.RAIN
    71, 73, 75, 77, 85, 86 -> Sky.SNOW
    95, 96, 99 -> Sky.STORM
    else -> Sky.CLOUDY
}

fun conditionText(code: Int, isDay: Boolean = true): String = when (code) {
    0 -> if (isDay) "Soleggiato" else "Sereno"
    1 -> "Poco nuvoloso"
    2 -> "Parzialmente nuvoloso"
    3 -> "Nuvoloso"
    45 -> "Nebbia"
    48 -> "Nebbia con brina"
    51 -> "Pioggerella debole"
    53 -> "Pioggerella"
    55 -> "Pioggerella intensa"
    56, 57 -> "Pioggerella gelata"
    61 -> "Pioggia debole"
    63 -> "Pioggia"
    65 -> "Pioggia forte"
    66, 67 -> "Pioggia gelata"
    71 -> "Neve debole"
    73 -> "Neve"
    75 -> "Neve forte"
    77 -> "Nevischio"
    80 -> "Rovesci deboli"
    81 -> "Rovesci"
    82 -> "Rovesci violenti"
    85, 86 -> "Rovesci di neve"
    95 -> "Temporale"
    96, 99 -> "Temporale con grandine"
    else -> "—"
}

/** Codici MET Norway (symbol_code) -> codice WMO equivalente. */
fun metnoToWmo(symbol: String): Int {
    val s = symbol.substringBefore('_')
    val thunder = s.contains("thunder")
    val showers = s.contains("showers")
    return when {
        thunder -> 95
        s.startsWith("clearsky") -> 0
        s.startsWith("fair") -> 1
        s.startsWith("partlycloudy") -> 2
        s.startsWith("cloudy") -> 3
        s.startsWith("fog") -> 45
        s.contains("sleet") -> 66
        s.contains("snow") -> when {
            s.startsWith("light") -> if (showers) 85 else 71
            s.startsWith("heavy") -> if (showers) 86 else 75
            else -> if (showers) 85 else 73
        }
        s.contains("rain") -> when {
            s.startsWith("light") -> if (showers) 80 else 61
            s.startsWith("heavy") -> if (showers) 82 else 65
            else -> if (showers) 81 else 63
        }
        else -> 3
    }
}

// ------------------------------------------------------------------ luna

data class MoonInfo(val name: String, val fraction: Double, val illumination: Double)

fun moonInfo(date: LocalDate): MoonInfo {
    // Luna nuova di riferimento: 6 gennaio 2000, 18:14 UTC
    val ref = LocalDate.of(2000, 1, 6).toEpochDay() + 18.0 / 24.0 + 14.0 / 1440.0
    val now = date.toEpochDay() + 0.5
    val synodic = 29.530588853
    var f = ((now - ref) / synodic)
    f -= floor(f)
    val illum = (1 - cos(2 * PI * f)) / 2
    val name = when {
        f < 0.03 || f >= 0.97 -> "Luna nuova"
        f < 0.22 -> "Luna crescente"
        f < 0.28 -> "Primo quarto"
        f < 0.47 -> "Gibbosa crescente"
        f < 0.53 -> "Luna piena"
        f < 0.72 -> "Gibbosa calante"
        f < 0.78 -> "Ultimo quarto"
        else -> "Luna calante"
    }
    return MoonInfo(name, f, illum)
}

fun windDirText(deg: Double): String {
    if (!deg.ok()) return ""
    val names = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    return names[(((deg % 360) + 360 + 22.5) / 45).toInt() % 8]
}

fun aqiText(aqi: Double): String = when {
    !aqi.ok() -> "—"
    aqi <= 20 -> "Ottima"
    aqi <= 40 -> "Buona"
    aqi <= 60 -> "Discreta"
    aqi <= 80 -> "Scarsa"
    aqi <= 100 -> "Pessima"
    else -> "Molto pessima"
}

fun uvText(uv: Double): String = when {
    !uv.ok() -> "—"
    uv < 3 -> "Basso"
    uv < 6 -> "Moderato"
    uv < 8 -> "Alto"
    uv < 11 -> "Molto alto"
    else -> "Estremo"
}
