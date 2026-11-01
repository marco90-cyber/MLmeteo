package com.marco.mlmeteo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Elementi della Home, nell'ordine predefinito. */
enum class HomeItem(val id: String, val title: String, val locked: Boolean = false, val small: Boolean = false) {
    ALERTS("alerts", "Allerte meteo"),
    MAIN("main", "Card principale", locked = true),
    SOURCE("source", "Fonte dei dati", locked = true),
    HOURS("hours", "Prossime ore"),
    GRAPH("graph", "Grafico temperatura"),
    RAINCHART("rainchart", "Grafico pioggia"),
    DAYS("days", "Prossimi giorni"),
    RADAR("radar", "Radar in miniatura"),
    HUMIDITY("humidity", "Umidità", small = true),
    WIND("wind", "Vento", small = true),
    RAIN("rain", "Pioggia", small = true),
    UV("uv", "Indice UV", small = true),
    PRESSURE("pressure", "Pressione", small = true),
    VISIBILITY("visibility", "Visibilità", small = true),
    AIR("air", "Qualità dell'aria", small = true),
    MOON("moon", "Luna", small = true),
    SUN("sun", "Sole (alba e tramonto)");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

data class Settings(
    val sourceId: String = "ecmwf",
    val radarId: String = "auto",
    val radarModel: String = "icon2i",
    val icon2iHours: Int = 12,
    val showLightning: Boolean = false,
    val useWallpaper: Boolean = true,
    val manualColor: Long = 0xFFA9A6FF,
    val mode: String = "auto",
    val weatherTint: Boolean = false,
    val iconSet: Int = 1,
    val homeOrder: List<String> = HomeItem.entries.map { it.id },
    val hidden: Set<String> = emptySet(),
    val daysCount: Int = 10,
    val tempUnit: String = "C",
    val windUnit: String = "kmh",
    val rainUnit: String = "mm",
    val pressureUnit: String = "hpa",
    val visUnit: String = "km",
    val clock24: Boolean = true,
    val rainDisplay: String = "prob",
    val useGps: Boolean = true,
    val mainPlaceId: String = "gps",
    val apiKeys: Map<String, String> = emptyMap(),
    // notifiche e aggiornamento in background
    val notifAlerts: Boolean = true,
    val alertMinLevel: Int = 1,
    val notifRain: Boolean = true,
    val notifMorning: Boolean = true,
    /** Orario della previsione del mattino, in minuti dalla mezzanotte (8:00). */
    val morningMinutes: Int = 8 * 60,
    val notifStatus: Boolean = false,
    val quietNight: Boolean = true,
    val updateFreq: Int = 60,
    val wifiOnly: Boolean = false,
) {
    /** Ordine completo: aggiunge in fondo eventuali elementi nuovi. */
    fun orderedItems(): List<HomeItem> {
        val list = homeOrder.mapNotNull { HomeItem.byId(it) }.distinct().toMutableList()
        // elementi nuovi: al loro posto predefinito
        HomeItem.entries.forEachIndexed { i, item -> if (item !in list) list.add(i.coerceAtMost(list.size), item) }
        return list
    }
    fun visible(item: HomeItem) = item.locked || item.id !in hidden
}

object Prefs {
    private const val NAME = "mlmeteo"
    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun loadSettings(ctx: Context): Settings {
        val p = sp(ctx)
        val d = Settings()
        val keys = try {
            val o = JSONObject(p.getString("apiKeys", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) { emptyMap() }
        return Settings(
            sourceId = p.getString("sourceId", d.sourceId) ?: d.sourceId,
            radarId = p.getString("radarId", d.radarId) ?: d.radarId,
            radarModel = (p.getString("radarModel", d.radarModel) ?: d.radarModel).let {
                if (it == "icon2i" || it == "wrf" || it == "icon_seamless" || it == "gfs_seamless") it else d.radarModel
            },
            icon2iHours = p.getInt("icon2iHours", d.icon2iHours),
            showLightning = p.getBoolean("showLightning", d.showLightning),
            useWallpaper = p.getBoolean("useWallpaper", d.useWallpaper),
            manualColor = p.getLong("manualColor", d.manualColor),
            mode = p.getString("mode", d.mode) ?: d.mode,
            weatherTint = p.getBoolean("weatherTint", d.weatherTint),
            iconSet = p.getInt("iconSet", d.iconSet),
            homeOrder = (p.getString("homeOrder", null)?.split(',')?.filter { it.isNotBlank() }) ?: d.homeOrder,
            hidden = (p.getString("hidden", "") ?: "").split(',').filter { it.isNotBlank() }.toSet(),
            daysCount = p.getInt("daysCount", d.daysCount),
            tempUnit = p.getString("tempUnit", d.tempUnit) ?: d.tempUnit,
            windUnit = p.getString("windUnit", d.windUnit) ?: d.windUnit,
            rainUnit = p.getString("rainUnit", d.rainUnit) ?: d.rainUnit,
            pressureUnit = p.getString("pressureUnit", d.pressureUnit) ?: d.pressureUnit,
            visUnit = p.getString("visUnit", d.visUnit) ?: d.visUnit,
            clock24 = p.getBoolean("clock24", d.clock24),
            rainDisplay = p.getString("rainDisplay", d.rainDisplay) ?: d.rainDisplay,
            useGps = p.getBoolean("useGps", d.useGps),
            mainPlaceId = p.getString("mainPlaceId", d.mainPlaceId) ?: d.mainPlaceId,
            apiKeys = keys,
            notifAlerts = p.getBoolean("notifAlerts", d.notifAlerts),
            alertMinLevel = p.getInt("alertMinLevel", d.alertMinLevel),
            notifRain = p.getBoolean("notifRain", d.notifRain),
            notifMorning = p.getBoolean("notifMorning", d.notifMorning),
            morningMinutes = p.getInt("morningMinutes", d.morningMinutes),
            notifStatus = p.getBoolean("notifStatus", d.notifStatus),
            quietNight = p.getBoolean("quietNight", d.quietNight),
            updateFreq = p.getInt("updateFreq", d.updateFreq),
            wifiOnly = p.getBoolean("wifiOnly", d.wifiOnly),
        )
    }

    fun saveSettings(ctx: Context, s: Settings) {
        val keys = JSONObject()
        s.apiKeys.forEach { (k, v) -> keys.put(k, v) }
        sp(ctx).edit()
            .putString("sourceId", s.sourceId)
            .putString("radarId", s.radarId)
            .putString("radarModel", s.radarModel)
            .putInt("icon2iHours", s.icon2iHours)
            .putBoolean("showLightning", s.showLightning)
            .putBoolean("useWallpaper", s.useWallpaper)
            .putLong("manualColor", s.manualColor)
            .putString("mode", s.mode)
            .putBoolean("weatherTint", s.weatherTint)
            .putInt("iconSet", s.iconSet)
            .putString("homeOrder", s.homeOrder.joinToString(","))
            .putString("hidden", s.hidden.joinToString(","))
            .putInt("daysCount", s.daysCount)
            .putString("tempUnit", s.tempUnit)
            .putString("windUnit", s.windUnit)
            .putString("rainUnit", s.rainUnit)
            .putString("pressureUnit", s.pressureUnit)
            .putString("visUnit", s.visUnit)
            .putBoolean("clock24", s.clock24)
            .putString("rainDisplay", s.rainDisplay)
            .putBoolean("useGps", s.useGps)
            .putString("mainPlaceId", s.mainPlaceId)
            .putString("apiKeys", keys.toString())
            .putBoolean("notifAlerts", s.notifAlerts)
            .putInt("alertMinLevel", s.alertMinLevel)
            .putBoolean("notifRain", s.notifRain)
            .putBoolean("notifMorning", s.notifMorning)
            .putInt("morningMinutes", s.morningMinutes)
            .putBoolean("notifStatus", s.notifStatus)
            .putBoolean("quietNight", s.quietNight)
            .putInt("updateFreq", s.updateFreq)
            .putBoolean("wifiOnly", s.wifiOnly)
            .apply()
    }

    fun loadPlaces(ctx: Context): List<Place> = try {
        val a = JSONArray(sp(ctx).getString("places", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(o.getString("id"), o.getString("name"), o.optString("region"), o.getDouble("lat"), o.getDouble("lon"), cc = o.optString("cc"))
        }
    } catch (e: Exception) { emptyList() }

    fun savePlaces(ctx: Context, places: List<Place>) {
        val a = JSONArray()
        places.filter { !it.gps }.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("region", it.region).put("lat", it.lat).put("lon", it.lon).put("cc", it.cc))
        }
        sp(ctx).edit().putString("places", a.toString()).apply()
    }

    fun loadGps(ctx: Context): Place? {
        val p = sp(ctx)
        if (!p.contains("gpsLat")) return null
        return Place("gps", p.getString("gpsName", "Posizione attuale") ?: "Posizione attuale",
            p.getString("gpsRegion", "Posizione attuale") ?: "Posizione attuale",
            p.getFloat("gpsLat", 0f).toDouble(), p.getFloat("gpsLon", 0f).toDouble(), gps = true, cc = p.getString("gpsCc", "") ?: "")
    }

    fun saveGps(ctx: Context, place: Place) {
        sp(ctx).edit().putFloat("gpsLat", place.lat.toFloat()).putFloat("gpsLon", place.lon.toFloat())
            .putString("gpsName", place.name).putString("gpsRegion", place.region).putString("gpsCc", place.cc).apply()
    }
}


/** Previsioni salvate su disco: l'app parte subito con gli ultimi dati, e widget e notifiche li usano. */
object ForecastStore {
    private fun dir(ctx: Context) = java.io.File(ctx.filesDir, "forecasts").apply { mkdirs() }

    fun save(ctx: Context, placeId: String, f: Forecast) {
        try { java.io.File(dir(ctx), "$placeId.json").writeText(toJson(f).toString()) } catch (e: Exception) { }
    }

    fun load(ctx: Context, placeId: String): Forecast? = try {
        val file = java.io.File(dir(ctx), "$placeId.json")
        if (file.exists()) fromJson(JSONObject(file.readText())) else null
    } catch (e: Exception) { null }

    private fun d(x: Double) = if (x.isNaN()) JSONObject.NULL else x
    private fun JSONObject.dd(k: String) = if (isNull(k)) Double.NaN else optDouble(k)

    fun toJson(f: Forecast): JSONObject {
        val c = f.current
        val cur = JSONObject().put("temp", d(c.temp)).put("feels", d(c.feels)).put("code", c.code).put("isDay", c.isDay)
            .put("humidity", d(c.humidity)).put("dew", d(c.dewPoint)).put("ws", d(c.windSpeed)).put("wd", d(c.windDir))
            .put("wg", d(c.windGusts)).put("p", d(c.pressure)).put("vis", d(c.visibility)).put("uv", d(c.uv)).put("pr", d(c.precip))
        val hours = JSONArray()
        f.hours.forEach { h ->
            hours.put(JSONObject().put("t", h.time.toString()).put("temp", d(h.temp)).put("code", h.code).put("day", h.isDay)
                .put("pp", d(h.precipProb)).put("pr", d(h.precip)))
        }
        val days = JSONArray()
        f.days.forEach { x ->
            days.put(JSONObject().put("d", x.date.toString()).put("code", x.code).put("max", d(x.tMax)).put("min", d(x.tMin))
                .put("pp", d(x.precipProb)).put("ps", d(x.precipSum)).put("sr", x.sunrise?.toString() ?: "")
                .put("ss", x.sunset?.toString() ?: "").put("uv", d(x.uvMax)))
        }
        return JSONObject().put("current", cur).put("hours", hours).put("days", days).put("source", f.sourceName)
            .put("at", f.fetchedAt).put("now", f.localNow.toString())
    }

    fun fromJson(o: JSONObject): Forecast {
        val c = o.getJSONObject("current")
        val cur = Current(c.dd("temp"), c.dd("feels"), c.optInt("code", 3), c.optBoolean("isDay", true), c.dd("humidity"),
            c.dd("dew"), c.dd("ws"), c.dd("wd"), c.dd("wg"), c.dd("p"), c.dd("vis"), c.dd("uv"), c.dd("pr"))
        val ha = o.getJSONArray("hours")
        val hours = (0 until ha.length()).map { i ->
            val h = ha.getJSONObject(i)
            Hour(java.time.LocalDateTime.parse(h.getString("t")), h.dd("temp"), h.optInt("code", 3), h.optBoolean("day", true), h.dd("pp"), h.dd("pr"))
        }
        val da = o.getJSONArray("days")
        val days = (0 until da.length()).map { i ->
            val x = da.getJSONObject(i)
            Day(java.time.LocalDate.parse(x.getString("d")), x.optInt("code", 3), x.dd("max"), x.dd("min"), x.dd("pp"), x.dd("ps"),
                x.optString("sr").takeIf { it.isNotBlank() }?.let { java.time.LocalDateTime.parse(it) },
                x.optString("ss").takeIf { it.isNotBlank() }?.let { java.time.LocalDateTime.parse(it) }, x.dd("uv"))
        }
        return Forecast(cur, hours, days, o.optString("source"), o.optLong("at"), java.time.LocalDateTime.parse(o.getString("now")))
    }
}
