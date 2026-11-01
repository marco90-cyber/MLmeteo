package com.marco.mlmeteo

import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun HomeScreen(onAskGps: () -> Unit) {
    val c = LocalAppColors.current
    val places = AppState.places
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        if (places.isEmpty()) {
            EmptyHome(onAskGps)
            return@Column
        }
        val pager = rememberPagerState(initialPage = Nav.page.coerceIn(0, places.size - 1)) { places.size }
        LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { Nav.page = it } }
        val place = places[pager.currentPage.coerceIn(0, places.size - 1)]
        TopBar(place, AppState.forecasts[place.id])
        if (places.size > 1) Dots(places.size, pager.currentPage)
        BackHandler(enabled = HomeEdit.editing) { HomeEdit.editing = false }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !HomeEdit.editing) { i ->
            places.getOrNull(i)?.let { PlacePage(it) }
        }
    }
}

@Composable
private fun EmptyHome(onAskGps: () -> Unit) {
    val c = LocalAppColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WeatherIcon(2, true, 120.dp)
        Spacer(Modifier.height(16.dp))
        Text("Benvenuto in MLMeteo", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (AppState.gpsStatus.isNotBlank()) AppState.gpsStatus else "Usa la posizione del telefono o aggiungi una città.",
            color = c.inkDim, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (!AppState.settings.useGps) AppState.update { it.copy(useGps = true) }
            if (AppState.hasLocationPermission(AppState.app)) AppState.locate() else onAskGps()
        }) {
            Icon(Icons.Filled.MyLocation, contentDescription = null)
            Gap(8)
            Text("Usa la mia posizione")
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { Nav.go(Screen.PLACES) }) { Text("Aggiungi una città") }
    }
}

@Composable
private fun TopBar(place: Place, f: Forecast?) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(Icons.AutoMirrored.Filled.List, "Località") { Nav.go(Screen.PLACES) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (place.gps) {
                    Icon(Icons.Filled.Navigation, contentDescription = "Posizione attuale", tint = c.accent,
                        modifier = Modifier.size(16.dp).rotate(45f))
                    Gap(4)
                }
                Text(place.name, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(c.card)
                    .clickable { Nav.go(Screen.SOURCES) }.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Fonte: " + Sources.byId(AppState.settings.sourceId).short, color = c.accent, fontSize = 13.sp)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
        }
        RoundButton(Icons.Filled.Settings, "Impostazioni") { Nav.go(Screen.SETTINGS) }
    }
}

@Composable
fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = c.ink) }
}

@Composable
private fun Dots(n: Int, sel: Int) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
        repeat(n) { i ->
            Box(
                Modifier.padding(horizontal = 3.dp).size(width = if (i == sel) 18.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape).background(if (i == sel) c.accent else c.outline)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacePage(place: Place) {
    val c = LocalAppColors.current
    val s = AppState.settings
    val f = AppState.forecasts[place.id]
    val err = AppState.errors[place.id]
    val loading = AppState.loading[place.id] == true
    LaunchedEffect(place.id) { AppState.refreshIfOld(place) }
    PullToRefreshBox(isRefreshing = loading && f != null, onRefresh = { AppState.refresh(place) }, modifier = Modifier.fillMaxSize()) {
        if (f == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (err != null && !loading) {
                    Text("Impossibile scaricare i dati", color = c.ink, fontSize = 18.sp)
                    Text(err, color = c.inkDim, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { AppState.refresh(place) }) { Text("Riprova") }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { Nav.go(Screen.SOURCES) }) { Text("Cambia fonte") }
                } else CircularProgressIndicator(color = c.accent)
            }
            return@PullToRefreshBox
        }
        val fmt = Fmt(s)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            val items = s.orderedItems().filter { s.visible(it) }
            val haptic = LocalHapticFeedback.current
            val startEdit = Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    HomeEdit.editing = true
                })
            }
            val block: @Composable (HomeItem) -> Unit = { item ->
                when (item) {
                    HomeItem.MAIN -> MainCard(f, fmt, err)
                    HomeItem.SOURCE -> SourceLine(f)
                    HomeItem.HOURS -> HoursCard(f, fmt)
                    HomeItem.GRAPH -> GraphCard(f, fmt)
                    HomeItem.DAYS -> DaysCard(f, fmt, s.daysCount)
                    HomeItem.RADAR -> RadarPreviewCard(place)
                    HomeItem.SUN -> SunCard(f, fmt)
                    else -> SmallCard(item, place, f, fmt)
                }
            }
            if (HomeEdit.editing) {
                EditBar()
                val spacing = with(LocalDensity.current) { 12.dp.toPx() }
                items.forEach { item -> EditableItem(item, spacing) { block(item) } }
            } else {
                var i = 0
                while (i < items.size) {
                    val item = items[i]
                    if (item.small) {
                        val next = items.getOrNull(i + 1)?.takeIf { n -> n.small }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f).then(startEdit)) { block(item) }
                            Box(Modifier.weight(1f).then(startEdit)) { if (next != null) block(next) }
                        }
                        i += if (next != null) 2 else 1
                    } else {
                        Box(startEdit) { block(item) }
                        i++
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ------------------------------------------------------------------ card principali

@Composable
private fun MainCard(f: Forecast, fmt: Fmt, err: String?) {
    val c = LocalAppColors.current
    val cur = f.current
    val today = f.days.firstOrNull()
    CardBox(padding = 22) {
        val updated = java.time.Instant.ofEpochMilli(f.fetchedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        Text(
            "Aggiornato alle ${fmt.time(updated)}" + if (err != null) " · offline" else "",
            color = c.inkDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(cur.code, cur.isDay, 110.dp)
            Gap(20)
            Column {
                Text(fmt.temp(cur.temp), color = c.ink, fontSize = 80.sp, fontWeight = FontWeight.Bold, lineHeight = 84.sp)
                Text(conditionText(cur.code, cur.isDay), color = c.inkDim, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Max ${fmt.temp(today?.tMax ?: Double.NaN)} · Min ${fmt.temp(today?.tMin ?: Double.NaN)} · Percepita ${fmt.temp(cur.feels)}",
            color = c.inkDim, fontSize = 16.sp,
        )
    }
}

@Composable
private fun SourceLine(f: Forecast) {
    val c = LocalAppColors.current
    val radar = RadarSource.byId(AppState.settings.radarId)
    Text(
        "Dati meteo: ${f.sourceName} (via Open-Meteo / api.met.no) · Radar: ${radar.title.substringBefore(" (")} · Aria: Copernicus CAMS",
        color = c.inkDim, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable { Nav.go(Screen.SOURCES) }.padding(4.dp),
    )
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = LocalAppColors.current.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun HoursCard(f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(vertical = 16.dp)) {
        Box(Modifier.padding(start = 18.dp, bottom = 10.dp)) { CardTitle("Prossime ore") }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            itemsIndexed(f.hours.take(24)) { i, h ->
                val first = i == 0
                Column(
                    Modifier.width(62.dp).clip(RoundedCornerShape(31.dp))
                        .background(if (first) c.accentContainer else c.card2).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(if (first) "Ora" else fmt.hourLabel(h.time), color = if (first) c.onAccentContainer else c.inkDim,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    WeatherIcon(h.code, h.isDay, 32.dp)
                    Text(fmt.temp(if (first) f.current.temp else h.temp), color = if (first) c.onAccentContainer else c.ink,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(fmt.rainShort(h.precipProb, h.precip).ifEmpty { " " }, color = if (first) c.onAccentContainer else c.accent,
                        fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GraphCard(f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    val hours = f.hours.take(25)
    if (hours.size < 2) return
    val temps = hours.map { fmt.tempValue(it.temp) }
    val lo = temps.filter { it.ok() }.minOrNull() ?: 0.0
    val hi = temps.filter { it.ok() }.maxOrNull() ?: 1.0
    CardBox {
        CardTitle("Temperatura nelle prossime 24 ore")
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.height(100.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${hi.roundToInt()}°", color = c.inkDim, fontSize = 12.sp)
                Text("${lo.roundToInt()}°", color = c.inkDim, fontSize = 12.sp)
            }
            Gap(8)
            Canvas(Modifier.weight(1f).height(100.dp)) {
                val range = (hi - lo).coerceAtLeast(1.0)
                val dx = size.width / (temps.size - 1)
                val p = Path()
                temps.forEachIndexed { i, t ->
                    val y = (size.height - 8.dp.toPx()) * (1 - ((t - lo) / range)).toFloat() + 4.dp.toPx()
                    if (i == 0) p.moveTo(0f, y) else p.lineTo(i * dx, y)
                }
                drawLine(c.outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
                drawPath(p, c.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 30.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 24).forEach { idx ->
                Text(if (idx == 0) "Ora" else hours.getOrNull(idx)?.let { fmt.hourLabel(it.time) } ?: "", color = c.inkDim, fontSize = 12.sp)
            }
        }
    }
}

private fun dayName(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Oggi"
    today.plusDays(1) -> "Domani"
    else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ITALY).replaceFirstChar { it.uppercase() } + " ${d.dayOfMonth}"
}

@Composable
private fun DaysCard(f: Forecast, fmt: Fmt, count: Int) {
    val c = LocalAppColors.current
    val days = f.days.take(count)
    if (days.isEmpty()) return
    val lo = days.minOf { it.tMin }
    val hi = days.maxOf { it.tMax }
    val span = (hi - lo).coerceAtLeast(1.0)
    val today = f.localNow.toLocalDate()
    CardBox {
        CardTitle("Prossimi ${days.size} giorni")
        Spacer(Modifier.height(6.dp))
        days.forEachIndexed { i, d ->
            if (i > 0) androidx.compose.material3.HorizontalDivider(color = c.outline.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(dayName(d.date, today), color = c.ink, fontSize = 15.sp, modifier = Modifier.width(76.dp), maxLines = 1)
                WeatherIcon(d.code, true, 30.dp)
                Text(fmt.rainShort(d.precipProb, d.precipSum), color = c.accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.width(52.dp).padding(start = 4.dp), maxLines = 1)
                Text(fmt.temp(d.tMin), color = c.inkDim, fontSize = 15.sp, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                Gap(8)
                Canvas(Modifier.weight(1f).height(6.dp)) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    drawRoundRect(c.outline, cornerRadius = r)
                    val x0 = ((d.tMin - lo) / span * size.width).toFloat()
                    val x1 = ((d.tMax - lo) / span * size.width).toFloat()
                    drawRoundRect(c.accent, topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(size.height), size.height), cornerRadius = r)
                }
                Gap(8)
                Text(fmt.temp(d.tMax), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun RadarPreviewCard(place: Place) {
    val c = LocalAppColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).clickable { Nav.go(Screen.RADAR) },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Map, contentDescription = null, tint = c.accent)
            Gap(8)
            CardTitle("Radar")
            Spacer(Modifier.weight(1f))
            Text("Apri", color = c.inkDim, fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().height(180.dp).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(18.dp))) {
            RadarPreview(place)
        }
    }
}

// ------------------------------------------------------------------ card piccole

@Composable
private fun Small(title: String, content: @Composable () -> Unit) {
    val c = LocalAppColors.current
    Column(
        Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(24.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = c.inkDim, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun Big(text: String, unit: String = "") {
    val c = LocalAppColors.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text, color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (unit.isNotBlank()) {
            Gap(6)
            Text(unit, color = c.inkDim, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun Sub(text: String) = Text(text, color = LocalAppColors.current.inkDim, fontSize = 13.sp, maxLines = 2)

@Composable
private fun Segments(fraction: Float, color: Color) {
    val c = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(if (fraction * 5 > i) color else c.outline))
        }
    }
}

@Composable
private fun SmallCard(item: HomeItem, place: Place, f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    val cur = f.current
    when (item) {
        HomeItem.HUMIDITY -> Small("Umidità") {
            Big(if (cur.humidity.ok()) "${cur.humidity.roundToInt()}%" else "—")
            Sub("Punto di rugiada ${fmt.temp(cur.dewPoint)}")
        }
        HomeItem.WIND -> Small("Vento") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val (v, u) = fmt.windParts(cur.windSpeed)
                    Big(v)
                    Sub("$u · ${windDirText(cur.windDir)}")
                }
                Canvas(Modifier.size(56.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(c.outline, r - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
                    if (cur.windDir.ok()) {
                        // la freccia indica dove va il vento (opposto alla provenienza)
                        val a = Math.toRadians(cur.windDir + 180 - 90)
                        val tip = Offset(center.x + (r * 0.75f) * cos(a).toFloat(), center.y + (r * 0.75f) * sin(a).toFloat())
                        val tail = Offset(center.x - (r * 0.55f) * cos(a).toFloat(), center.y - (r * 0.55f) * sin(a).toFloat())
                        drawLine(c.accent, tail, tip, 4.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(c.accent, 5.dp.toPx(), tip)
                    }
                }
            }
            if (cur.windGusts.ok()) Sub("Raffiche ${fmt.wind(cur.windGusts)}")
        }
        HomeItem.RAIN -> Small("Pioggia") {
            val today = f.days.firstOrNull()
            val next24 = f.hours.take(24).map { it.precip }.filter { it.ok() }.sum()
            val mode = AppState.settings.rainDisplay
            val prob = f.hours.firstOrNull()?.precipProb ?: Double.NaN
            if (mode == "mm" || !prob.ok()) Big(fmt.rain(next24)) else Big("${prob.roundToInt()}%")
            Sub(if (mode == "mm" || !prob.ok()) "nelle prossime 24 ore" else "${fmt.rain(next24)} nelle prossime 24 h")
            if (today != null && today.precipProb.ok() && mode != "mm") Sub("Oggi max ${today.precipProb.roundToInt()}%")
        }
        HomeItem.UV -> Small("Indice UV") {
            val uv = if (cur.uv.ok()) cur.uv else f.days.firstOrNull()?.uvMax ?: Double.NaN
            Big(if (uv.ok()) "${uv.roundToInt()}" else "—", uvText(uv))
            Segments(((if (uv.ok()) uv else 0.0) / 11.0).toFloat().coerceIn(0f, 1f), c.accent)
            f.days.firstOrNull()?.uvMax?.takeIf { it.ok() }?.let { Sub("Max oggi ${it.roundToInt()}") }
        }
        HomeItem.PRESSURE -> Small("Pressione") {
            val (v, u) = fmt.pressure(cur.pressure).let { p -> p.substringBefore(' ') to p.substringAfter(' ', "") }
            Big(v, u)
            Sub("al livello del mare")
        }
        HomeItem.VISIBILITY -> Small("Visibilità") {
            Big(fmt.visibility(cur.visibility))
            Sub(
                when {
                    !cur.visibility.ok() -> ""
                    cur.visibility < 1000 -> "Nebbia fitta"
                    cur.visibility < 5000 -> "Foschia"
                    cur.visibility < 20000 -> "Buona"
                    else -> "Ottima"
                }
            )
        }
        HomeItem.AIR -> Small("Qualità dell'aria") {
            val a = AppState.air[place.id]
            Big(a?.aqi?.takeIf { it.ok() }?.roundToInt()?.toString() ?: "—", aqiText(a?.aqi ?: Double.NaN))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.outline)) {
                val frac = ((a?.aqi ?: 0.0) / 100.0).toFloat().coerceIn(0.02f, 1f)
                val col = when {
                    (a?.aqi ?: 0.0) <= 40 -> Color(0xFF7BDCB5)
                    (a?.aqi ?: 0.0) <= 60 -> Color(0xFFFFD27A)
                    else -> Color(0xFFFF8A80)
                }
                Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(CircleShape).background(col))
            }
            if (a != null && a.pm25.ok()) Sub("PM2,5 ${a.pm25.roundToInt()} · PM10 ${a.pm10.roundToInt()} µg/m³")
        }
        HomeItem.MOON -> Small("Luna") {
            val m = moonInfo(f.localNow.toLocalDate())
            Row(verticalAlignment = Alignment.CenterVertically) {
                MoonDrawing(m, 40)
                Gap(10)
                Text(m.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Sub("Illuminata al ${(m.illumination * 100).roundToInt()}%")
        }
        else -> {}
    }
}

@Composable
fun MoonDrawing(m: MoonInfo, sizeDp: Int) {
    val c = LocalAppColors.current
    Canvas(Modifier.size(sizeDp.dp)) {
        val r = size.minDimension / 2
        drawCircle(c.outline, r)
        // parte illuminata: semicerchio + ellisse che cresce o cala
        val lit = Color(0xFFE9E3C8)
        val waxing = m.fraction < 0.5
        val k = cos(2 * PI * m.fraction).toFloat() // 1 = nuova, -1 = piena
        val path = Path().apply {
            val top = Offset(center.x, center.y - r)
            moveTo(top.x, top.y)
            // semicerchio esterno sul lato illuminato
            arcTo(androidx.compose.ui.geometry.Rect(center, r), -90f, if (waxing) 180f else -180f, false)
            // terminatore: ellisse di larghezza r*|k|
            val w = r * k
            val rect = androidx.compose.ui.geometry.Rect(center.x - kotlin.math.abs(w), center.y - r, center.x + kotlin.math.abs(w), center.y + r)
            val sweep = if (waxing) (if (k > 0) -180f else 180f) else (if (k > 0) 180f else -180f)
            arcTo(rect, 90f, sweep, false)
            close()
        }
        drawPath(path, lit)
    }
}

@Composable
private fun SunCard(f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    val today = f.days.firstOrNull() ?: return
    val sr = today.sunrise
    val ss = today.sunset
    val now = f.localNow.plusSeconds((System.currentTimeMillis() - f.fetchedAt) / 1000)
    val progress = if (sr != null && ss != null) {
        (Duration.between(sr, now).seconds.toFloat() / Duration.between(sr, ss).seconds.toFloat()).coerceIn(0f, 1f)
    } else 0f
    CardBox {
        Text("Sole", color = c.inkDim, fontSize = 13.sp)
        Canvas(Modifier.fillMaxWidth().height(90.dp).padding(top = 6.dp)) {
            val w = size.width
            val h = size.height
            val base = h - 6.dp.toPx()
            drawLine(c.outline, Offset(0f, base), Offset(w, base), 2.dp.toPx())
            val left = w * 0.08f
            val right = w * 0.92f
            val arc = Path().apply {
                moveTo(left, base)
                cubicTo(left + (right - left) * 0.2f, -h * 0.25f, right - (right - left) * 0.2f, -h * 0.25f, right, base)
            }
            drawPath(arc, c.inkDim.copy(alpha = 0.6f), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f))))
            // posizione del sole sulla curva di Bézier
            val t = progress
            val p0 = Offset(left, base)
            val p1 = Offset(left + (right - left) * 0.2f, -h * 0.25f)
            val p2 = Offset(right - (right - left) * 0.2f, -h * 0.25f)
            val p3 = Offset(right, base)
            val u = 1 - t
            val pos = p0 * (u * u * u) + p1 * (3 * u * u * t) + p2 * (3 * u * t * t) + p3 * (t * t * t)
            drawCircle(Color(0xFFFFC64A), 8.dp.toPx(), pos)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Alba ${fmt.time(sr)}", color = c.ink, fontSize = 15.sp)
            Text("Tramonto ${fmt.time(ss)}", color = c.ink, fontSize = 15.sp)
        }
    }
}


// ------------------------------------------------------------------ modifica della Home (come i widget del launcher)

/** Stato della modifica: tieni premuto un pannello per spostarlo o toglierlo. */
object HomeEdit {
    var editing by mutableStateOf(false)
    var dragId by mutableStateOf<String?>(null)
    var dragOffset by mutableFloatStateOf(0f)
    val heights = HashMap<String, Int>()

    private fun swap(a: String, b: String) {
        AppState.update { st ->
            val l = st.orderedItems().map { it.id }.toMutableList()
            val i = l.indexOf(a); val j = l.indexOf(b)
            if (i >= 0 && j >= 0) { l[i] = b; l[j] = a }
            st.copy(homeOrder = l)
        }
    }

    fun drag(id: String, dy: Float, spacing: Float) {
        dragOffset += dy
        val st = AppState.settings
        val order = st.orderedItems().filter { st.visible(it) }.map { it.id }
        val idx = order.indexOf(id)
        if (idx < 0) return
        if (dragOffset > 0 && idx < order.lastIndex) {
            val nh = (heights[order[idx + 1]] ?: 0) + spacing
            if (dragOffset > nh / 2) { swap(id, order[idx + 1]); dragOffset -= nh }
        } else if (dragOffset < 0 && idx > 0) {
            val ph = (heights[order[idx - 1]] ?: 0) + spacing
            if (-dragOffset > ph / 2) { swap(id, order[idx - 1]); dragOffset += ph }
        }
    }

    fun hide(id: String) = AppState.update { it.copy(hidden = it.hidden + id) }
    fun show(id: String) = AppState.update { it.copy(hidden = it.hidden - id) }
}

@Composable
private fun EditBar() {
    val c = LocalAppColors.current
    val s = AppState.settings
    var adding by remember { mutableStateOf(false) }
    val hidden = s.orderedItems().filter { !s.visible(it) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.accentContainer).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Modifica Home", color = c.onAccentContainer, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Tieni premuto e trascina · − per togliere", color = c.onAccentContainer, fontSize = 12.sp)
        }
        if (hidden.isNotEmpty()) {
            androidx.compose.material3.TextButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = c.onAccentContainer)
                Text(" Aggiungi", color = c.onAccentContainer)
            }
        }
        Button(onClick = { HomeEdit.editing = false }) { Text("Fine") }
    }
    if (adding) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Aggiungi alla Home") },
            text = {
                Column {
                    hidden.forEach { h ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { HomeEdit.show(h.id); adding = false }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = c.accent)
                            Gap(12)
                            Text(h.title, color = c.ink, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { adding = false }) { Text("Chiudi") } },
        )
    }
}

@Composable
private fun EditableItem(item: HomeItem, spacing: Float, content: @Composable () -> Unit) {
    val c = LocalAppColors.current
    val haptic = LocalHapticFeedback.current
    val dragging = HomeEdit.dragId == item.id
    val wobble = rememberInfiniteTransition(label = "wobble")
    val angle by wobble.animateFloat(
        initialValue = -0.6f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(150 + (item.ordinal % 3) * 20), RepeatMode.Reverse),
        label = "angle",
    )
    Box(
        Modifier.zIndex(if (dragging) 1f else 0f)
            .onGloballyPositioned { HomeEdit.heights[item.id] = it.size.height }
            .graphicsLayer {
                translationY = if (dragging) HomeEdit.dragOffset else 0f
                rotationZ = if (dragging) 0f else angle * (if (item.ordinal % 2 == 0) 1f else -1f)
                val sc = if (dragging) 1.03f else 0.96f
                scaleX = sc; scaleY = sc
                shadowElevation = if (dragging) 16f else 0f
            }
    ) {
        content()
        // copre il pannello: in modifica si può solo spostare o togliere
        Box(
            Modifier.matchParentSize().pointerInput(item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        HomeEdit.dragId = item.id; HomeEdit.dragOffset = 0f
                    },
                    onDragEnd = { HomeEdit.dragId = null; HomeEdit.dragOffset = 0f },
                    onDragCancel = { HomeEdit.dragId = null; HomeEdit.dragOffset = 0f },
                ) { change, amount ->
                    change.consume()
                    HomeEdit.drag(item.id, amount.y, spacing)
                }
            }
        )
        if (item.locked) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(28.dp).clip(CircleShape).background(c.card2),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Lock, contentDescription = "Sempre visibile", tint = c.inkDim, modifier = Modifier.size(16.dp)) }
        } else {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(32.dp).clip(CircleShape).background(Color(0xFFE53935))
                    .clickable { HomeEdit.hide(item.id) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Remove, contentDescription = "Togli ${item.title}", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}
