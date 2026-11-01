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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
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
                0.2 to Color(0x0029B6F6), 0.3 to Color(0x8881D4FA), 1.0 to Color(0xCC29B6F6), 2.0 to Color(0xCC66BB6A),
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

/** Frame del radar della Protezione Civile (scaricati una volta, validi 5 minuti). */
object DpcCache {
    var frames by mutableStateOf<List<DpcRadar.Frame>?>(null)
    var loading by mutableStateOf(false)
    var progress by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    private var loadedAt = 0L
    suspend fun ensure() {
        if (loading) return
        if (frames != null && System.currentTimeMillis() - loadedAt < 5 * 60_000) return
        loading = true
        error = null
        try {
            frames = DpcRadar.load { done, total -> progress = "Scarico il radar $done/$total…" }
            loadedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            error = e.message ?: "Radar non disponibile"
        }
        loading = false
        progress = ""
    }
}

private enum class RadarMode { DPC, RAINVIEWER, MODEL }

private val MapModels = listOf(
    "icon_seamless" to "ICON",
    "ecmwf_ifs025" to "ECMWF",
)

@Composable
fun RadarScreen() {
    val c = LocalAppColors.current
    val s = AppState.settings
    val place = currentPlace()
    val radar = RadarSource.byId(s.radarId)
    val inItaly = place?.let { DpcRadar.contains(it.lat, it.lon) } ?: true
    // In Automatico si può passare da "Radar" a "Previsione"
    var autoForecast by remember { mutableStateOf(false) }
    val mode = when (radar) {
        RadarSource.MLMAP -> RadarMode.MODEL
        RadarSource.DPC -> RadarMode.DPC
        RadarSource.RAINVIEWER -> RadarMode.RAINVIEWER
        else -> if (autoForecast) RadarMode.MODEL else if (inItaly) RadarMode.DPC else RadarMode.RAINVIEWER
    }
    val state = remember { MapState(place?.lat ?: 42.0, place?.lon ?: 12.5, 6.0) }
    var playing by remember { mutableStateOf(false) }
    var phase by remember { mutableFloatStateOf(0f) }
    var showSources by remember { mutableStateOf(false) }

    // --- radar Protezione Civile
    LaunchedEffect(mode) { if (mode == RadarMode.DPC) DpcCache.ensure() }
    val dpc = DpcCache.frames
    var dpcIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(dpc) { dpc?.let { l -> dpcIndex = l.indexOfLast { !it.forecast }.coerceAtLeast(0) } }
    val dpcImages = remember(dpc) { android.util.LruCache<Int, ImageBitmap>(4) }
    fun dpcImage(i: Int): ImageBitmap? {
        val l = dpc ?: return null
        val f = l.getOrNull(i) ?: return null
        return dpcImages.get(i) ?: DpcRadar.render(f.q).also { dpcImages.put(i, it) }
    }

    // --- RainViewer
    LaunchedEffect(Unit) { RadarCache.ensure() }
    val frames = RadarCache.frames
    var frame by remember { mutableIntStateOf(-1) }
    LaunchedEffect(frames) { frames?.let { frame = it.frames.lastIndex } }

    // --- previsione dei modelli: la griglia segue la zona visibile
    var layer by remember { mutableStateOf(MapLayer.RAIN) }
    var grid by remember { mutableStateOf<WeatherRepo.Grid?>(null) }
    var gridModel by remember { mutableStateOf("") }
    var gridBounds by remember { mutableStateOf(doubleArrayOf()) }
    var gridLoading by remember { mutableStateOf(false) }
    var gridError by remember { mutableStateOf<String?>(null) }
    var hour by remember { mutableIntStateOf(0) }
    var lastFetch by remember { mutableStateOf(0L) }
    LaunchedEffect(mode, s.radarModel) {
        if (mode != RadarMode.MODEL) return@LaunchedEffect
        snapshotFlow { Triple(state.x, state.y, state.zoom) }.collectLatest {
            delay(900)
            if (state.viewW <= 0f) delay(500)
            val b = state.bounds()
            val g = grid
            val gb = gridBounds
            val covered = g != null && gridModel == s.radarModel && gb.size == 4 &&
                b[0] >= gb[0] && b[1] <= gb[1] && b[2] >= gb[2] && b[3] <= gb[3]
            val tooCoarse = gb.size == 4 && (gb[1] - gb[0]) > 3 * (b[1] - b[0])
            if (covered && !tooCoarse) return@collectLatest
            // rispetta i limiti gratuiti di Open-Meteo: al massimo una griglia ogni 12 secondi
            val wait = 12_000 - (System.currentTimeMillis() - lastFetch)
            if (wait > 0) delay(wait)
            val latPad = (b[1] - b[0]) * 0.15
            val lonPad = (b[3] - b[2]) * 0.15
            val south = (b[0] - latPad).coerceAtLeast(-80.0)
            val north = (b[1] + latPad).coerceAtMost(80.0)
            val west = b[2] - lonPad
            val east = b[3] + lonPad
            gridLoading = true
            gridError = null
            lastFetch = System.currentTimeMillis()
            try {
                val shownTime = g?.times?.getOrNull(hour)
                val ng = WeatherRepo.grid(south, north, west, east, 15, s.radarModel)
                grid = ng
                gridModel = s.radarModel
                gridBounds = doubleArrayOf(south, north, west, east)
                val now = place?.let { AppState.forecasts[it.id]?.localNow }?.withMinute(0)?.withSecond(0)?.withNano(0)
                hour = when {
                    shownTime != null -> ng.times.indexOf(shownTime).takeIf { it >= 0 } ?: 0
                    now != null -> ng.times.indexOfFirst { !it.isBefore(now) }.coerceAtLeast(0)
                    else -> 0
                }
            } catch (e: Exception) {
                gridError = e.message ?: "Errore"
            }
            gridLoading = false
        }
    }
    val gridCount = grid?.times?.size ?: 0
    val gridA = remember(grid, layer, hour) { grid?.let { gridBitmap(it, layer, hour) } }
    val gridB = remember(grid, layer, hour) { grid?.let { if (gridCount > 0) gridBitmap(it, layer, (hour + 1) % gridCount) else null } }

    // --- animazione con dissolvenza tra un fotogramma e il successivo
    LaunchedEffect(playing, mode) {
        phase = 0f
        while (playing) {
            val steps = 8
            for (k in 1..steps) {
                delay(if (mode == RadarMode.MODEL) 60 else 80)
                phase = k / steps.toFloat()
            }
            when (mode) {
                RadarMode.MODEL -> if (gridCount > 0) hour = (hour + 1) % gridCount
                RadarMode.DPC -> dpc?.let { if (it.isNotEmpty()) dpcIndex = (dpcIndex + 1) % it.size }
                RadarMode.RAINVIEWER -> frames?.let { if (it.frames.isNotEmpty()) frame = (frame + 1) % it.frames.size }
            }
            phase = 0f
        }
    }

    val layers = buildList {
        add(baseLayer(c.dark))
        if (mode == RadarMode.RAINVIEWER && frames != null) {
            frames.frames.getOrNull(frame)?.let { add(TileLayer(7, 0.8f, zoomBoost = 1) { z, x, y -> "${frames.host}${it.second}/256/$z/$x/$y/2/1_1.png" }) }
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        TileMap(state, layers, maxZoom = 11.0) { project, _ ->
            fun drawRaster(img: ImageBitmap, n: Double, sLat: Double, w: Double, e: Double, alpha: Float) {
                val tl = project(n, w)
                val br = project(sLat, e)
                drawImage(
                    img, IntOffset.Zero, IntSize(img.width, img.height),
                    IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                    IntSize((br.x - tl.x).roundToInt().coerceAtLeast(1), (br.y - tl.y).roundToInt().coerceAtLeast(1)),
                    alpha = alpha,
                    filterQuality = FilterQuality.Low,
                )
            }
            when (mode) {
                RadarMode.DPC -> {
                    val a = dpcImage(dpcIndex)
                    val nextIdx = dpc?.let { if (it.isEmpty()) -1 else (dpcIndex + 1) % it.size } ?: -1
                    val b = if (phase > 0f) dpcImage(nextIdx) else null
                    if (a != null) drawRaster(a, DpcRadar.NORTH, DpcRadar.SOUTH, DpcRadar.WEST, DpcRadar.EAST, 1f - phase * (if (b != null) 1f else 0f))
                    if (b != null) drawRaster(b, DpcRadar.NORTH, DpcRadar.SOUTH, DpcRadar.WEST, DpcRadar.EAST, phase)
                }
                RadarMode.MODEL -> {
                    val g = grid
                    if (g != null && g.lats.size > 1 && g.lons.size > 1) {
                        val stepLat = (g.lats.last() - g.lats.first()) / (g.lats.size - 1)
                        val stepLon = (g.lons.last() - g.lons.first()) / (g.lons.size - 1)
                        val n = g.lats.last() + stepLat / 20; val sLat = g.lats.first() - stepLat / 20
                        val w = g.lons.first() - stepLon / 20; val e = g.lons.last() + stepLon / 20
                        gridA?.let { drawRaster(it, n, sLat, w, e, if (gridB != null) 1f - phase else 1f) }
                        if (phase > 0f) gridB?.let { drawRaster(it, n, sLat, w, e, phase) }
                    }
                }
                else -> {}
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
                    Text(
                        when (mode) {
                            RadarMode.DPC -> "Radar Protezione Civile + previsto 60 min"
                            RadarMode.RAINVIEWER -> "Radar RainViewer"
                            RadarMode.MODEL -> "Previsione dei modelli"
                        },
                        color = c.inkDim, fontSize = 12.sp, maxLines = 1,
                    )
                }
                RoundButton(Icons.Filled.Layers, "Fonte radar") { showSources = true }
            }
            if (radar == RadarSource.AUTO) {
                Box(Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally)) {
                    Segmented(listOf("obs" to "Radar", "fc" to "Previsione"), if (autoForecast) "fc" else "obs") {
                        autoForecast = it == "fc"; playing = false
                    }
                }
            }
            if (mode == RadarMode.MODEL) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapLayer.entries.forEach { l ->
                        FilterChip(selected = layer == l, onClick = { layer = l }, label = { Text(l.label) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapModels.forEach { (id, label) ->
                        FilterChip(selected = s.radarModel == id, onClick = { AppState.update { it.copy(radarModel = id) } }, label = { Text(label) })
                    }
                }
            }
        }

        // pulsanti laterali
        Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton(Icons.Filled.MyLocation, "Centra") {
                place?.let { state.x = lonToX(it.lon); state.y = latToY(it.lat) }
            }
        }

        // barra del tempo
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                .clip(RoundedCornerShape(24.dp)).background(c.card).padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            var label = ""
            var future = false
            var count = 0
            var index = 0
            var busy = false
            when (mode) {
                RadarMode.MODEL -> {
                    val g = grid
                    count = gridCount
                    index = hour
                    busy = gridLoading
                    label = when {
                        gridError != null -> "Errore: $gridError"
                        g != null && count > 0 -> g.times[hour.coerceIn(0, count - 1)]
                            .format(DateTimeFormatter.ofPattern("EEE d · HH:mm", Locale.ITALY)).replaceFirstChar { it.uppercase() } +
                            " · " + (MapModels.firstOrNull { it.first == gridModel }?.second ?: "")
                        gridLoading -> "Calcolo della mappa…"
                        else -> "Fermati su una zona per calcolare la mappa"
                    }
                }
                RadarMode.DPC -> {
                    val l = dpc
                    count = l?.size ?: 0
                    index = dpcIndex
                    busy = DpcCache.loading
                    val f = l?.getOrNull(dpcIndex)
                    future = f?.forecast == true
                    label = when {
                        DpcCache.loading -> DpcCache.progress.ifBlank { "Scarico il radar…" }
                        DpcCache.error != null -> "Errore: ${DpcCache.error}"
                        f != null -> Instant.ofEpochMilli(f.time).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALY)) +
                            if (f.forecast) " · previsto" else if (l?.indexOfLast { !it.forecast } == dpcIndex) " · ultimo dato" else ""
                        else -> ""
                    }
                }
                RadarMode.RAINVIEWER -> {
                    count = frames?.frames?.size ?: 0
                    index = frame
                    label = frames?.frames?.getOrNull(frame)?.let {
                        Instant.ofEpochSecond(it.first).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALY)) +
                            if (frame == count - 1) " · ultimo dato" else ""
                    } ?: "Caricamento radar…"
                }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = if (future) c.accent else c.ink, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    if (count > 1) {
                        Slider(
                            value = index.coerceIn(0, count - 1).toFloat(),
                            onValueChange = { v ->
                                playing = false
                                when (mode) {
                                    RadarMode.MODEL -> hour = v.roundToInt()
                                    RadarMode.DPC -> dpcIndex = v.roundToInt()
                                    RadarMode.RAINVIEWER -> frame = v.roundToInt()
                                }
                            },
                            valueRange = 0f..(count - 1).toFloat(),
                        )
                    }
                }
            }
            Legend(if (mode == RadarMode.MODEL) layer else MapLayer.RAIN, mode == RadarMode.MODEL)
            Text(
                "© OpenStreetMap contributors · " + when (mode) {
                    RadarMode.DPC -> "Radar: Dipartimento della Protezione Civile"
                    RadarMode.RAINVIEWER -> "Rain Viewer"
                    RadarMode.MODEL -> "Dati Open-Meteo"
                },
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

/**
 * Disegna la griglia come immagine: i valori vengono interpolati (bilineare)
 * su una griglia 10 volte più fitta, così le zone di pioggia hanno bordi morbidi.
 */
private fun gridBitmap(g: WeatherRepo.Grid, layer: MapLayer, hour: Int): ImageBitmap? {
    val vals = g.values[layer.key] ?: return null
    val ny = g.lats.size
    val nx = g.lons.size
    if (vals.size < nx * ny || nx < 2 || ny < 2) return null
    fun v(xi: Int, yi: Int): Double = vals[yi * nx + xi].getOrNull(hour) ?: Double.NaN
    val f = 10
    val w = (nx - 1) * f + 1
    val h = (ny - 1) * f + 1
    val pixels = IntArray(w * h)
    val stops = layer.stops
    for (py in 0 until h) {
        val gy = py.toDouble() / f
        val y0 = gy.toInt().coerceAtMost(ny - 2)
        val ty = gy - y0
        for (px in 0 until w) {
            val gx = px.toDouble() / f
            val x0 = gx.toInt().coerceAtMost(nx - 2)
            val tx = gx - x0
            val a = v(x0, y0); val b = v(x0 + 1, y0); val c = v(x0, y0 + 1); val d = v(x0 + 1, y0 + 1)
            val value = if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) {
                listOf(a, b, c, d).filter { !it.isNaN() }.let { l -> if (l.isEmpty()) Double.NaN else l.average() }
            } else {
                (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
            }
            // la riga 0 dell'immagine è a nord
            pixels[(h - 1 - py) * w + px] = if (value.isNaN()) 0 else scale(value, stops).toArgb()
        }
    }
    val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}
