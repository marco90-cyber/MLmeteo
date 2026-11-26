package com.marco.mlmeteo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fonti che usano la chiave API personale: OpenWeatherMap, WeatherAPI.com, AccuWeather,
 * Weatherbit, HERE e Tomorrow.io.
 * Alba, tramonto, UV e visibilità mancanti vengono presi da Open-Meteo (come per YR.no).
 */
object KeyedSources {

    /** Un punto orario (o a 3 ore) restituito da una fonte. */
    private class P(
        val t: LocalDateTime,
        val temp: Double,
        val code: Int,
        val isDay: Boolean? = null,
        val pop: Double = Double.NaN,
        val precip: Double = Double.NaN,
        val wind: Double = Double.NaN,
        val dir: Double = Double.NaN,
        val gust: Double = Double.NaN,
    )

    private val accuLocations = HashMap<String, String>()

    fun fetch(place: Place, source: Source, key: String): Forecast {
        if (key.isBlank()) throw IOException("Inserisci la chiave API di ${source.name} nelle impostazioni")
        try {
            return when (source.id) {
                "owm" -> owm(place, key.trim(), source.name)
                "weatherapi" -> weatherApi(place, key.trim(), source.name)
                "accuweather" -> accu(place, key.trim(), source.name)
                "weatherbit" -> weatherbit(place, key.trim(), source.name)
                "here" -> here(place, key.trim(), source.name)
                "tomorrow" -> tomorrow(place, key.trim(), source.name)
                else -> throw IOException("Fonte non supportata")
            }
        } catch (e: IOException) {
            val m = e.message ?: ""
            throw when {
                m.contains("401") || m.contains("403") -> IOException("Chiave API di ${source.name} non valida o non ancora attiva")
                m.contains("429") -> IOException("Limite di richieste raggiunto per ${source.name}")
                else -> e
            }
        }
    }

    // ------------------------------------------------------------------ utilità

    private fun JSONObject.num(k: String): Double = if (has(k) && !isNull(k)) optDouble(k) else Double.NaN
    private fun JSONArray.num(i: Int): Double = if (i < length() && !isNull(i)) optDouble(i) else Double.NaN

    /** Numero in un oggetto annidato: path("Temperature", "Metric", "Value"). */
    private fun JSONObject.n(vararg path: String): Double {
        var o: JSONObject = this
        for (i in 0 until path.size - 1) o = o.optJSONObject(path[i]) ?: return Double.NaN
        return o.num(path.last())
    }

    private fun ll(place: Place) = String.format(Locale.US, "%.4f", place.lat) + "," + String.format(Locale.US, "%.4f", place.lon)
    private fun lat(place: Place) = String.format(Locale.US, "%.4f", place.lat)
    private fun lon(place: Place) = String.format(Locale.US, "%.4f", place.lon)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun ms(v: Double) = if (v.ok()) v * 3.6 else Double.NaN
    private fun km(v: Double) = if (v.ok()) v * 1000.0 else Double.NaN
    private fun local(epochSec: Long, off: ZoneOffset): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), off)

    private fun sev(code: Int) = when (skyOf(code)) {
        Sky.CLEAR -> 0; Sky.PARTLY -> 1; Sky.CLOUDY -> 2; Sky.FOG -> 3
        Sky.DRIZZLE -> 4; Sky.RAIN -> 5; Sky.SNOW -> 6; Sky.STORM -> 7
    }

    private fun fillCurrent(c: Current, b: Current) = c.copy(
        feels = if (c.feels.ok()) c.feels else c.temp,
        humidity = if (c.humidity.ok()) c.humidity else b.humidity,
        dewPoint = if (c.dewPoint.ok()) c.dewPoint else b.dewPoint,
        windSpeed = if (c.windSpeed.ok()) c.windSpeed else b.windSpeed,
        windDir = if (c.windDir.ok()) c.windDir else b.windDir,
        windGusts = if (c.windGusts.ok()) c.windGusts else b.windGusts,
        pressure = if (c.pressure.ok()) c.pressure else b.pressure,
        visibility = if (c.visibility.ok()) c.visibility else b.visibility,
        uv = if (c.uv.ok()) c.uv else b.uv,
        precip = if (c.precip.ok()) c.precip else 0.0,
    )

    private fun daysFromPoints(pts: List<P>, today: LocalDate, curTemp: Double): List<Day> {
        val out = ArrayList<Day>()
        for ((date, list) in pts.groupBy { it.t.toLocalDate() }.toSortedMap()) {
            if (list.size < 4 && date != today) continue
            val temps = list.map { it.temp }.filter { it.ok() } + (if (date == today && curTemp.ok()) listOf(curTemp) else emptyList())
            if (temps.isEmpty()) continue
            val rain = list.map { it.precip }.filter { it.ok() }
            out += Day(
                date = date,
                code = list.map { it.code }.maxByOrNull { sev(it) } ?: 3,
                tMax = temps.maxOrNull() ?: Double.NaN, tMin = temps.minOrNull() ?: Double.NaN,
                precipProb = list.map { it.pop }.filter { it.ok() }.maxOrNull() ?: Double.NaN,
                precipSum = if (rain.isEmpty()) Double.NaN else rain.sum(),
                sunrise = null, sunset = null, uvMax = Double.NaN,
            )
        }
        return out
    }

    /** Unisce i dati della fonte con la base di Open-Meteo (alba, tramonto, UV, orari mancanti). */
    private fun build(base: WeatherRepo.Parsed, label: String, cur: Current, pts: List<P>, daily: List<Day>?): Forecast {
        val b = base.forecast
        val sorted = pts.filter { it.temp.ok() }.sortedBy { it.t }
        val covered = ArrayList<Hour>()
        for (h in b.hours) {
            val prev = sorted.lastOrNull { !it.t.isAfter(h.time) }
            val next = sorted.firstOrNull { !it.t.isBefore(h.time) }
            val ref: P = when {
                prev != null && next != null -> if (Duration.between(prev.t, next.t).toHours() <= 4) prev else null
                prev != null -> if (Duration.between(prev.t, h.time).toHours() < 2) prev else null
                next != null -> if (Duration.between(h.time, next.t).toHours() < 2) next else null
                else -> null
            } ?: break
            val temp = if (prev != null && next != null && next.t != prev.t) {
                val span = Duration.between(prev.t, next.t).toMinutes().toDouble()
                val f = Duration.between(prev.t, h.time).toMinutes() / span
                prev.temp + (next.temp - prev.temp) * f
            } else ref.temp
            covered += Hour(h.time, temp, ref.code, ref.isDay ?: h.isDay, ref.pop, ref.precip, ref.wind, ref.dir, ref.gust)
        }
        // se la fonte non dà dati orari (es. piano gratuito) uso quelli di Open-Meteo
        val hours = if (covered.size >= 6) covered else b.hours

        val rawDays = daily?.takeIf { it.size >= 2 } ?: daysFromPoints(sorted, b.localNow.toLocalDate(), cur.temp)
        val baseByDate = b.days.associateBy { it.date }
        val days = rawDays.map { d ->
            val bd = baseByDate[d.date]
            d.copy(
                sunrise = d.sunrise ?: bd?.sunrise,
                sunset = d.sunset ?: bd?.sunset,
                uvMax = if (d.uvMax.ok()) d.uvMax else (bd?.uvMax ?: Double.NaN),
                precipSum = if (d.precipSum.ok()) d.precipSum else (bd?.precipSum ?: Double.NaN),
            )
        }.ifEmpty { b.days }

        val fixed = fillCurrent(cur, b.current)
        return Forecast(fixed, hours, days, label, System.currentTimeMillis(), b.localNow)
    }

    // ------------------------------------------------------------------ OpenWeatherMap (API gratuita 2.5)

    private fun owm(place: Place, key: String, name: String): Forecast {
        val base = WeatherRepo.openMeteo(place, "", name)
        val off = ZoneOffset.ofTotalSeconds(base.utcOffset)
        val q = "lat=${lat(place)}&lon=${lon(place)}&units=metric&appid=${enc(key)}"
        val c = JSONObject(Net.get("https://api.openweathermap.org/data/2.5/weather?$q"))
        val f = JSONObject(Net.get("https://api.openweathermap.org/data/2.5/forecast?$q"))
        val m = c.optJSONObject("main") ?: throw IOException("OpenWeatherMap: nessun dato")
        val w = c.optJSONObject("wind")
        val wx = c.optJSONArray("weather")?.optJSONObject(0)
        val cur = Current(
            temp = m.num("temp"), feels = m.num("feels_like"),
            code = owmToWmo(wx?.optInt("id", 804) ?: 804),
            isDay = wx?.optString("icon")?.endsWith("n")?.not() ?: base.forecast.current.isDay,
            humidity = m.num("humidity"), dewPoint = Double.NaN,
            windSpeed = ms(w?.num("speed") ?: Double.NaN), windDir = w?.num("deg") ?: Double.NaN,
            windGusts = ms(w?.num("gust") ?: Double.NaN),
            pressure = m.num("pressure"), visibility = c.num("visibility"), uv = Double.NaN,
            precip = c.optJSONObject("rain")?.num("1h") ?: Double.NaN,
        )
        val list = f.optJSONArray("list") ?: JSONArray()
        val pts = ArrayList<P>()
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val mm = e.optJSONObject("main") ?: continue
            val ww = e.optJSONObject("wind")
            val rain = (e.optJSONObject("rain")?.num("3h") ?: 0.0).let { if (it.ok()) it else 0.0 } +
                (e.optJSONObject("snow")?.num("3h") ?: 0.0).let { if (it.ok()) it else 0.0 }
            pts += P(
                t = local(e.getLong("dt"), off), temp = mm.num("temp"),
                code = owmToWmo(e.optJSONArray("weather")?.optJSONObject(0)?.optInt("id", 804) ?: 804),
                isDay = e.optJSONObject("sys")?.optString("pod")?.let { it == "d" },
                pop = e.num("pop") * 100.0, precip = rain / 3.0,
                wind = ms(ww?.num("speed") ?: Double.NaN), dir = ww?.num("deg") ?: Double.NaN, gust = ms(ww?.num("gust") ?: Double.NaN),
            )
        }
        return build(base, name, cur, pts, null)
    }

    // ------------------------------------------------------------------ WeatherAPI.com

    private fun weatherApi(place: Place, key: String, name: String): Forecast {
        val base = WeatherRepo.openMeteo(place, "", name)
        val o = JSONObject(Net.get("https://api.weatherapi.com/v1/forecast.json?key=${enc(key)}&q=${ll(place)}&days=14&aqi=no&alerts=no"))
        val c = o.getJSONObject("current")
        val cur = Current(
            temp = c.num("temp_c"), feels = c.num("feelslike_c"),
            code = weatherApiToWmo(c.optJSONObject("condition")?.optInt("code", 1009) ?: 1009),
            isDay = c.optInt("is_day", 1) == 1,
            humidity = c.num("humidity"), dewPoint = c.num("dewpoint_c"),
            windSpeed = c.num("wind_kph"), windDir = c.num("wind_degree"), windGusts = c.num("gust_kph"),
            pressure = c.num("pressure_mb"), visibility = km(c.num("vis_km")), uv = c.num("uv"),
            precip = c.num("precip_mm"),
        )
        val pts = ArrayList<P>()
        val days = ArrayList<Day>()
        val fd = o.getJSONObject("forecast").getJSONArray("forecastday")
        val ampm = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
        for (i in 0 until fd.length()) {
            val d = fd.getJSONObject(i)
            val date = LocalDate.parse(d.getString("date"))
            val hs = d.optJSONArray("hour") ?: JSONArray()
            for (j in 0 until hs.length()) {
                val h = hs.getJSONObject(j)
                val t = try { LocalDateTime.parse(h.getString("time").replace(' ', 'T')) } catch (e: Exception) { continue }
                pts += P(
                    t = t, temp = h.num("temp_c"), code = weatherApiToWmo(h.optJSONObject("condition")?.optInt("code", 1009) ?: 1009),
                    isDay = h.optInt("is_day", 1) == 1,
                    pop = maxOf(h.num("chance_of_rain").let { if (it.ok()) it else 0.0 }, h.num("chance_of_snow").let { if (it.ok()) it else 0.0 }),
                    precip = h.num("precip_mm"), wind = h.num("wind_kph"), dir = h.num("wind_degree"), gust = h.num("gust_kph"),
                )
            }
            val dy = d.getJSONObject("day")
            val astro = d.optJSONObject("astro")
            fun astroTime(k: String): LocalDateTime? = try {
                LocalTime.parse(astro?.optString(k)?.trim().orEmpty(), ampm).atDate(date)
            } catch (e: Exception) { null }
            days += Day(
                date = date, code = weatherApiToWmo(dy.optJSONObject("condition")?.optInt("code", 1009) ?: 1009),
                tMax = dy.num("maxtemp_c"), tMin = dy.num("mintemp_c"),
                precipProb = maxOf(dy.num("daily_chance_of_rain").let { if (it.ok()) it else 0.0 }, dy.num("daily_chance_of_snow").let { if (it.ok()) it else 0.0 }),
                precipSum = dy.num("totalprecip_mm"),
                sunrise = astroTime("sunrise"), sunset = astroTime("sunset"), uvMax = dy.num("uv"),
            )
        }
        return build(base, name, cur, pts, days)
    }

    // ------------------------------------------------------------------ AccuWeather

    private fun accu(place: Place, key: String, name: String): Forecast {
        val base = WeatherRepo.openMeteo(place, "", name)
        val k = enc(key)
        val cacheKey = String.format(Locale.US, "%.2f,%.2f", place.lat, place.lon)
        val loc = accuLocations[cacheKey] ?: JSONObject(
            Net.get("https://dataservice.accuweather.com/locations/v1/cities/geoposition/search?apikey=$k&q=${ll(place)}")
        ).optString("Key").takeIf { it.isNotBlank() }?.also { accuLocations[cacheKey] = it }
            ?: throw IOException("AccuWeather: località non trovata")
        val cj = JSONArray(Net.get("https://dataservice.accuweather.com/currentconditions/v1/$loc?apikey=$k&details=true"))
        val c = cj.optJSONObject(0) ?: throw IOException("AccuWeather: nessun dato")
        val cur = Current(
            temp = c.n("Temperature", "Metric", "Value"), feels = c.n("RealFeelTemperature", "Metric", "Value"),
            code = accuToWmo(c.optInt("WeatherIcon", 7)), isDay = c.optBoolean("IsDayTime", base.forecast.current.isDay),
            humidity = c.num("RelativeHumidity"), dewPoint = c.n("DewPoint", "Metric", "Value"),
            windSpeed = c.n("Wind", "Speed", "Metric", "Value"), windDir = c.n("Wind", "Direction", "Degrees"),
            windGusts = c.n("WindGust", "Speed", "Metric", "Value"),
            pressure = c.n("Pressure", "Metric", "Value"), visibility = km(c.n("Visibility", "Metric", "Value")),
            uv = c.num("UVIndex"), precip = c.n("Precip1hr", "Metric", "Value"),
        )
        val pts = ArrayList<P>()
        try {
            val hj = JSONArray(Net.get("https://dataservice.accuweather.com/forecasts/v1/hourly/12hour/$loc?apikey=$k&details=true&metric=true"))
            for (i in 0 until hj.length()) {
                val h = hj.getJSONObject(i)
                pts += P(
                    t = OffsetDateTime.parse(h.getString("DateTime")).toLocalDateTime(),
                    temp = h.n("Temperature", "Value"), code = accuToWmo(h.optInt("WeatherIcon", 7)),
                    isDay = h.optBoolean("IsDaylight", true), pop = h.num("PrecipitationProbability"),
                    precip = h.n("TotalLiquid", "Value"), wind = h.n("Wind", "Speed", "Value"),
                    dir = h.n("Wind", "Direction", "Degrees"), gust = h.n("WindGust", "Speed", "Value"),
                )
            }
        } catch (e: IOException) { }
        var days: List<Day>? = null
        try {
            val dj = JSONObject(Net.get("https://dataservice.accuweather.com/forecasts/v1/daily/5day/$loc?apikey=$k&details=true&metric=true"))
            val arr = dj.getJSONArray("DailyForecasts")
            val list = ArrayList<Day>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val sun = d.optJSONObject("Sun")
                list += Day(
                    date = OffsetDateTime.parse(d.getString("Date")).toLocalDate(),
                    code = accuToWmo(d.optJSONObject("Day")?.optInt("Icon", 7) ?: 7),
                    tMax = d.n("Temperature", "Maximum", "Value"), tMin = d.n("Temperature", "Minimum", "Value"),
                    precipProb = d.n("Day", "PrecipitationProbability"), precipSum = d.n("Day", "TotalLiquid", "Value"),
                    sunrise = sun?.optString("Rise")?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).toLocalDateTime() },
                    sunset = sun?.optString("Set")?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).toLocalDateTime() },
                    uvMax = Double.NaN,
                )
            }
            days = list
        } catch (e: IOException) { }
        return build(base, name, cur, pts, days)
    }

    // ------------------------------------------------------------------ Weatherbit

    private fun weatherbit(place: Place, key: String, name: String): Forecast {
        val base = WeatherRepo.openMeteo(place, "", name)
        val off = ZoneOffset.ofTotalSeconds(base.utcOffset)
        val q = "lat=${lat(place)}&lon=${lon(place)}&units=M&key=${enc(key)}"
        val c = JSONObject(Net.get("https://api.weatherbit.io/v2.0/current?$q")).getJSONArray("data").getJSONObject(0)
        val cur = Current(
            temp = c.num("temp"), feels = c.num("app_temp"),
            code = owmToWmo(c.optJSONObject("weather")?.optInt("code", 804) ?: 804),
            isDay = c.optString("pod", "d") != "n",
            humidity = c.num("rh"), dewPoint = c.num("dewpt"),
            windSpeed = ms(c.num("wind_spd")), windDir = c.num("wind_dir"), windGusts = Double.NaN,
            pressure = c.num("pres"), visibility = km(c.num("vis")), uv = c.num("uv"), precip = c.num("precip"),
        )
        val pts = ArrayList<P>()
        // le previsioni orarie sono solo nei piani a pagamento: se mancano si usa Open-Meteo per le ore
        try {
            val arr = JSONObject(Net.get("https://api.weatherbit.io/v2.0/forecast/hourly?$q&hours=48")).getJSONArray("data")
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                pts += P(
                    t = LocalDateTime.parse(h.getString("timestamp_local")), temp = h.num("temp"),
                    code = owmToWmo(h.optJSONObject("weather")?.optInt("code", 804) ?: 804),
                    isDay = h.optString("pod", "d") != "n", pop = h.num("pop"), precip = h.num("precip"),
                    wind = ms(h.num("wind_spd")), dir = h.num("wind_dir"), gust = ms(h.num("wind_gust_spd")),
                )
            }
        } catch (e: IOException) { }
        var days: List<Day>? = null
        try {
            val arr = JSONObject(Net.get("https://api.weatherbit.io/v2.0/forecast/daily?$q&days=16")).getJSONArray("data")
            val list = ArrayList<Day>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                list += Day(
                    date = LocalDate.parse(d.getString("valid_date")),
                    code = owmToWmo(d.optJSONObject("weather")?.optInt("code", 804) ?: 804),
                    tMax = d.num("max_temp"), tMin = d.num("min_temp"), precipProb = d.num("pop"), precipSum = d.num("precip"),
                    sunrise = d.num("sunrise_ts").takeIf { it.ok() }?.let { local(it.toLong(), off) },
                    sunset = d.num("sunset_ts").takeIf { it.ok() }?.let { local(it.toLong(), off) },
                    uvMax = d.num("uv"),
                )
            }
            days = list
        } catch (e: IOException) { }
        return build(base, name, cur, pts, days)
    }

    // ------------------------------------------------------------------ HERE

    /** Gli elementi possono essere già le previsioni oppure contenerle in "forecasts". */
    private fun flat(a: JSONArray?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            val inner = e.optJSONArray("forecasts")
            if (inner != null) {
                for (j in 0 until inner.length()) {
                    val x = inner.optJSONObject(j)
                    if (x != null) out.add(x)
                }
            } else {
                out.add(e)
            }
        }
        return out
    }

    private fun hereTime(s: String): LocalDateTime? = try { OffsetDateTime.parse(s).toLocalDateTime() } catch (e: Exception) {
        try { LocalDateTime.parse(s) } catch (e2: Exception) { null }
    }

    private fun here(place: Place, key: String, name: String): Forecast {
        val base = WeatherRepo.openMeteo(place, "", name)
        val url = "https://weather.hereapi.com/v3/report?location=${ll(place)}&products=observation,forecastHourly,forecast7daysSimple" +
            "&units=metric&lang=it-IT&apiKey=${enc(key)}"
        val p = JSONObject(Net.get(url)).getJSONArray("places").getJSONObject(0)
        val ob0 = p.optJSONArray("observations")?.optJSONObject(0)
        val o = ob0?.optJSONObject("observation") ?: ob0 ?: throw IOException("HERE: nessun dato")
        val cur = Current(
            temp = o.num("temperature"), feels = o.num("comfort"),
            code = hereToWmo(o.optString("iconName")), isDay = o.optString("daylight", "D") != "N",
            humidity = o.num("humidity"), dewPoint = o.num("dewPoint"),
            windSpeed = o.num("windSpeed"), windDir = o.num("windDirection"), windGusts = Double.NaN,
            pressure = o.num("barometerPressure"), visibility = km(o.num("visibility")), uv = o.num("uvIndex"),
            precip = Double.NaN,
        )
        val pts = ArrayList<P>()
        for (h in flat(p.optJSONArray("hourlyForecasts"))) {
            val t = hereTime(h.optString("time")) ?: continue
            pts += P(
                t = t, temp = h.num("temperature"), code = hereToWmo(h.optString("iconName")),
                isDay = h.optString("daylight", "D") != "N", pop = h.num("precipitationProbability"),
                precip = (h.num("rainFall").let { if (it.ok()) it else 0.0 }) + (h.num("snowFall").let { if (it.ok()) it else 0.0 }),
                wind = h.num("windSpeed"), dir = h.num("windDirection"),
            )
        }
        val list = ArrayList<Day>()
        for (d in flat(p.optJSONArray("dailyForecasts"))) {
            val t = hereTime(d.optString("time")) ?: continue
            list += Day(
                date = t.toLocalDate(), code = hereToWmo(d.optString("iconName")),
                tMax = d.num("highTemperature"), tMin = d.num("lowTemperature"),
                precipProb = d.num("precipitationProbability"), precipSum = Double.NaN,
                sunrise = null, sunset = null, uvMax = d.num("uvIndex"),
            )
        }
        return build(base, name, cur, pts, list.filter { it.tMax.ok() && it.tMin.ok() })
    }

    // ------------------------------------------------------------------ Tomorrow.io

    private fun tomorrow(place: Place, key: String, name: String): Forecast {
        val base = WeatherRepo.openMeteo(place, "", name)
        val off = ZoneOffset.ofTotalSeconds(base.utcOffset)
        val o = JSONObject(Net.get("https://api.tomorrow.io/v4/weather/forecast?location=${ll(place)}&timesteps=1h,1d&units=metric&apikey=${enc(key)}"))
        val tl = o.getJSONObject("timelines")
        val hourly = tl.optJSONArray("hourly") ?: throw IOException("Tomorrow.io: nessun dato")
        fun at(s: String): LocalDateTime = LocalDateTime.ofInstant(Instant.parse(s), off)
        val pts = ArrayList<P>()
        for (i in 0 until hourly.length()) {
            val e = hourly.getJSONObject(i)
            val v = e.optJSONObject("values") ?: continue
            pts += P(
                t = at(e.getString("time")), temp = v.num("temperature"), code = tomorrowToWmo(v.optInt("weatherCode", 1001)),
                pop = v.num("precipitationProbability"), precip = v.num("precipitationIntensity"),
                wind = ms(v.num("windSpeed")), dir = v.num("windDirection"), gust = ms(v.num("windGust")),
            )
        }
        val v0 = hourly.getJSONObject(0).getJSONObject("values")
        val cur = Current(
            temp = v0.num("temperature"), feels = v0.num("temperatureApparent"),
            code = tomorrowToWmo(v0.optInt("weatherCode", 1001)), isDay = base.forecast.current.isDay,
            humidity = v0.num("humidity"), dewPoint = v0.num("dewPoint"),
            windSpeed = ms(v0.num("windSpeed")), windDir = v0.num("windDirection"), windGusts = ms(v0.num("windGust")),
            pressure = v0.num("pressureSeaLevel"), visibility = km(v0.num("visibility")), uv = v0.num("uvIndex"),
            precip = v0.num("precipitationIntensity"),
        )
        val list = ArrayList<Day>()
        val da = tl.optJSONArray("daily") ?: JSONArray()
        for (i in 0 until da.length()) {
            val e = da.getJSONObject(i)
            val v = e.optJSONObject("values") ?: continue
            val code = listOf("weatherCodeMax", "weatherCodeFullDay", "weatherCodeDay").map { v.num(it) }.firstOrNull { it.ok() }?.toInt() ?: 1001
            list += Day(
                date = at(e.getString("time")).toLocalDate(), code = tomorrowToWmo(code),
                tMax = v.num("temperatureMax"), tMin = v.num("temperatureMin"),
                precipProb = listOf("precipitationProbabilityMax", "precipitationProbabilityAvg").map { v.num(it) }.firstOrNull { it.ok() } ?: Double.NaN,
                precipSum = v.num("rainAccumulationSum"),
                sunrise = v.optString("sunriseTime").takeIf { it.isNotBlank() }?.let { try { at(it) } catch (e: Exception) { null } },
                sunset = v.optString("sunsetTime").takeIf { it.isNotBlank() }?.let { try { at(it) } catch (e: Exception) { null } },
                uvMax = v.num("uvIndexMax"),
            )
        }
        return build(base, name, cur, pts, list.filter { it.tMax.ok() && it.tMin.ok() })
    }

    // ------------------------------------------------------------------ codici -> WMO

    /** OpenWeatherMap e Weatherbit (codici molto simili). */
    private fun owmToWmo(id: Int): Int = when (id) {
        in 200..233 -> 95
        in 300..321 -> 53
        500 -> 61; 501 -> 63; 502, 503, 504 -> 65; 511 -> 66
        520 -> 80; 521 -> 81; 522, 531 -> 82
        600 -> 71; 601 -> 73; 602 -> 75
        611, 612, 613, 615, 616 -> 66
        620, 621, 623 -> 85; 622 -> 86
        701, 741, 751 -> 45
        781 -> 95
        800 -> 0; 801 -> 1; 802 -> 2; 803, 804 -> 3
        else -> 3
    }

    private fun weatherApiToWmo(code: Int): Int = when (code) {
        1000 -> 0; 1003 -> 2; 1006, 1009 -> 3
        1030, 1135 -> 45; 1147 -> 48
        1063, 1180, 1183 -> 61; 1186, 1189 -> 63; 1192, 1195 -> 65
        1150, 1153 -> 51; 1168, 1171 -> 56
        1198, 1201, 1204, 1207, 1069, 1072 -> 66
        1066, 1210, 1213 -> 71; 1114, 1216, 1219 -> 73; 1117, 1222, 1225 -> 75
        1237, 1261, 1264 -> 77
        1240 -> 80; 1243 -> 81; 1246 -> 82
        1249, 1252, 1255 -> 85; 1258 -> 86
        1087, 1273, 1276, 1279, 1282 -> 95
        else -> 3
    }

    private fun accuToWmo(icon: Int): Int = when (icon) {
        1, 2, 30, 31, 33 -> 0
        3, 34, 37 -> 1
        4, 5, 35, 36 -> 2
        6, 7, 8, 32, 38 -> 3
        11 -> 45
        12, 13, 14, 39, 40 -> 80
        15, 16, 17, 41, 42 -> 95
        18 -> 63
        19, 20, 21, 43 -> 71
        22, 23, 44 -> 73
        24 -> 77
        25, 26, 29 -> 66
        else -> 3
    }

    private fun hereToWmo(icon: String): Int {
        val n = icon.lowercase(Locale.ROOT)
        return when {
            n.contains("thunder") || n.contains("tstorm") -> 95
            n.contains("snow") || n.contains("flurr") -> if (n.contains("heavy")) 75 else if (n.contains("light") || n.contains("flurr")) 71 else 73
            n.contains("sleet") || n.contains("freezing") || n.contains("ice") -> 66
            n.contains("drizzle") -> 53
            n.contains("shower") -> if (n.contains("heavy")) 82 else 80
            n.contains("rain") -> if (n.contains("heavy")) 65 else if (n.contains("light")) 61 else 63
            n.contains("fog") || n.contains("mist") -> 45
            n.contains("overcast") -> 3
            n.contains("partly") -> 2
            n.contains("cloudy") -> 3
            n.contains("passing") || n.contains("mostly_sunny") || n.contains("mostly_clear") || n.contains("fair") -> 1
            n.contains("sunny") || n.contains("clear") -> 0
            else -> 3
        }
    }

    private fun tomorrowToWmo(code: Int): Int = when (code) {
        1000 -> 0; 1100 -> 1; 1101 -> 2; 1102, 1001 -> 3
        2000, 2100 -> 45
        4000 -> 51; 4200 -> 61; 4001 -> 63; 4201 -> 65
        5001, 5100 -> 71; 5000 -> 73; 5101 -> 75
        6000 -> 56; 6001, 6200 -> 66; 6201 -> 67
        7000, 7101, 7102 -> 77
        8000 -> 95
        else -> 3
    }
}
