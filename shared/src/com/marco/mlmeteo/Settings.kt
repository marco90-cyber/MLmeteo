package com.marco.mlmeteo

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
    /** Luminosità della mappa, 30..100 %. */
    val mapBrightness: Int = 100,
    val icon2iHours: Int = 12,
    /** Animazioni della card principale: "once" (all'apertura), "always", "icons", "off". */
    val animations: String = "once",
    /** Stile dell'interfaccia: "material", "fluent", "glass". */
    val uiStyle: String = "material",
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
    /** Allerte calcolate dalla previsione: vento forte e pioggia forte. */
    val alertWind: Boolean = true,
    val alertRain: Boolean = true,
    val notifRain: Boolean = true,
    val notifMorning: Boolean = true,
    /** Orario della previsione del mattino, in minuti dalla mezzanotte (8:00). */
    val morningMinutes: Int = 8 * 60,
    val notifAfternoon: Boolean = true,
    val afternoonMinutes: Int = 13 * 60,
    val notifEvening: Boolean = true,
    val eveningMinutes: Int = 18 * 60,
    val notifNight: Boolean = true,
    val nightMinutes: Int = 22 * 60,
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

