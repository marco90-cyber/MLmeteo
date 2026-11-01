package com.marco.mlmeteo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private const val UA = "MLMeteo/1.0 (+https://github.com/marco90-cyber/MLmeteo)"

object Net {
    fun get(url: String, timeoutMs: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val reason = try { JSONObject(body).optString("reason") } catch (e: Exception) { "" }
                throw IOException(if (reason.isNotBlank()) reason else "Errore HTTP $code")
            }
            return body
        } finally {
            c.disconnect()
        }
    }

    fun getBytes(url: String, timeoutMs: Int = 15000): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}

private fun JSONArray.d(i: Int): Double = if (i < length() && !isNull(i)) optDouble(i) else Double.NaN
private fun JSONObject.d(k: String): Double = if (has(k) && !isNull(k)) optDouble(k) else Double.NaN
private fun fmt(x: Double) = String.format(Locale.US, "%.4f", x)
private fun parseLdt(s: String): LocalDateTime? = try { LocalDateTime.parse(s) } catch (e: Exception) { null }

object WeatherRepo {

    private const val CURRENT = "temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,weather_code,is_day," +
        "wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,visibility,uv_index,precipitation"
    private const val HOURLY = "temperature_2m,weather_code,is_day,precipitation_probability,precipitation,visibility,uv_index"
    private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
        "precipitation_sum,sunrise,sunset,uv_index_max"

    private class Parsed(val forecast: Forecast, val utcOffset: Int)

    /** Scarica la previsione per la fonte scelta. */
    suspend fun forecast(place: Place, source: Source): Forecast = withContext(Dispatchers.IO) {
        when (source.kind) {
            SourceKind.OPEN_METEO -> openMeteo(place, source.model, source.short).forecast
            SourceKind.METNO -> metno(place)
            SourceKind.CONSENSUS -> consensus(place)
            SourceKind.API_KEY -> throw IOException("Questa fonte richiede una chiave API (in arrivo)")
        }
    }

    private fun openMeteo(place: Place, model: String, label: String, days: Int = 16): Parsed {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${fmt(place.lat)}&longitude=${fmt(place.lon)}" +
            "&current=$CURRENT&hourly=$HOURLY&daily=$DAILY&timezone=auto&forecast_days=$days" +
            (if (model.isNotBlank()) "&models=$model" else "")
        val o = JSONObject(Net.get(url))
        val off = o.optInt("utc_offset_seconds", 0)
        val c = o.getJSONObject("current")
        val now = parseLdt(c.optString("time")) ?: LocalDateTime.now()
        val current = Current(
            temp = c.d("temperature_2m"),
            feels = c.d("apparent_temperature"),
            code = c.optInt("weather_code", 3),
            isDay = c.optInt("is_day", 1) == 1,
            humidity = c.d("relative_humidity_2m"),
            dewPoint = c.d("dew_point_2m"),
            windSpeed = c.d("wind_speed_10m"),
            windDir = c.d("wind_direction_10m"),
            windGusts = c.d("wind_gusts_10m"),
            pressure = c.d("pressure_msl"),
            visibility = c.d("visibility"),
            uv = c.d("uv_index"),
            precip = c.d("precipitation"),
        )
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hourStart = now.truncatedTo(ChronoUnit.HOURS)
        val hours = ArrayList<Hour>()
        var visFallback = Double.NaN
        var uvFallback = Double.NaN
        for (i in 0 until ht.length()) {
            val t = parseLdt(ht.getString(i)) ?: continue
            if (t.isBefore(hourStart)) continue
            val temp = h.optJSONArray("temperature_2m")?.d(i) ?: Double.NaN
            if (!temp.ok()) continue
            if (hours.isEmpty()) {
                visFallback = h.optJSONArray("visibility")?.d(i) ?: Double.NaN
                uvFallback = h.optJSONArray("uv_index")?.d(i) ?: Double.NaN
            }
            hours += Hour(
                time = t,
                temp = temp,
                code = (h.optJSONArray("weather_code")?.d(i) ?: 3.0).let { if (it.ok()) it.toInt() else 3 },
                isDay = (h.optJSONArray("is_day")?.d(i) ?: 1.0) == 1.0,
                precipProb = h.optJSONArray("precipitation_probability")?.d(i) ?: Double.NaN,
                precip = h.optJSONArray("precipitation")?.d(i) ?: Double.NaN,
            )
            if (hours.size >= 48) break
        }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = ArrayList<Day>()
        for (i in 0 until dt.length()) {
            val max = d.optJSONArray("temperature_2m_max")?.d(i) ?: Double.NaN
            val min = d.optJSONArray("temperature_2m_min")?.d(i) ?: Double.NaN
            if (!max.ok() || !min.ok()) continue
            days += Day(
                date = LocalDate.parse(dt.getString(i)),
                code = (d.optJSONArray("weather_code")?.d(i) ?: 3.0).let { if (it.ok()) it.toInt() else 3 },
                tMax = max,
                tMin = min,
                precipProb = d.optJSONArray("precipitation_probability_max")?.d(i) ?: Double.NaN,
                precipSum = d.optJSONArray("precipitation_sum")?.d(i) ?: Double.NaN,
                sunrise = d.optJSONArray("sunrise")?.optString(i)?.let { parseLdt(it) },
                sunset = d.optJSONArray("sunset")?.optString(i)?.let { parseLdt(it) },
                uvMax = d.optJSONArray("uv_index_max")?.d(i) ?: Double.NaN,
            )
        }
        if (hours.isEmpty() || days.isEmpty()) throw IOException("Nessun dato per questa zona")
        var fixed = current.copy(
            visibility = if (current.visibility.ok()) current.visibility else visFallback,
            uv = if (current.uv.ok()) current.uv else uvFallback,
        )
        var fixedDays: List<Day> = days
        // Alcuni modelli (es. ECMWF) non danno UV o visibilità: li prendo dalla combinazione automatica di Open-Meteo
        if (model.isNotBlank() && (!fixed.uv.ok() || !fixed.visibility.ok() || days.any { !it.uvMax.ok() })) {
            try {
                val e = JSONObject(Net.get(
                    "https://api.open-meteo.com/v1/forecast?latitude=${fmt(place.lat)}&longitude=${fmt(place.lon)}" +
                        "&current=uv_index,visibility&daily=uv_index_max&timezone=auto&forecast_days=16"
                ))
                val ec = e.getJSONObject("current")
                fixed = fixed.copy(
                    uv = if (fixed.uv.ok()) fixed.uv else ec.d("uv_index"),
                    visibility = if (fixed.visibility.ok()) fixed.visibility else ec.d("visibility"),
                )
                val ed = e.getJSONObject("daily")
                val et = ed.getJSONArray("time")
                val uvByDate = (0 until et.length()).associate { LocalDate.parse(et.getString(it)) to (ed.optJSONArray("uv_index_max")?.d(it) ?: Double.NaN) }
                fixedDays = days.map { d -> if (d.uvMax.ok()) d else d.copy(uvMax = uvByDate[d.date] ?: Double.NaN) }
            } catch (ex: Exception) { }
        }
        return Parsed(Forecast(fixed, hours, fixedDays, label, System.currentTimeMillis(), now), off)
    }

    /** MET Norway (YR.no): temperatura, cielo, vento e pioggia da api.met.no; alba, UV e visibilità da Open-Meteo. */
    private fun metno(place: Place): Forecast {
        val base = openMeteo(place, "", "YR.no")
        val off = ZoneOffset.ofTotalSeconds(base.utcOffset)
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${String.format(Locale.US, "%.3f", place.lat)}" +
            "&lon=${String.format(Locale.US, "%.3f", place.lon)}"
        val o = JSONObject(Net.get(url))
        val ts = o.getJSONObject("properties").getJSONArray("timeseries")
        val b = base.forecast
        val hourStart = b.localNow.truncatedTo(ChronoUnit.HOURS)
        val hours = ArrayList<Hour>()
        val byDay = LinkedHashMap<LocalDate, MutableList<Pair<Double, Int>>>()
        val rainByDay = HashMap<LocalDate, Double>()
        var first: JSONObject? = null
        for (i in 0 until ts.length()) {
            val e = ts.getJSONObject(i)
            val t = LocalDateTime.ofInstant(Instant.parse(e.getString("time")), off)
            val data = e.getJSONObject("data")
            val det = data.getJSONObject("instant").getJSONObject("details")
            val temp = det.d("air_temperature")
            val next = data.optJSONObject("next_1_hours") ?: data.optJSONObject("next_6_hours")
            val symbol = next?.optJSONObject("summary")?.optString("symbol_code") ?: ""
            val code = if (symbol.isNotBlank()) metnoToWmo(symbol) else 3
            val rain = next?.optJSONObject("details")?.d("precipitation_amount") ?: Double.NaN
            if (!t.isBefore(hourStart)) {
                if (first == null) first = e
                if (hours.size < 48 && data.has("next_1_hours")) {
                    val isDay = b.days.firstOrNull { it.date == t.toLocalDate() }?.let { d ->
                        val sr = d.sunrise; val ss = d.sunset
                        sr == null || ss == null || (!t.isBefore(sr) && t.isBefore(ss))
                    } ?: true
                    hours += Hour(t, temp, code, isDay, Double.NaN, rain)
                }
            }
            byDay.getOrPut(t.toLocalDate()) { mutableListOf() } += temp to code
            if (data.has("next_1_hours") && rain.ok()) rainByDay[t.toLocalDate()] = (rainByDay[t.toLocalDate()] ?: 0.0) + rain
        }
        val fe = first ?: throw IOException("YR.no: nessun dato")
        val fd = fe.getJSONObject("data")
        val det = fd.getJSONObject("instant").getJSONObject("details")
        val sym = (fd.optJSONObject("next_1_hours") ?: fd.optJSONObject("next_6_hours"))
            ?.optJSONObject("summary")?.optString("symbol_code") ?: ""
        val cur = b.current.copy(
            temp = det.d("air_temperature"),
            code = if (sym.isNotBlank()) metnoToWmo(sym) else b.current.code,
            humidity = det.d("relative_humidity"),
            windSpeed = det.d("wind_speed") * 3.6,
            windDir = det.d("wind_from_direction"),
            pressure = det.d("air_pressure_at_sea_level"),
        )
        val days = b.days.map { d ->
            val list = byDay[d.date]
            if (list == null || list.size < 3) d else {
                val temps = list.map { it.first }.filter { it.ok() }
                val worst = list.map { it.second }.maxByOrNull { severity(it) } ?: d.code
                d.copy(
                    tMax = temps.maxOrNull() ?: d.tMax,
                    tMin = temps.minOrNull() ?: d.tMin,
                    code = worst,
                    precipProb = Double.NaN,
                    precipSum = rainByDay[d.date] ?: d.precipSum,
                )
            }
        }.filter { byDay.containsKey(it.date) }
        return Forecast(cur, if (hours.isEmpty()) b.hours else hours, days.ifEmpty { b.days }, "YR.no", System.currentTimeMillis(), b.localNow)
    }

    private fun severity(code: Int) = when (skyOf(code)) {
        Sky.CLEAR -> 0; Sky.PARTLY -> 1; Sky.CLOUDY -> 2; Sky.FOG -> 3
        Sky.DRIZZLE -> 4; Sky.RAIN -> 5; Sky.SNOW -> 6; Sky.STORM -> 7
    }

    /** Media delle fonti principali: temperature mediate, cielo = valore più frequente. */
    private suspend fun consensus(place: Place): Forecast = coroutineScope {
        val members = Sources.consensusMembers.map { Sources.byId(it) }
        val results = members.map { s ->
            async(Dispatchers.IO) {
                try {
                    if (s.kind == SourceKind.METNO) metno(place) else openMeteo(place, s.model, s.short).forecast
                } catch (e: Exception) { null }
            }
        }.awaitAll().filterNotNull()
        if (results.isEmpty()) throw IOException("Nessuna fonte disponibile")
        val base = results.first()
        fun avg(xs: List<Double>) = xs.filter { it.ok() }.let { if (it.isEmpty()) Double.NaN else it.average() }
        fun mode(xs: List<Int>) = xs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 3
        val cur = base.current.copy(
            temp = avg(results.map { it.current.temp }),
            feels = avg(results.map { it.current.feels }),
            code = mode(results.map { it.current.code }),
            humidity = avg(results.map { it.current.humidity }),
            windSpeed = avg(results.map { it.current.windSpeed }),
            pressure = avg(results.map { it.current.pressure }),
        )
        val hours = base.hours.map { h ->
            val same = results.mapNotNull { r -> r.hours.firstOrNull { it.time == h.time } }
            h.copy(
                temp = avg(same.map { it.temp }),
                code = mode(same.map { it.code }),
                precipProb = avg(same.map { it.precipProb }),
                precip = avg(same.map { it.precip }),
            )
        }
        val days = base.days.map { d ->
            val same = results.mapNotNull { r -> r.days.firstOrNull { it.date == d.date } }
            d.copy(
                tMax = avg(same.map { it.tMax }),
                tMin = avg(same.map { it.tMin }),
                code = mode(same.map { it.code }),
                precipProb = avg(same.map { it.precipProb }),
                precipSum = avg(same.map { it.precipSum }),
            )
        }
        Forecast(cur, hours, days, "Consenso (${results.size} fonti)", System.currentTimeMillis(), base.localNow)
    }

    /** Temperatura e cielo attuali di ogni fonte, per la pagina "Fonte dati". */
    suspend fun compareAll(place: Place): Map<String, Pair<Double, Int>?> = coroutineScope {
        Sources.free.map { s ->
            async(Dispatchers.IO) {
                s.id to try {
                    val f = if (s.kind == SourceKind.METNO) metno(place) else openMeteo(place, s.model, s.short, days = 1).forecast
                    f.current.temp to f.current.code
                } catch (e: Exception) { null }
            }
        }.awaitAll().toMap()
    }

    suspend fun airQuality(place: Place): AirQuality? = withContext(Dispatchers.IO) {
        try {
            val url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${fmt(place.lat)}&longitude=${fmt(place.lon)}" +
                "&current=european_aqi,pm2_5,pm10&timezone=auto"
            val c = JSONObject(Net.get(url)).getJSONObject("current")
            AirQuality(c.d("european_aqi"), c.d("pm2_5"), c.d("pm10"))
        } catch (e: Exception) { null }
    }

    suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val o = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=15&language=it&format=json"))
        val arr = o.optJSONArray("results") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val parts = listOf(r.optString("admin2"), r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() }.distinct()
            Place(
                id = "p" + r.optLong("id", System.nanoTime()),
                name = r.optString("name"),
                region = parts.joinToString(", "),
                lat = r.optDouble("latitude"),
                lon = r.optDouble("longitude"),
                cc = r.optString("country_code").lowercase(),
            )
        }
    }

    // ------------------------------------------------------------------ radar

    data class RadarFrames(val host: String, val frames: List<Pair<Long, String>>)

    suspend fun rainViewer(): RadarFrames = withContext(Dispatchers.IO) {
        val o = JSONObject(Net.get("https://api.rainviewer.com/public/weather-maps.json"))
        val host = o.optString("host", "https://tilecache.rainviewer.com")
        val past = o.getJSONObject("radar").getJSONArray("past")
        RadarFrames(host, (0 until past.length()).map { i ->
            val f = past.getJSONObject(i)
            f.getLong("time") to f.getString("path")
        })
    }

    /** Griglia di punti per la Mappa MLMeteo. values[layer][punto][ora] */
    class Grid(
        val lats: List<Double>,
        val lons: List<Double>,
        val times: List<LocalDateTime>,
        val values: Map<String, List<DoubleArray>>,
    )

    suspend fun grid(
        south: Double, north: Double, west: Double, east: Double, n: Int, model: String,
    ): Grid = withContext(Dispatchers.IO) {
        val lats = (0 until n).map { south + (north - south) * it / (n - 1) }
        val lons = (0 until n).map { west + (east - west) * it / (n - 1) }
        val la = ArrayList<String>(); val lo = ArrayList<String>()
        for (y in lats) for (x in lons) { la += String.format(Locale.US, "%.3f", y); lo += String.format(Locale.US, "%.3f", x) }
        val vars = listOf("precipitation", "temperature_2m", "cloud_cover", "wind_speed_10m", "wind_gusts_10m", "snowfall")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${la.joinToString(",")}&longitude=${lo.joinToString(",")}" +
            "&hourly=${vars.joinToString(",")}&forecast_days=3&timezone=auto&models=$model"
        val body = Net.get(url, 30000)
        val arr = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
        var times: List<LocalDateTime> = emptyList()
        val values = vars.associateWith { ArrayList<DoubleArray>() }
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i).getJSONObject("hourly")
            if (times.isEmpty()) {
                val t = h.getJSONArray("time")
                times = (0 until t.length()).mapNotNull { parseLdt(t.getString(it)) }
            }
            for (v in vars) {
                val a = h.optJSONArray(v)
                values[v]!!.add(DoubleArray(times.size) { a?.d(it) ?: Double.NaN })
            }
        }
        Grid(lats, lons, times, values)
    }
}

/** Nome, regione e paese dalle coordinate (OpenStreetMap Nominatim), se il telefono non ha il geocoder. */
fun reverseName(lat: Double, lon: Double): Triple<String, String, String>? = try {
    val o = JSONObject(Net.get(
        "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&accept-language=it" +
            "&lat=${String.format(Locale.US, "%.4f", lat)}&lon=${String.format(Locale.US, "%.4f", lon)}"
    ))
    val a = o.optJSONObject("address")
    val name = listOf("city", "town", "village", "municipality", "county").firstNotNullOfOrNull { k ->
        a?.optString(k)?.takeIf { it.isNotBlank() }
    } ?: o.optString("name").takeIf { it.isNotBlank() }
    if (name == null) null else Triple(name, a?.optString("state") ?: "", a?.optString("country_code")?.lowercase() ?: "")
} catch (e: Exception) { null }

/** Distanza approssimata in km (per riconoscere la stessa località). */
fun roughKm(a: Place, b: Place): Double = abs(a.lat - b.lat) * 111 + abs(a.lon - b.lon) * 80
