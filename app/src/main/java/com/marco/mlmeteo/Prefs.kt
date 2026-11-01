package com.marco.mlmeteo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Elementi della Home, nell'ordine predefinito. */
enum class HomeItem(val id: String, val title: String, val locked: Boolean = false, val small: Boolean = false) {
    MAIN("main", "Card principale", locked = true),
    SOURCE("source", "Fonte dei dati", locked = true),
    HOURS("hours", "Prossime ore"),
    GRAPH("graph", "Grafico temperatura"),
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
    val radarModel: String = "icon_seamless",
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
) {
    /** Ordine completo: aggiunge in fondo eventuali elementi nuovi. */
    fun orderedItems(): List<HomeItem> {
        val known = homeOrder.mapNotNull { HomeItem.byId(it) }
        return known + HomeItem.entries.filter { it !in known }
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
                if (it == "icon_seamless" || it == "ecmwf_ifs025") it else d.radarModel
            },
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
        )
    }

    fun saveSettings(ctx: Context, s: Settings) {
        val keys = JSONObject()
        s.apiKeys.forEach { (k, v) -> keys.put(k, v) }
        sp(ctx).edit()
            .putString("sourceId", s.sourceId)
            .putString("radarId", s.radarId)
            .putString("radarModel", s.radarModel)
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
            .apply()
    }

    fun loadPlaces(ctx: Context): List<Place> = try {
        val a = JSONArray(sp(ctx).getString("places", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(o.getString("id"), o.getString("name"), o.optString("region"), o.getDouble("lat"), o.getDouble("lon"))
        }
    } catch (e: Exception) { emptyList() }

    fun savePlaces(ctx: Context, places: List<Place>) {
        val a = JSONArray()
        places.filter { !it.gps }.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("region", it.region).put("lat", it.lat).put("lon", it.lon))
        }
        sp(ctx).edit().putString("places", a.toString()).apply()
    }

    fun loadGps(ctx: Context): Place? {
        val p = sp(ctx)
        if (!p.contains("gpsLat")) return null
        return Place("gps", p.getString("gpsName", "Posizione attuale") ?: "Posizione attuale", "Posizione attuale",
            p.getFloat("gpsLat", 0f).toDouble(), p.getFloat("gpsLon", 0f).toDouble(), gps = true)
    }

    fun saveGps(ctx: Context, place: Place) {
        sp(ctx).edit().putFloat("gpsLat", place.lat.toFloat()).putFloat("gpsLon", place.lon.toFloat())
            .putString("gpsName", place.name).apply()
    }
}
