package com.marco.mlmeteo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stato dell'app per Windows (stesso nome e stesse proprietà usate dal codice condiviso).
 * I dati restano in %APPDATA%\MLMeteo.
 */
object AppState {
    val dir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "MLMeteo").apply { mkdirs() }
    private val cache = File(dir, "cache").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var settings by mutableStateOf(Settings(uiStyle = "fluent", useWallpaper = true, useGps = false, mainPlaceId = ""))
        private set
    /** Impostazioni solo per Windows. */
    var closeToTray by mutableStateOf(true)
    var autostart by mutableStateOf(false)
    var zoom by mutableStateOf(100)

    val places = mutableStateListOf<Place>()
    val forecasts = mutableStateMapOf<String, Forecast>()
    val air = mutableStateMapOf<String, AirQuality>()
    val alerts = mutableStateMapOf<String, List<Alert>>()
    val errors = mutableStateMapOf<String, String>()
    var loading by mutableStateOf(false)
    var selectedId by mutableStateOf("")

    fun init() {
        AlertsRepo.cacheDir = cache
        load()
        if (selectedId.isBlank()) selectedId = mainPlace()?.id ?: ""
    }

    fun mainPlace(): Place? = places.firstOrNull { it.id == settings.mainPlaceId } ?: places.firstOrNull()
    fun selected(): Place? = places.firstOrNull { it.id == selectedId } ?: mainPlace()

    fun update(f: (Settings) -> Settings) {
        val old = settings
        settings = f(settings)
        save()
        if (old.sourceId != settings.sourceId) refreshAll(force = true)
    }

    // ------------------------------------------------------------------ dati

    fun refresh(p: Place, force: Boolean = false) {
        val f = forecasts[p.id]
        if (!force && f != null && System.currentTimeMillis() - f.fetchedAt < 20 * 60_000) return
        scope.launch {
            loading = true
            try {
                forecasts[p.id] = WeatherRepo.forecast(p, Sources.byId(settings.sourceId))
                errors.remove(p.id)
                saveForecastStamp()
            } catch (e: Exception) {
                errors[p.id] = e.message ?: "Errore"
            }
            loading = false
            try { WeatherRepo.airQuality(p)?.let { air[p.id] = it } } catch (e: Exception) { }
            try { alerts[p.id] = AlertsRepo.forPlace(p) } catch (e: Exception) { }
            Desktop.onDataChanged()
        }
    }

    fun refreshAll(force: Boolean = false) = places.toList().forEach { refresh(it, force) }

    // ------------------------------------------------------------------ località

    fun addPlace(p: Place) {
        if (places.any { it.id == p.id }) return
        places.add(p)
        if (places.size == 1) settings = settings.copy(mainPlaceId = p.id)
        selectedId = p.id
        save()
        refresh(p, true)
    }

    fun removePlace(p: Place) {
        places.removeAll { it.id == p.id }
        forecasts.remove(p.id)
        if (settings.mainPlaceId == p.id) settings = settings.copy(mainPlaceId = places.firstOrNull()?.id ?: "")
        if (selectedId == p.id) selectedId = mainPlace()?.id ?: ""
        save()
    }

    fun movePlace(from: Int, to: Int) {
        if (from !in places.indices || to !in places.indices) return
        val p = places.removeAt(from)
        places.add(to, p)
        save()
    }

    fun setMain(p: Place) = update { it.copy(mainPlaceId = p.id) }

    // ------------------------------------------------------------------ salvataggio

    private val file get() = File(dir, "impostazioni.json")

    private fun saveForecastStamp() {}

    fun save() {
        val s = settings
        val o = JSONObject()
            .put("sourceId", s.sourceId).put("iconSet", s.iconSet).put("mode", s.mode).put("uiStyle", s.uiStyle)
            .put("useWallpaper", s.useWallpaper).put("manualColor", s.manualColor).put("animations", s.animations)
            .put("tempUnit", s.tempUnit).put("windUnit", s.windUnit).put("rainUnit", s.rainUnit)
            .put("pressureUnit", s.pressureUnit).put("visUnit", s.visUnit).put("clock24", s.clock24)
            .put("daysCount", s.daysCount).put("mainPlaceId", s.mainPlaceId).put("notifAlerts", s.notifAlerts)
            .put("notifRain", s.notifRain).put("updateFreq", s.updateFreq).put("radarModel", s.radarModel)
            .put("closeToTray", closeToTray).put("autostart", autostart).put("zoom", zoom).put("selectedId", selectedId)
            .put("hidden", JSONArray(s.hidden.toList())).put("homeOrder", JSONArray(s.homeOrder))
            .put("places", JSONArray().apply {
                places.forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name).put("region", p.region).put("lat", p.lat).put("lon", p.lon).put("cc", p.cc))
                }
            })
        try { file.writeText(o.toString(2)) } catch (e: Exception) { }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            val d = settings
            settings = d.copy(
                sourceId = o.optString("sourceId", d.sourceId),
                iconSet = validIconSet(o.optInt("iconSet", d.iconSet)),
                mode = o.optString("mode", d.mode),
                uiStyle = o.optString("uiStyle", d.uiStyle),
                useWallpaper = o.optBoolean("useWallpaper", d.useWallpaper),
                manualColor = o.optLong("manualColor", d.manualColor),
                animations = o.optString("animations", d.animations),
                tempUnit = o.optString("tempUnit", d.tempUnit),
                windUnit = o.optString("windUnit", d.windUnit),
                rainUnit = o.optString("rainUnit", d.rainUnit),
                pressureUnit = o.optString("pressureUnit", d.pressureUnit),
                visUnit = o.optString("visUnit", d.visUnit),
                clock24 = o.optBoolean("clock24", d.clock24),
                daysCount = o.optInt("daysCount", d.daysCount),
                mainPlaceId = o.optString("mainPlaceId", d.mainPlaceId),
                notifAlerts = o.optBoolean("notifAlerts", d.notifAlerts),
                notifRain = o.optBoolean("notifRain", d.notifRain),
                updateFreq = o.optInt("updateFreq", d.updateFreq),
                radarModel = o.optString("radarModel", d.radarModel),
                hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: d.hidden,
                homeOrder = o.optJSONArray("homeOrder")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: d.homeOrder,
            )
            closeToTray = o.optBoolean("closeToTray", true)
            autostart = o.optBoolean("autostart", false)
            zoom = o.optInt("zoom", 100)
            selectedId = o.optString("selectedId", "")
            o.optJSONArray("places")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    places.add(Place(p.getString("id"), p.getString("name"), p.optString("region"), p.getDouble("lat"), p.getDouble("lon"), cc = p.optString("cc")))
                }
            }
        } catch (e: Exception) { }
    }
}
