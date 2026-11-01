package com.marco.mlmeteo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Stato condiviso dell'app (impostazioni, località, previsioni). */
object AppState {
    lateinit var app: Context
        private set

    var settings by mutableStateOf(Settings())
        private set
    var savedPlaces by mutableStateOf<List<Place>>(emptyList())
        private set
    var gpsPlace by mutableStateOf<Place?>(null)
        private set
    var gpsStatus by mutableStateOf("")

    val forecasts = mutableStateMapOf<String, Forecast>()
    val alerts = mutableStateMapOf<String, List<Alert>>()
    val air = mutableStateMapOf<String, AirQuality>()
    val errors = mutableStateMapOf<String, String>()
    val loading = mutableStateMapOf<String, Boolean>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        Icon2i.cacheDir = app.cacheDir
        Wrf.cacheDir = app.cacheDir
        Gfs.cacheDir = app.cacheDir
        AlertsRepo.cacheDir = app.cacheDir
        settings = Prefs.loadSettings(app)
        savedPlaces = Prefs.loadPlaces(app)
        gpsPlace = Prefs.loadGps(app)
        // ultimi dati salvati: l'app mostra subito qualcosa anche senza rete
        (listOfNotNull(gpsPlace) + savedPlaces).forEach { p -> ForecastStore.load(app, p.id)?.let { forecasts[p.id] = it } }
    }

    /** Località principale (quella con la campanella): riceve allerte e notifiche. */
    fun mainPlace(): Place? = places.firstOrNull { it.id == settings.mainPlaceId } ?: places.firstOrNull()

    /** Località mostrate, nell'ordine: GPS (se attivo) e poi quelle salvate. */
    val places: List<Place>
        get() = (if (settings.useGps) listOfNotNull(gpsPlace) else emptyList()) + savedPlaces

    fun update(block: (Settings) -> Settings) {
        val old = settings
        settings = block(settings)
        Prefs.saveSettings(app, settings)
        if (old.sourceId != settings.sourceId) {
            refreshAll()
        }
        if (old.updateFreq != settings.updateFreq || old.wifiOnly != settings.wifiOnly ||
            old.notifMorning != settings.notifMorning || old.morningMinutes != settings.morningMinutes
        ) Background.schedule(app)
        if (old.notifStatus != settings.notifStatus || old.mainPlaceId != settings.mainPlaceId) {
            mainPlace()?.let { p -> forecasts[p.id]?.let { Notify.statusBar(app, p, it) } } ?: Notify.cancelStatus(app)
            if (!settings.notifStatus) Notify.cancelStatus(app)
        }
        if (old.iconSet != settings.iconSet || old.mode != settings.mode || old.useWallpaper != settings.useWallpaper ||
            old.manualColor != settings.manualColor || old.tempUnit != settings.tempUnit || old.rainDisplay != settings.rainDisplay
        ) Widgets.updateAll(app)
    }

    // ------------------------------------------------------------------ località

    fun addPlace(p: Place) {
        if (savedPlaces.any { roughKm(it, p) < 1.0 }) return
        savedPlaces = savedPlaces + p
        Prefs.savePlaces(app, savedPlaces)
        refresh(p)
    }

    fun removePlace(p: Place) {
        savedPlaces = savedPlaces.filter { it.id != p.id }
        Prefs.savePlaces(app, savedPlaces)
        forecasts.remove(p.id)
        if (settings.mainPlaceId == p.id) update { it.copy(mainPlaceId = "gps") }
    }

    fun movePlace(from: Int, to: Int) {
        if (from !in savedPlaces.indices || to !in savedPlaces.indices) return
        val l = savedPlaces.toMutableList()
        l.add(to, l.removeAt(from))
        savedPlaces = l
        Prefs.savePlaces(app, savedPlaces)
    }

    // ------------------------------------------------------------------ previsioni

    fun refreshAll() = places.forEach { refresh(it) }

    fun refresh(p: Place) {
        if (loading[p.id] == true) return
        loading[p.id] = true
        val source = Sources.byId(settings.sourceId)
        scope.launch {
            try {
                val f = WeatherRepo.forecast(p, source)
                forecasts[p.id] = f
                errors.remove(p.id)
                ForecastStore.save(app, p.id, f)
                Widgets.updateAll(app)
                if (p.id == mainPlace()?.id) Notify.statusBar(app, p, f)
            } catch (e: Exception) {
                errors[p.id] = e.message ?: "Errore di rete"
            } finally {
                loading[p.id] = false
            }
            WeatherRepo.airQuality(p)?.let { air[p.id] = it }
            try { alerts[p.id] = AlertsRepo.forPlace(p) } catch (e: Exception) { }
        }
    }

    /** Se i dati hanno più di 30 minuti li riscarica. */
    fun refreshIfOld(p: Place) {
        val f = forecasts[p.id]
        if (f == null || System.currentTimeMillis() - f.fetchedAt > 30 * 60_000) refresh(p)
    }

    // ------------------------------------------------------------------ GPS

    fun hasLocationPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locate() {
        if (!settings.useGps || !hasLocationPermission(app)) return
        gpsStatus = "Ricerca posizione…"
        scope.launch {
            val loc = withTimeoutOrNull(20_000) { currentLocation() }
            if (loc == null) {
                gpsStatus = if (gpsPlace == null) "Posizione non disponibile" else ""
                gpsPlace?.let { refreshIfOld(it) }
                return@launch
            }
            val info = withContext(Dispatchers.IO) {
                cityName(loc.latitude, loc.longitude) ?: reverseName(loc.latitude, loc.longitude)
            }
            val name = info?.first ?: gpsPlace?.name?.takeIf { it != "Posizione attuale" } ?: "Posizione attuale"
            val region = info?.second?.takeIf { it.isNotBlank() } ?: gpsPlace?.region ?: "Posizione attuale"
            val cc = info?.third?.takeIf { it.isNotBlank() } ?: gpsPlace?.cc ?: ""
            val p = Place("gps", name, region, loc.latitude, loc.longitude, gps = true, cc = cc)
            val moved = gpsPlace?.let { roughKm(it, p) > 2.0 } ?: true
            gpsPlace = p
            Prefs.saveGps(app, p)
            gpsStatus = ""
            if (moved) refresh(p) else refreshIfOld(p)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 15 * 60_000) return last
        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> return last
        }
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(app)) { l ->
                            if (cont.isActive) cont.resume(l)
                        }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (cont.isActive) cont.resume(location)
                            }
                        }
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        return fresh ?: last
    }

    private fun cityName(lat: Double, lon: Double): Triple<String, String, String>? = try {
        if (!Geocoder.isPresent()) null else {
            @Suppress("DEPRECATION")
            val list = Geocoder(app, Locale.ITALY).getFromLocation(lat, lon, 1)
            val a = list?.firstOrNull()
            val n = a?.locality ?: a?.subAdminArea
            if (n == null) null else Triple(n, a?.adminArea ?: "", a?.countryCode?.lowercase() ?: "")
        }
    } catch (e: Exception) { null }
}
