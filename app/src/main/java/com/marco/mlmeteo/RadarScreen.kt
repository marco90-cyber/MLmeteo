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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Frame del radar RainViewer, condivisi tra Home e schermata Radar. */
object RadarCache {
    var frames by mutableStateOf<WeatherRepo.RadarFrames?>(null)
    var loadedAt = 0L
    suspend fun ensure(force: Boolean = false) {
        if (!force && frames != null && System.currentTimeMillis() - loadedAt < 10 * 60_000) return
        try {
            frames = WeatherRepo.rainViewer()
            loadedAt = System.currentTimeMillis()
        } catch (e: Exception) { }
    }
}

fun rainViewerLayer(host: String, path: String) = TileLayer(7, 0.8f, zoomBoost = 1) { z, x, y -> "$host$path/256/$z/$x/$y/2/1_1.png" }

/** Fulmini dal satellite MTG (EUMETSAT, area colpita negli ultimi minuti). [time] null = ultimo dato. */
fun lightningLayer(time: Instant?) = TileLayer(8, 0.95f) { z, x, y ->
    val world = 20037508.342789244
    val ts = 2 * world / (1 shl z)
    val minX = -world + x * ts
    val maxY = world - y * ts
    val t = time?.let { "&time=" + DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(it.epochSecond / 300 * 300)) } ?: ""
    "https://view.eumetsat.int/geoserver/wms?service=WMS&request=GetMap&version=1.3.0&layers=mtg_fd:li_afa&styles=" +
        "&crs=EPSG:3857&bbox=$minX,${maxY - ts},${minX + ts},$maxY&width=256&height=256&format=image/png&transparent=true$t"
}

// ------------------------------------------------------------------ strati della previsione

enum class MapLayer(val key: String?, val label: String, val unit: String) {
    RAIN("precipitation", "Pioggia", "mm/h"),
    TEMP("temperature_2m", "Temperatura", "°C"),
    WIND("wind_speed_10m", "Vento", "km/h"),
    GUST("wind_gusts_10m", "Raffiche", "km/h"),
    CLOUD("cloud_cover", "Nuvole", "%"),
    SNOW("snowfall", "Neve", "cm/h"),
    LPI(null, "Fulmini previsti", "J/kg");

    val stops: List<Pair<Double, Color>>
        get() = when (this) {
            RAIN -> listOf(
                0.1 to Color(0x009BE7FF), 0.2 to Color(0xAA9BE7FF), 0.5 to Color(0xCC00C8FF), 1.0 to Color(0xDD0096FF),
                2.0 to Color(0xDD00B400), 5.0 to Color(0xEE00E600), 10.0 to Color(0xEEFFFF00), 20.0 to Color(0xEEFF9600),
                40.0 to Color(0xEEFF0000), 70.0 to Color(0xEEC800FF),
            )
            TEMP -> listOf(
                -15.0 to Color(0xAA5E35B1), -5.0 to Color(0xAA3F51B5), 0.0 to Color(0xAA42A5F5), 10.0 to Color(0xAA66BB6A),
                20.0 to Color(0xAAFFEE58), 30.0 to Color(0xAAFF7043), 40.0 to Color(0xAAB71C1C),
            )
            WIND -> listOf(
                0.0 to Color(0x5580DEEA), 15.0 to Color(0x9966BB6A), 30.0 to Color(0xAAFFEE58), 50.0 to Color(0xBBFF7043),
                80.0 to Color(0xCCAB47BC),
            )
            GUST -> listOf(
                10.0 to Color(0x0080DEEA), 25.0 to Color(0x8866BB6A), 45.0 to Color(0xAAFFEE58), 65.0 to Color(0xBBFF7043),
                90.0 to Color(0xCCAB47BC), 120.0 to Color(0xDD6A1B9A),
            )
            CLOUD -> listOf(0.0 to Color(0x00FFFFFF), 30.0 to Color(0x33FFFFFF), 70.0 to Color(0x88E0E0E0), 100.0 to Color(0xBBF5F5F5))
            SNOW -> listOf(
                0.05 to Color(0x00E3F2FD), 0.2 to Color(0x99E3F2FD), 1.0 to Color(0xBB90CAF9), 2.0 to Color(0xCC7986CB),
                5.0 to Color(0xDDAB47BC),
            )
            LPI -> listOf(
                0.5 to Color(0x00FFEB3B), 1.0 to Color(0xAAFFEB3B), 5.0 to Color(0xCCFF9800), 15.0 to Color(0xDDF44336),
                40.0 to Color(0xEE9C27B0),
            )
        }
}

private fun scale(v: Double, stops: List<Pair<Double, Color>>): Color = colorAt(v, stops)

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

// ------------------------------------------------------------------ cache dei dati

/** Frame del radar della Protezione Civile (scaricati una volta, validi 5 minuti). */
object DpcCache {
    var frames by mutableStateOf<List<DpcRadar.Frame>?>(null)
    var loading by mutableStateOf(false)
    var progress by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    private var loadedAt = 0L
    suspend fun ensure(force: Boolean = false) {
        if (loading) return
        if (!force && frames != null && System.currentTimeMillis() - loadedAt < 5 * 60_000) return
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

/** Strati ICON-2I già scaricati (validi fino alla corsa successiva). */
object Icon2iCache {
    val data = HashMap<String, Pair<Long, Icon2i.Data>>()
    fun get(layer: MapLayer, hours: Int): Icon2i.Data? =
        data["$layer|$hours"]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.second
    fun put(layer: MapLayer, hours: Int, d: Icon2i.Data) { data["$layer|$hours"] = System.currentTimeMillis() to d }
}

private enum class RadarMode { DPC, RAINVIEWER, MODEL }

val MapModels = listOf(
    "icon2i" to "ICON-2I 2 km",
    "icon_seamless" to "ICON",
    "ecmwf_ifs025" to "ECMWF",
)

// ------------------------------------------------------------------ schermata Radar

@Composable
private fun FloatBox(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp), content: @Composable () -> Unit) {
    val c = LocalAppColors.current
    Box(modifier.shadow(6.dp, shape).clip(shape).background(c.card)) { content() }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier.size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape)
            .background(if (active) c.accentContainer else c.card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (active) c.onAccentContainer else c.ink) }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.accentContainer else c.card2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) c.onAccentContainer else c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RadarScreen() {
    val c = LocalAppColors.current
    val s = AppState.settings
    val scope = rememberCoroutineScope()
    val place = currentPlace()
    val radar = RadarSource.byId(s.radarId)
    val inItaly = place?.let { DpcRadar.contains(it.lat, it.lon) } ?: true
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
    var reload by remember { mutableIntStateOf(0) }

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

    // --- previsione
    var layer by remember { mutableStateOf(MapLayer.RAIN) }
    val model = s.radarModel
    val isIcon2i = model == "icon2i"
    if (!isIcon2i && layer == MapLayer.LPI) layer = MapLayer.RAIN

    // ICON-2I
    var i2 by remember { mutableStateOf<Icon2i.Data?>(null) }
    var i2Index by remember { mutableIntStateOf(0) }
    var i2Loading by remember { mutableStateOf(false) }
    var i2Progress by remember { mutableStateOf("") }
    var i2Error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mode, isIcon2i, layer, s.icon2iHours, reload) {
        if (mode != RadarMode.MODEL || !isIcon2i) return@LaunchedEffect
        val cached = Icon2iCache.get(layer, s.icon2iHours)
        if (cached != null) { i2 = cached; i2Index = i2Index.coerceIn(0, cached.times.size - 1); return@LaunchedEffect }
        i2Loading = true
        i2Error = null
        i2 = null
        try {
            val d = Icon2i.load(layer, s.icon2iHours) { done, total -> i2Progress = "Scarico ICON-2I $done/$total…" }
            Icon2iCache.put(layer, s.icon2iHours, d)
            i2 = d
            i2Index = 0
        } catch (e: Exception) {
            i2Error = e.message ?: "ICON-2I non disponibile"
        }
        i2Loading = false
        i2Progress = ""
    }
    val i2Images = remember(i2) { android.util.LruCache<Int, ImageBitmap>(4) }
    fun i2Image(k: Int): ImageBitmap? {
        val d = i2 ?: return null
        val v = d.values.getOrNull(k) ?: return null
        return i2Images.get(k) ?: Icon2i.render(v, layer).also { i2Images.put(k, it) }
    }

    // ICON / ECMWF (Open-Meteo): la griglia segue la zona visibile
    var grid by remember { mutableStateOf<WeatherRepo.Grid?>(null) }
    var gridModel by remember { mutableStateOf("") }
    var gridBounds by remember { mutableStateOf(doubleArrayOf()) }
    var gridLoading by remember { mutableStateOf(false) }
    var gridError by remember { mutableStateOf<String?>(null) }
    var hour by remember { mutableIntStateOf(0) }
    var lastFetch by remember { mutableStateOf(0L) }
    LaunchedEffect(mode, model, reload) {
        if (mode != RadarMode.MODEL || isIcon2i) return@LaunchedEffect
        if (reload > 0) { gridBounds = doubleArrayOf(); lastFetch = 0L }
        snapshotFlow { Triple(state.x, state.y, state.zoom) }.collectLatest {
            delay(900)
            if (state.viewW <= 0f) delay(500)
            val b = state.bounds()
            val g = grid
            val gb = gridBounds
            val covered = g != null && gridModel == model && gb.size == 4 &&
                b[0] >= gb[0] && b[1] <= gb[1] && b[2] >= gb[2] && b[3] <= gb[3]
            val tooCoarse = gb.size == 4 && (gb[1] - gb[0]) > 3 * (b[1] - b[0])
            if (covered && !tooCoarse) return@collectLatest
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
                val ng = WeatherRepo.grid(south, north, west, east, 15, model)
                grid = ng
                gridModel = model
                gridBounds = doubleArrayOf(south, north, west, east)
                val now = java.time.LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
                hour = when {
                    shownTime != null -> ng.times.indexOf(shownTime).takeIf { it >= 0 } ?: 0
                    else -> ng.times.indexOfFirst { !it.isBefore(now) }.coerceAtLeast(0)
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

    // --- animazione con dissolvenza
    LaunchedEffect(playing, mode, isIcon2i) {
        phase = 0f
        while (playing) {
            for (k in 1..8) {
                delay(if (mode == RadarMode.MODEL) 60 else 80)
                phase = k / 8f
            }
            when {
                mode == RadarMode.MODEL && isIcon2i -> i2?.let { if (it.times.isNotEmpty()) i2Index = (i2Index + 1) % it.times.size }
                mode == RadarMode.MODEL -> if (gridCount > 0) hour = (hour + 1) % gridCount
                mode == RadarMode.DPC -> DpcCache.frames?.let { if (it.isNotEmpty()) dpcIndex = (dpcIndex + 1) % it.size }
                else -> RadarCache.frames?.let { if (it.frames.isNotEmpty()) frame = (frame + 1) % it.frames.size }
            }
            phase = 0f
        }
    }

    // --- fulmini (solo radar)
    val lightningTime: Instant? = when (mode) {
        RadarMode.DPC -> dpc?.getOrNull(dpcIndex)?.takeIf { !it.forecast && dpcIndex != dpc?.indexOfLast { f -> !f.forecast } }
            ?.let { Instant.ofEpochMilli(it.time) }
        RadarMode.RAINVIEWER -> frames?.frames?.getOrNull(frame)?.takeIf { frame != frames?.frames?.lastIndex }?.let { Instant.ofEpochSecond(it.first) }
        else -> null
    }
    val showLightning = s.showLightning && mode != RadarMode.MODEL &&
        !(mode == RadarMode.DPC && dpc?.getOrNull(dpcIndex)?.forecast == true)

    val layers = buildList {
        add(baseLayer(c.dark))
        if (mode == RadarMode.RAINVIEWER && frames != null) {
            frames.frames.getOrNull(frame)?.let { add(rainViewerLayer(frames.host, it.second)) }
        }
        if (showLightning) add(lightningLayer(lightningTime))
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        TileMap(state, layers, maxZoom = 11.0) { project, world ->
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
            when {
                mode == RadarMode.DPC -> {
                    val a = dpcImage(dpcIndex)
                    val nextIdx = dpc?.let { if (it.isEmpty()) -1 else (dpcIndex + 1) % it.size } ?: -1
                    val b = if (phase > 0f) dpcImage(nextIdx) else null
                    if (a != null) drawRaster(a, DpcRadar.NORTH, DpcRadar.SOUTH, DpcRadar.WEST, DpcRadar.EAST, if (b != null) 1f - phase else 1f)
                    if (b != null) drawRaster(b, DpcRadar.NORTH, DpcRadar.SOUTH, DpcRadar.WEST, DpcRadar.EAST, phase)
                }
                mode == RadarMode.MODEL && isIcon2i -> {
                    val d = i2
                    if (d != null && d.times.isNotEmpty()) {
                        val n = d.times.size
                        val a = i2Image(i2Index.coerceIn(0, n - 1))
                        val b = if (phase > 0f) i2Image((i2Index + 1) % n) else null
                        val north = Icon2i.LAT1 + Icon2i.DLAT / 2; val south = Icon2i.LAT0 - Icon2i.DLAT / 2
                        val west = Icon2i.LON0 - Icon2i.DLON / 2; val east = Icon2i.LON1 + Icon2i.DLON / 2
                        if (a != null) drawRaster(a, north, south, west, east, if (b != null) 1f - phase else 1f)
                        if (b != null) drawRaster(b, north, south, west, east, phase)
                        // frecce del vento
                        val u = d.u?.getOrNull(i2Index); val v = d.v?.getOrNull(i2Index)
                        if (layer == MapLayer.WIND && u != null && v != null) {
                            val cellPx = world * Icon2i.DLON / 360.0
                            val step = (56.dp.toPx() / cellPx).roundToInt().coerceAtLeast(1)
                            val len = 18.dp.toPx()
                            var j = 0
                            while (j < Icon2i.NJ) {
                                var i = 0
                                while (i < Icon2i.NI) {
                                    val lat = Icon2i.LAT0 + j * Icon2i.DLAT
                                    val lon = Icon2i.LON0 + i * Icon2i.DLON
                                    val p = project(lat, lon)
                                    if (p.x > -20 && p.y > -20 && p.x < size.width + 20 && p.y < size.height + 20) {
                                        val uu = u[j * Icon2i.NI + i]; val vv = v[j * Icon2i.NI + i]
                                        val sp = sqrt(uu * uu + vv * vv)
                                        if (sp > 2f) {
                                            val dx = uu / sp; val dy = -vv / sp
                                            val tail = Offset(p.x - dx * len / 2, p.y - dy * len / 2)
                                            val tip = Offset(p.x + dx * len / 2, p.y + dy * len / 2)
                                            val col = if (c.dark) Color.White.copy(alpha = 0.85f) else Color(0xDD1C1B21)
                                            drawLine(col, tail, tip, 2.dp.toPx(), cap = StrokeCap.Round)
                                            val hx = -dx * 6.dp.toPx(); val hy = -dy * 6.dp.toPx()
                                            drawLine(col, tip, Offset(tip.x + hx - hy * 0.6f, tip.y + hy + hx * 0.6f), 2.dp.toPx(), cap = StrokeCap.Round)
                                            drawLine(col, tip, Offset(tip.x + hx + hy * 0.6f, tip.y + hy - hx * 0.6f), 2.dp.toPx(), cap = StrokeCap.Round)
                                        }
                                    }
                                    i += step
                                }
                                j += step
                            }
                        }
                    }
                }
                mode == RadarMode.MODEL -> {
                    val g = grid
                    if (g != null && g.lats.size > 1 && g.lons.size > 1) {
                        val stepLat = (g.lats.last() - g.lats.first()) / (g.lats.size - 1)
                        val stepLon = (g.lons.last() - g.lons.first()) / (g.lons.size - 1)
                        val n = g.lats.last() + stepLat / 20; val sLat = g.lats.first() - stepLat / 20
                        val w = g.lons.first() - stepLon / 20; val e = g.lons.last() + stepLon / 20
                        gridA?.let { drawRaster(it, n, sLat, w, e, if (gridB != null && phase > 0f) 1f - phase else 1f) }
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

        // ---------------- barra in alto
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MapButton(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") { Nav.back() }
                FloatBox(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(place?.name ?: "Radar", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            when {
                                mode == RadarMode.DPC -> "Radar Protezione Civile + previsto 60 min"
                                mode == RadarMode.RAINVIEWER -> "Radar RainViewer"
                                isIcon2i -> "Previsione ICON-2I (2,2 km)"
                                else -> "Previsione " + (MapModels.firstOrNull { it.first == model }?.second ?: "")
                            },
                            color = c.inkDim, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                }
                MapButton(Icons.Filled.Layers, "Fonte radar") { showSources = true }
            }
            if (radar == RadarSource.AUTO) {
                FloatBox(Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally), RoundedCornerShape(22.dp)) {
                    Segmented(listOf("obs" to "Radar", "fc" to "Previsione"), if (autoForecast) "fc" else "obs") {
                        autoForecast = it == "fc"; playing = false
                    }
                }
            }
        }

        // ---------------- pulsanti laterali
        Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapButton(Icons.Filled.MyLocation, "Centra") {
                place?.let { state.x = lonToX(it.lon); state.y = latToY(it.lat) }
            }
            if (mode != RadarMode.MODEL) {
                MapButton(Icons.Filled.Bolt, if (s.showLightning) "Nascondi fulmini" else "Mostra fulmini", active = s.showLightning) {
                    AppState.update { it.copy(showLightning = !it.showLightning) }
                }
            }
        }

        // ---------------- pannello in basso
        FloatBox(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (mode == RadarMode.MODEL) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapModels.forEach { (id, label) ->
                            Pill(label, model == id) { playing = false; AppState.update { it.copy(radarModel = id) } }
                        }
                        if (isIcon2i) {
                            val next = when (s.icon2iHours) { 6 -> 12; 12 -> 24; else -> 6 }
                            Pill("+${s.icon2iHours} h", false) { playing = false; AppState.update { it.copy(icon2iHours = next) } }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapLayer.entries.filter { isIcon2i || it != MapLayer.LPI }.forEach { l ->
                            Pill(l.label, layer == l) { playing = false; layer = l }
                        }
                    }
                }
                var label = ""
                var future = false
                var count = 0
                var index = 0
                var busy = false
                when {
                    mode == RadarMode.MODEL && isIcon2i -> {
                        val d = i2
                        count = d?.times?.size ?: 0
                        index = i2Index
                        busy = i2Loading
                        label = when {
                            i2Loading -> i2Progress.ifBlank { "Scarico ICON-2I…" }
                            i2Error != null -> "Errore: $i2Error"
                            d != null && count > 0 -> d.times[i2Index.coerceIn(0, count - 1)]
                                .format(DateTimeFormatter.ofPattern("EEE d · HH:mm", Locale.ITALY)).replaceFirstChar { it.uppercase() } +
                                " · corsa ${d.run.substring(8)}:00 UTC"
                            else -> ""
                        }
                        if (place != null && !Icon2i.contains(place.lat, place.lon) && !i2Loading) label = "ICON-2I copre solo l'Italia: scegli ICON o ECMWF"
                    }
                    mode == RadarMode.MODEL -> {
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
                    mode == RadarMode.DPC -> {
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
                    else -> {
                        count = frames?.frames?.size ?: 0
                        index = frame
                        label = frames?.frames?.getOrNull(frame)?.let {
                            Instant.ofEpochSecond(it.first).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ITALY)) +
                                if (frame == count - 1) " · ultimo dato" else ""
                        } ?: "Caricamento radar…"
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(c.accentContainer).clickable { playing = !playing },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pausa" else "Riproduci",
                            tint = c.onAccentContainer)
                    }
                    Gap(12)
                    Text(
                        label, color = if (future) c.accent else c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, modifier = Modifier.weight(1f),
                    )
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Gap(8)
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(c.card2).clickable {
                            playing = false
                            Tiles.clearFailed()
                            when {
                                mode == RadarMode.DPC -> scope.launch { DpcCache.ensure(force = true) }
                                mode == RadarMode.RAINVIEWER -> scope.launch { RadarCache.ensure(force = true) }
                                isIcon2i -> { Icon2iCache.data.clear(); reload++ }
                                else -> reload++
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "Ricarica", tint = c.ink) }
                }
                if (count > 1) {
                    Slider(
                        value = index.coerceIn(0, count - 1).toFloat(),
                        onValueChange = { v ->
                            playing = false
                            when {
                                mode == RadarMode.MODEL && isIcon2i -> i2Index = v.roundToInt()
                                mode == RadarMode.MODEL -> hour = v.roundToInt()
                                mode == RadarMode.DPC -> dpcIndex = v.roundToInt()
                                else -> frame = v.roundToInt()
                            }
                        },
                        valueRange = 0f..(count - 1).toFloat(),
                    )
                }
                Legend(if (mode == RadarMode.MODEL) layer else MapLayer.RAIN, mode == RadarMode.MODEL)
                if (showLightning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(14.dp))
                        Text(" Fulmini dal satellite: zone colpite negli ultimi minuti", color = c.inkDim, fontSize = 12.sp)
                    }
                }
                Text(
                    "© OpenStreetMap contributors · " + when {
                        mode == RadarMode.DPC -> "Radar: Dipartimento della Protezione Civile"
                        mode == RadarMode.RAINVIEWER -> "Rain Viewer"
                        isIcon2i -> "ICON-2I: ItaliaMeteo / ARPAE"
                        else -> "Dati Open-Meteo"
                    } + if (showLightning) " · Fulmini: © EUMETSAT" else "",
                    color = c.inkDim, fontSize = 11.sp,
                )
            }
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
    val stops = layer.stops
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (forecast) "${layer.label} (${layer.unit})" else "Pioggia", color = c.ink, fontSize = 12.sp)
        Gap(8)
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawRoundRect(
                Brush.horizontalGradient(stops.drop(1).map { it.second.copy(alpha = 1f) }),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
            )
        }
        Gap(8)
        Text(
            if (forecast) "${stops.first().first.let { if (it < 1) it.toString().replace('.', ',') else it.roundToInt().toString() }}–${stops.last().first.roundToInt()}"
            else "debole → forte",
            color = c.ink, fontSize = 12.sp,
        )
    }
}

/**
 * Disegna la griglia come immagine: i valori vengono interpolati (bilineare)
 * su una griglia 10 volte più fitta, così le zone di pioggia hanno bordi morbidi.
 */
private fun gridBitmap(g: WeatherRepo.Grid, layer: MapLayer, hour: Int): ImageBitmap? {
    val vals = g.values[layer.key ?: return null] ?: return null
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
