package com.marco.mlmeteo

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Frame del radar RainViewer, condivisi tra Home e schermata Radar. */
object RadarCache {
    var frames by mutableStateOf<WeatherRepo.RadarFrames?>(null)
    var loadedAt = 0L
    suspend fun ensure() {
        if (frames != null && System.currentTimeMillis() - loadedAt < 10 * 60_000) return
        try {
            frames = WeatherRepo.rainViewer()
            loadedAt = System.currentTimeMillis()
        } catch (e: Exception) { }
    }
}

fun rainViewerLayer(host: String, path: String) = TileLayer(7, 0.8f) { z, x, y -> "$host$path/256/$z/$x/$y/2/1_1.png" }

// ------------------------------------------------------------------ scale di colore

private fun scale(v: Double, stops: List<Pair<Double, Color>>): Color {
    if (v <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (a, ca) = stops[i - 1]
        val (b, cb) = stops[i]
        if (v <= b) return lerp(ca, cb, ((v - a) / (b - a)).toFloat())
    }
    return stops.last().second
}

enum class MapLayer(val key: String, val label: String, val unit: String) {
    RAIN("precipitation", "Pioggia", "mm/h"),
    TEMP("temperature_2m", "Temperatura", "°C"),
    CLOUD("cloud_cover", "Nuvole", "%"),
    WIND("wind_speed_10m", "Vento", "km/h");

    val stops: List<Pair<Double, Color>>
        get() = when (this) {
            RAIN -> listOf(
                0.05 to Color(0x0029B6F6), 0.2 to Color(0x9981D4FA), 1.0 to Color(0xCC29B6F6), 2.0 to Color(0xCC66BB6A),
                4.0 to Color(0xDDFFEE58), 8.0 to Color(0xDDFFA726), 16.0 to Color(0xDDEF5350), 32.0 to Color(0xDDAB47BC),
            )
            TEMP -> listOf(
                -15.0 to Color(0xAA5E35B1), -5.0 to Color(0xAA3F51B5), 0.0 to Color(0xAA42A5F5), 10.0 to Color(0xAA66BB6A),
                20.0 to Color(0xAAFFEE58), 30.0 to Color(0xAAFF7043), 40.0 to Color(0xAAB71C1C),
            )
            CLOUD -> listOf(0.0 to Color(0x00FFFFFF), 30.0 to Color(0x33FFFFFF), 70.0 to Color(0x88E0E0E0), 100.0 to Color(0xBBF5F5F5))
            WIND -> listOf(
                0.0 to Color(0x6680DEEA), 15.0 to Color(0x9966BB6A), 30.0 to Color(0xAAFFEE58), 50.0 to Color(0xBBFF7043),
                80.0 to Color(0xCCAB47BC),
            )
        }
}

// ------------------------------------------------------------------ anteprima in Home

@Composable
fun RadarPreview(place: Place) {
    val c = LocalAppColors.current
    LaunchedEffect(Unit) { RadarCache.ensure() }
    val state = remember(place.id) { MapState(place.lat, place.lon, 6.0) }
    val fr = RadarCache.frames
    val layers = buildList {
        add(baseLayer(c.dark))
        fr?.frames?.lastOrNull()?.let { add(rainViewerLayer(fr.host, it.second)) }
    }
    TileMap(state, layers, interactive = false) { project, _ ->
        val p = project(place.lat, place.lon)
        drawCircle(Color.White, 7.dp.toPx(), p)
        drawCircle(c.accent, 5.dp.toPx(), p)
    }
}

// ------------------------------------------------------------------ schermata Radar

@Composable
fun RadarScreen() {
    val c = LocalAppColors.current
    val s = AppState.settings
    val place = currentPlace()
    val radar = RadarSource.byId(s.radarId)
    // In Automatico si può passare da "Osservato" a "Previsione"
    var autoForecast by remember { mutableStateOf(false) }
    val showForecast = radar == RadarSource.MLMAP || (radar == RadarSource.AUTO && autoForecast)
    val state = remember { MapState(place?.lat ?: 42.0, place?.lon ?: 12.5, 6.5) }
    var playing by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }

    // radar osservato
    LaunchedEffect(Unit) { RadarCache.ensure() }
    val frames = RadarCache.frames
    var frame by remember { mutableIntStateOf(-1) }
    LaunchedEffect(frames) { frames?.let { frame = it.frames.lastIndex } }

    // mappa di previsione
    var layer by remember { mutableStateOf(MapLayer.RAIN) }
    var grid by remember { mutableStateOf<WeatherRepo.Grid?>(null) }
    var gridLoading by remember { mutableStateOf(false) }
    var gridError by remember { mutableStateOf<String?>(null) }
    var gridCenter by remember { mutableStateOf(Offset.Zero) }
    var hour by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(showForecast, s.radarModel, reload) {
        if (!showForecast) return@LaunchedEffect
        gridLoading = true
        gridError = null
        val lat = state.lat; val lon = state.lon
        try {
            grid = WeatherRepo.grid(lat - 4.5, lat + 4.5, lon - 6.5, lon + 6.5, 13, s.radarModel)
            gridCenter = Offset(lat.toFloat(), lon.toFloat())
            val now = (place?.let { AppState.forecasts[it.id]?.localNow })
            hour = grid?.times?.indexOfFirst { now != null && !it.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }?.coerceAtLeast(0) ?: 0
        } catch (e: Exception) {
            gridError = e.message ?: "Errore"
        }
        gridLoading = false
    }
    val gridImage = remember(grid, layer, hour) { grid?.let { gridBitmap(it, layer, hour) } }

    // animazione
    LaunchedEffect(playing, showForecast) {
        while (playing) {
            delay(if (showForecast) 450 else 650)
            if (showForecast) {
                val n = grid?.times?.size ?: 0
                if (n > 0) hour = (hour + 1) % n
            } else {
                val n = frames?.frames?.size ?: 0
                if (n > 0) frame = (frame + 1) % n
            }
        }
    }

    val layers = buildList {
        add(baseLayer(c.dark))
        if (!showForecast && frames != null) {
            frames.frames.getOrNull(frame)?.let { add(rainViewerLayer(frames.host, it.second)) }
            // precarica il frame successivo
            frames.frames.getOrNull((frame + 1) % frames.frames.size.coerceAtLeast(1))?.let {
                Tiles.get(rainViewerLayer(frames.host, it.second).url(
                    kotlin.math.floor(state.zoom).toInt().coerceIn(1, 7),
                    (state.x * (1 shl kotlin.math.floor(state.zoom).toInt().coerceIn(1, 7))).toInt(),
                    (state.y * (1 shl kotlin.math.floor(state.zoom).toInt().coerceIn(1, 7))).toInt(),
                ))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        TileMap(state, layers, maxZoom = if (showForecast) 10.0 else 9.0) { project, _ ->
            val g = grid
            val img = gridImage
            if (showForecast && g != null && img != null) {
                val stepLat = (g.lats.last() - g.lats.first()) / (g.lats.size - 1)
                val stepLon = (g.lons.last() - g.lons.first()) / (g.lons.size - 1)
                val tl = project(g.lats.last() + stepLat / 2, g.lons.first() - stepLon / 2)
                val br = project(g.lats.first() - stepLat / 2, g.lons.last() + stepLon / 2)
                drawImage(
                    img, IntOffset.Zero, IntSize(img.width, img.height),
                    IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                    IntSize((br.x - tl.x).roundToInt().coerceAtLeast(1), (br.y - tl.y).roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low,
                )
            }
            if (place != null) {
                val p = project(place.lat, place.lon)
                drawCircle(Color.White, 8.dp.toPx(), p)
                drawCircle(c.accent, 6.dp.toPx(), p)
            }
        }

        // barra in alto
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") { Nav.back() }
                Column(
                    Modifier.weight(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(24.dp)).background(c.card)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(place?.name ?: "Radar", color = c.ink, fontSize = 16.sp, maxLines = 1)
                    Text(radar.title, color = c.inkDim, fontSize = 12.sp, maxLines = 1)
                }
                RoundButton(Icons.Filled.Layers, "Fonte radar") { showSources = true }
            }
            if (radar == RadarSource.AUTO) {
                Box(Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally)) {
                    Segmented(listOf("obs" to "Osservato", "fc" to "Previsione"), if (autoForecast) "fc" else "obs") {
                        autoForecast = it == "fc"; playing = false
                    }
                }
            }
            if (showForecast) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapLayer.entries.forEach { l ->
                        FilterChip(selected = layer == l, onClick = { layer = l }, label = { Text(l.label) })
                    }
                    FilterChip(selected = s.radarModel == "icon_seamless", onClick = { AppState.update { it.copy(radarModel = "icon_seamless") } }, label = { Text("ICON") })
                    FilterChip(selected = s.radarModel == "ecmwf_ifs025", onClick = { AppState.update { it.copy(radarModel = "ecmwf_ifs025") } }, label = { Text("ECMWF") })
                }
            }
        }

        // pulsanti laterali
        Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton(Icons.Filled.MyLocation, "Centra") {
                place?.let { state.x = lonToX(it.lon); state.y = latToY(it.lat) }
            }
            if (showForecast) {
                val moved = abs(state.lat - gridCenter.x) > 3 || abs(state.lon - gridCenter.y) > 4
                if (moved || gridError != null) RoundButton(Icons.Filled.Refresh, "Aggiorna area") { reload++ }
            }
        }

        // barra del tempo
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                .clip(RoundedCornerShape(24.dp)).background(c.card).padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val label: String
            val count: Int
            val index: Int
            if (showForecast) {
                val g = grid
                count = g?.times?.size ?: 0
                index = hour
                label = when {
                    gridLoading -> "Calcolo della mappa…"
                    gridError != null -> "Errore: $gridError"
                    g != null && count > 0 -> g.times[hour.coerceIn(0, count - 1)]
                        .format(DateTimeFormatter.ofPattern("EEE d · HH:mm", Locale.ITALY)).replaceFirstChar { it.uppercase() } +
                        " · ${if (s.radarModel == "ecmwf_ifs025") "ECMWF" else "ICON"}"
                    else -> ""
                }
            } else {
                count = frames?.frames?.size ?: 0
                index = frame
                label = frames?.frames?.getOrNull(frame)?.let {
                    Instant.ofEpochSecond(it.first).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALY)) +
                        if (frame == count - 1) " · ultimo dato" else ""
                } ?: "Caricamento radar…"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(c.accentContainer).clickable { playing = !playing },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pausa" else "Riproduci",
                        tint = c.onAccentContainer)
                }
                Gap(12)
                Column(Modifier.weight(1f)) {
                    Text(label, color = c.ink, fontSize = 14.sp, maxLines = 1)
                    if (count > 1) {
                        Slider(
                            value = index.coerceIn(0, count - 1).toFloat(),
                            onValueChange = { v -> playing = false; if (showForecast) hour = v.roundToInt() else frame = v.roundToInt() },
                            valueRange = 0f..(count - 1).toFloat(),
                            steps = (count - 2).coerceAtLeast(0),
                        )
                    } else if (gridLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
            Legend(if (showForecast) layer else MapLayer.RAIN, showForecast)
            Text(
                "© OpenStreetMap © CARTO · " + if (showForecast) "Dati Open-Meteo" else "Rain Viewer",
                color = c.inkDim, fontSize = 10.sp,
            )
        }
    }

    if (showSources) {
        ChoiceDialog(
            "Radar",
            RadarSource.entries.map { Choice(it.id, it.title, it.info, it.available) },
            s.radarId,
            onPick = { id -> AppState.update { it.copy(radarId = id) }; playing = false; showSources = false },
            onDismiss = { showSources = false },
        )
    }
}

@Composable
private fun Legend(layer: MapLayer, forecast: Boolean) {
    val c = LocalAppColors.current
    val stops = if (forecast) layer.stops else listOf(
        0.0 to Color(0xFF81D4FA), 0.33 to Color(0xFF29B6F6), 0.55 to Color(0xFFFFEE58), 0.8 to Color(0xFFEF5350), 1.0 to Color(0xFFAB47BC),
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (forecast) "${layer.label} (${layer.unit})" else "Pioggia", color = c.inkDim, fontSize = 11.sp)
        Gap(8)
        Canvas(Modifier.weight(1f).height(8.dp)) {
            drawRoundRect(
                Brush.horizontalGradient(stops.map { it.second.copy(alpha = 1f) }),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
            )
        }
        Gap(8)
        Text(
            if (forecast) "${stops.first().first.roundToInt()}–${stops.last().first.roundToInt()}" else "debole → forte",
            color = c.inkDim, fontSize = 11.sp,
        )
    }
}

/** Disegna la griglia come piccola immagine: verrà ingrandita e sfumata sulla mappa. */
private fun gridBitmap(g: WeatherRepo.Grid, layer: MapLayer, hour: Int): ImageBitmap? {
    val vals = g.values[layer.key] ?: return null
    val ny = g.lats.size
    val nx = g.lons.size
    if (vals.size < nx * ny) return null
    val bmp = Bitmap.createBitmap(nx, ny, Bitmap.Config.ARGB_8888)
    val stops = layer.stops
    for (yi in 0 until ny) for (xi in 0 until nx) {
        val arr = vals[yi * nx + xi]
        val v = arr.getOrNull(hour) ?: Double.NaN
        val col = if (v.isNaN()) Color.Transparent else scale(v, stops)
        bmp.setPixel(xi, ny - 1 - yi, col.toArgb())
    }
    return bmp.asImageBitmap()
}
