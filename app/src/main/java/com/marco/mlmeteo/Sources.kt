package com.marco.mlmeteo

enum class SourceKind { OPEN_METEO, METNO, CONSENSUS, API_KEY }

/**
 * Una fonte dati selezionabile.
 * [model] è il valore del parametro "models" di Open-Meteo.
 */
data class Source(
    val id: String,
    val name: String,
    val short: String,
    val area: String,
    val kind: SourceKind,
    val model: String = "",
)

object Sources {
    val consensus = Source("consensus", "Consenso", "Consenso", "Media di tutte le fonti gratuite", SourceKind.CONSENSUS)

    val free = listOf(
        Source("ecmwf", "ECMWF (Centro Europeo)", "ECMWF", "Mondo · IFS 0,25°", SourceKind.OPEN_METEO, "ecmwf_ifs025"),
        Source("ecmwf_aifs", "ECMWF AIFS (IA)", "ECMWF AIFS", "Mondo · modello con intelligenza artificiale", SourceKind.OPEN_METEO, "ecmwf_aifs025_single"),
        Source("icon", "DWD ICON (Germania)", "ICON", "Mondo · più preciso in Europa", SourceKind.OPEN_METEO, "icon_seamless"),
        Source("arpae", "ItaliaMeteo ARPAE (Italia)", "ARPAE", "Italia · 2 km", SourceKind.OPEN_METEO, "italia_meteo_arpae_icon_2i"),
        Source("meteofrance", "Météo-France", "Météo-France", "Mondo · AROME in Francia", SourceKind.OPEN_METEO, "meteofrance_seamless"),
        Source("ukmo", "UK Met Office", "Met Office", "Mondo · 10 km", SourceKind.OPEN_METEO, "ukmo_seamless"),
        Source("gfs", "NOAA GFS (USA)", "GFS", "Mondo", SourceKind.OPEN_METEO, "gfs_seamless"),
        Source("meteoswiss", "MeteoSwiss ICON", "MeteoSwiss", "Svizzera e Alpi", SourceKind.OPEN_METEO, "meteoswiss_icon_seamless"),
        Source("knmi", "KNMI (Paesi Bassi)", "KNMI", "Europa · con ECMWF altrove", SourceKind.OPEN_METEO, "knmi_seamless"),
        Source("dmi", "DMI (Danimarca)", "DMI", "Europa · con ECMWF altrove", SourceKind.OPEN_METEO, "dmi_seamless"),
        Source("metno", "MET Norway (YR.no)", "YR.no", "Mondo · servizio meteo norvegese", SourceKind.METNO),
        Source("jma", "JMA (Giappone)", "JMA", "Mondo", SourceKind.OPEN_METEO, "jma_seamless"),
        Source("kma", "KMA (Corea)", "KMA", "Mondo", SourceKind.OPEN_METEO, "kma_seamless"),
        Source("cma", "CMA (Cina)", "CMA", "Mondo", SourceKind.OPEN_METEO, "cma_grapes_global"),
        Source("gem", "GEM (Canada)", "GEM", "Mondo", SourceKind.OPEN_METEO, "gem_seamless"),
    )

    /** Fonti che richiedono una chiave personale (arriveranno in una prossima versione). */
    val keyed = listOf(
        Source("owm", "OpenWeatherMap", "OWM", "Richiede chiave API", SourceKind.API_KEY),
        Source("weatherapi", "WeatherAPI.com", "WeatherAPI", "Richiede chiave API", SourceKind.API_KEY),
        Source("accuweather", "AccuWeather", "AccuWeather", "Richiede chiave API", SourceKind.API_KEY),
        Source("weatherbit", "Weatherbit", "Weatherbit", "Richiede chiave API", SourceKind.API_KEY),
        Source("here", "HERE", "HERE", "Richiede chiave API", SourceKind.API_KEY),
        Source("tomorrow", "Tomorrow.io", "Tomorrow.io", "Richiede chiave API", SourceKind.API_KEY),
    )

    /** Fonti usate per calcolare il Consenso (globali, veloci). */
    val consensusMembers = listOf("ecmwf", "icon", "meteofrance", "ukmo", "gfs", "metno")

    fun byId(id: String): Source = (listOf(consensus) + free + keyed).firstOrNull { it.id == id } ?: free.first()
}

enum class RadarSource(val id: String, val title: String, val info: String, val available: Boolean) {
    AUTO("auto", "Automatico", "In Italia il radar della Protezione Civile con il previsto a 60 minuti, fuori dall'Italia RainViewer. Puoi passare alla previsione dei modelli.", true),
    DPC("dpc", "Protezione Civile (Italia)", "Radar nazionale dettagliato (1 km) ogni 10 minuti, ultima ora più il radar previsto per i prossimi 60 minuti.", true),
    RAINVIEWER("rainviewer", "RainViewer (attuale e storico)", "Radar reale di tutto il mondo, ultime 2 ore. Meno dettagliato.", true),
    MLMAP("mlmap", "Mappa MLMeteo (attuale e previsione)", "ICON-2I a 2,2 km sull'Italia per le prossime ore (dettagliata come le cartine dei siti meteo), oppure ICON ed ECMWF per i giorni successivi. Pioggia, temperatura, vento, raffiche, nuvole, neve e fulmini previsti.", true),
    OWM("owm", "OpenWeatherMap (con chiave)", "Strati extra con la tua chiave API. In arrivo in una prossima versione.", false);

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
