package com.marco.mlmeteo.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlmeteo.AirQuality
import com.marco.mlmeteo.AppState
import com.marco.mlmeteo.AqiBar
import com.marco.mlmeteo.Fmt
import com.marco.mlmeteo.Forecast
import com.marco.mlmeteo.HumidityGauge
import com.marco.mlmeteo.LocalAppColors
import com.marco.mlmeteo.Place
import com.marco.mlmeteo.RainBeaker
import com.marco.mlmeteo.VerticalScale
import com.marco.mlmeteo.WeatherBackdrop
import com.marco.mlmeteo.WeatherIcon
import com.marco.mlmeteo.WindCompass
import com.marco.mlmeteo.aqiText
import com.marco.mlmeteo.conditionText
import com.marco.mlmeteo.fxPeriod
import com.marco.mlmeteo.gaugeClock
import com.marco.mlmeteo.levelName
import com.marco.mlmeteo.moonInfo
import com.marco.mlmeteo.ok
import com.marco.mlmeteo.rememberFx
import com.marco.mlmeteo.rememberGrow
import com.marco.mlmeteo.skyOf
import com.marco.mlmeteo.uiCard
import com.marco.mlmeteo.uvText
import com.marco.mlmeteo.windDirLong
import java.time.Duration
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------ elementi comuni

@Composable
fun Card(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    Column(modifier.uiCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, color = c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
fun Tabs(items: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(c.card2).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { (k, label) ->
            val sel = k == value
            Box(
                Modifier.clip(RoundedCornerShape(5.dp)).background(if (sel) c.card else Color.Transparent)
                    .clickable { onPick(k) }.padding(horizontal = 12.dp, vertical = 5.dp),
            ) { Text(label, color = if (sel) c.ink else c.inkDim, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal) }
        }
    }
}

@Composable
fun PageHeader(title: String, onMenu: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onMenu != null) IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Menu", tint = c.ink) }
        Text(title, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Pagina con scorrimento verticale e barra di scorrimento di Windows. */
@Composable
fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    val sc = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(sc).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(sc), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp))
    }
}

// ------------------------------------------------------------------ pagina Meteo

@Composable
fun MeteoPage(width: Dp, onMenu: (() -> Unit)?) {
    val c = LocalAppColors.current
    val s = AppState.settings
    val fmt = Fmt(s)
    val place = AppState.selected()
    Column(Modifier.fillMaxSize()) {
        PageHeader("Meteo", onMenu) {
            if (place != null) PlacePicker(place)
            IconButton(onClick = { AppState.refreshAll(force = true) }) { Icon(Icons.Outlined.Refresh, "Aggiorna", tint = c.ink) }
        }
        if (place == null) {
            ScrollPage {
                Card(Modifier.fillMaxWidth()) {
                    Text("Aggiungi la tua prima località", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("Vai su Località e cerca una città.", color = c.inkDim, fontSize = 14.sp)
                    Text("Apri Località", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable { Nav.page = Page.PLACES })
                }
            }
            return@Column
        }
        LaunchedEffect(place.id) { AppState.refresh(place) }
        val f = AppState.forecasts[place.id]
        if (f == null) {
            ScrollPage {
                Card(Modifier.fillMaxWidth()) {
                    Text(AppState.errors[place.id]?.let { "Errore: $it" } ?: "Scarico le previsioni…", color = c.inkDim, fontSize = 14.sp)
                }
            }
            return@Column
        }
        val air = AppState.air[place.id]
        ScrollPage {
            AppState.alerts[place.id].orEmpty().takeIf { it.isNotEmpty() }?.let { al ->
                val lvl = al.maxOf { it.level }
                val col = when (lvl) { 3 -> Color(0xFFE53935); 2 -> Color(0xFFFB8C00); else -> Color(0xFFFDD835) }
                Row(
                    Modifier.fillMaxWidth().uiCard(col.copy(alpha = 0.18f)).clickable { Nav.page = Page.ALERTS }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = col)
                    Spacer(Modifier.width(10.dp))
                    Text("Allerta ${levelName(lvl)}: ${al.first().title}" + if (al.size > 1) " (+${al.size - 1})" else "", color = c.ink, fontSize = 14.sp)
                }
            }
            Header(place, f, fmt)
            when {
                width >= 1400.dp -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HoursCard(f, fmt); RainChart(f, fmt); TempChart(f, fmt)
                    }
                    Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(12.dp)) { Details(f, fmt, air, 2) }
                    Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(12.dp)) { DaysCard(f, fmt) }
                }
                width >= 1000.dp -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.7f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HoursCard(f, fmt); Details(f, fmt, air, 3)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { RainChart(f, fmt) }; Box(Modifier.weight(1f)) { TempChart(f, fmt) }
                        }
                    }
                    Column(Modifier.weight(1f)) { DaysCard(f, fmt) }
                }
                width >= 600.dp -> {
                    HoursCard(f, fmt)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) { Details(f, fmt, air, 2) }
                        Column(Modifier.weight(1f)) { DaysCard(f, fmt) }
                    }
                    RainChart(f, fmt); TempChart(f, fmt)
                }
                else -> {
                    HoursCard(f, fmt); DaysCard(f, fmt); Details(f, fmt, air, 2); RainChart(f, fmt); TempChart(f, fmt)
                }
            }
            Text("Dati: ${f.sourceName} · Aria: Copernicus CAMS · Allerte: Protezione Civile, MeteoAlarm", color = c.inkDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlacePicker(place: Place) {
    val c = LocalAppColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.uiCard().clickable { open = true }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(place.name, color = c.ink, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.ExpandMore, null, tint = c.inkDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            AppState.places.forEach { p ->
                val pf = AppState.forecasts[p.id]
                DropdownMenuItem(
                    text = { Text(p.name + (pf?.let { "  ·  " + Fmt(AppState.settings).temp(it.current.temp) } ?: "")) },
                    onClick = { AppState.selectedId = p.id; AppState.save(); open = false },
                )
            }
            DropdownMenuItem(text = { Text("Gestisci località…") }, onClick = { open = false; Nav.page = Page.PLACES })
        }
    }
}

/** Intestazione grande, senza scheda: icona animata, condizione, temperatura; a destra località e dettagli. */
@Composable
private fun Header(place: Place, f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    val cur = f.current
    val today = f.days.firstOrNull()
    val mode = AppState.settings.animations
    val sky = skyOf(cur.code)
    var taps by remember { mutableStateOf(0) }
    val fx = rememberFx(sky, mode, Triple(f.fetchedAt, cur.code, taps))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { taps++ }) {
        WeatherBackdrop(Modifier.matchParentSize(), sky, cur.isDay, cur.precip, fx, animate = mode == "always" || mode == "once")
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(cur.code, cur.isDay, 120.dp, phase = fx.phase, amp = if (mode == "off") 0f else fx.amp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(conditionText(cur.code, cur.isDay), color = c.ink, fontSize = 22.sp)
                Text(fmt.temp(cur.temp), color = c.ink, fontSize = 72.sp, fontWeight = FontWeight.SemiBold, lineHeight = 76.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(place.name, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("Percepita ${fmt.temp(cur.feels)}", color = c.ink, fontSize = 15.sp)
                Text("Max ${fmt.temp(today?.tMax ?: Double.NaN)} · Min ${fmt.temp(today?.tMin ?: Double.NaN)}", color = c.inkDim, fontSize = 14.sp)
                val upd = java.time.Instant.ofEpochMilli(f.fetchedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                Text("${f.sourceName} · agg. ${fmt.time(upd)}", color = c.inkDim, fontSize = 12.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ ore con le schede

@Composable
private fun HoursCard(f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    var tab by remember { mutableStateOf("temp") }
    val hrs = f.hours.take(24)
    val t = gaugeClock()
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tabs(listOf("temp" to "Temperatura", "wind" to "Vento", "rain" to "Pioggia"), tab) { tab = it }
            Spacer(Modifier.weight(1f))
            val head = when (tab) {
                "wind" -> hrs.mapNotNull { h -> h.gust.takeIf { it.ok() } ?: h.wind.takeIf { it.ok() } }.maxOrNull()?.let { "Raffica max ${fmt.wind(it)}" } ?: ""
                "rain" -> "Totale ${fmt.rain(hrs.sumOf { if (it.precip.ok()) it.precip else 0.0 })}"
                else -> f.days.firstOrNull()?.let { "Max oggi ${fmt.temp(it.tMax)}" } ?: ""
            }
            Text(head, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            hrs.forEachIndexed { i, h ->
                val first = i == 0
                Column(
                    Modifier.width(66.dp).clip(RoundedCornerShape(8.dp)).background(if (first) c.accentContainer else c.card2).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val ink = if (first) c.onAccentContainer else c.ink
                    Text(if (first) "Ora" else fmt.hourLabel(h.time), color = if (first) ink else c.inkDim, fontSize = 12.sp)
                    when (tab) {
                        "wind" -> {
                            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                if (h.windDir.ok()) Canvas(Modifier.size(24.dp).rotate((h.windDir + 180).toFloat() + kotlin.math.sin(t * 2 + i) * 3f)) {
                                    val p = Path().apply {
                                        moveTo(size.width / 2, 0f); lineTo(size.width * 0.9f, size.height); lineTo(size.width / 2, size.height * 0.72f)
                                        lineTo(size.width * 0.1f, size.height); close()
                                    }
                                    drawPath(p, Color(0xFFEF9F27))
                                }
                            }
                            Text(if (h.wind.ok()) fmt.windParts(h.wind).first else "—", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(if (h.gust.ok()) "raff. ${fmt.windParts(h.gust).first}" else " ", color = c.inkDim, fontSize = 11.sp)
                        }
                        "rain" -> {
                            Text(if (h.precipProb.ok()) "${h.precipProb.roundToInt()}%" else "—", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            val mm = if (h.precip.ok()) h.precip else 0.0
                            val grow = rememberGrow((mm / 5.0).toFloat().coerceIn(0f, 1f))
                            Box(Modifier.width(12.dp).height(38.dp).clip(RoundedCornerShape(6.dp)).background(c.outline), contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.fillMaxWidth().height((38 * grow).dp).background(Color(0xFF1E88E5)))
                            }
                            Text(fmt.rain(mm), color = Color(0xFF6CB8FF), fontSize = 11.sp)
                        }
                        else -> {
                            WeatherIcon(h.code, h.isDay, 34.dp, phase = t / fxPeriod(skyOf(h.code)), amp = if (AppState.settings.animations == "off") 0f else 1f)
                            Text(fmt.temp(if (first) f.current.temp else h.temp), color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(if (h.precipProb.ok() && h.precipProb >= 1) "${h.precipProb.roundToInt()}%" else " ", color = if (first) ink else c.accent, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ colonna Domani / 10 giorni

@Composable
private fun DaysCard(f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    var tab by remember { mutableStateOf("days") }
    val t = gaugeClock()
    val anim = AppState.settings.animations != "off"
    Card(Modifier.fillMaxWidth()) {
        Tabs(listOf("tomorrow" to "Domani", "days" to "${AppState.settings.daysCount} giorni"), tab) { tab = it }
        if (tab == "tomorrow") {
            val tomorrow = f.localNow.toLocalDate().plusDays(1)
            f.hours.filter { it.time.toLocalDate() == tomorrow && it.time.hour % 2 == 0 }.forEach { h ->
                Row(Modifier.fillMaxWidth().uiCard(c.card2).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt.hourLabel(h.time), color = c.ink, fontSize = 14.sp, modifier = Modifier.width(64.dp))
                    WeatherIcon(h.code, h.isDay, 30.dp, phase = t / fxPeriod(skyOf(h.code)), amp = if (anim) 1f else 0f)
                    Spacer(Modifier.weight(1f))
                    if (h.precipProb.ok() && h.precipProb >= 1) Text("${h.precipProb.roundToInt()}%  ", color = c.accent, fontSize = 12.sp)
                    Text(fmt.temp(h.temp), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val days = f.days.take(AppState.settings.daysCount)
            val lo = days.minOfOrNull { it.tMin } ?: 0.0
            val hi = days.maxOfOrNull { it.tMax } ?: 1.0
            val today = f.localNow.toLocalDate()
            days.forEach { d ->
                Row(Modifier.fillMaxWidth().uiCard(c.card2).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (d.date == today) "Oggi" else d.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.ITALY).replaceFirstChar { it.uppercase() },
                        color = c.ink, fontSize = 14.sp, modifier = Modifier.width(52.dp),
                    )
                    WeatherIcon(d.code, true, 30.dp, phase = t / fxPeriod(skyOf(d.code)), amp = if (anim) 1f else 0f)
                    Column(Modifier.width(56.dp).padding(start = 6.dp)) {
                        if (d.precipProb.ok() && d.precipProb >= 1) Text("${d.precipProb.roundToInt()}%", color = c.accent, fontSize = 11.sp)
                        if (d.precipSum.ok() && d.precipSum >= 0.1) Text(fmt.rain(d.precipSum), color = Color(0xFF6CB8FF), fontSize = 11.sp)
                    }
                    Text(fmt.temp(d.tMin), color = c.inkDim, fontSize = 13.sp)
                    Canvas(Modifier.weight(1f).height(6.dp).padding(horizontal = 6.dp)) {
                        val span = (hi - lo).coerceAtLeast(1.0)
                        drawRoundRect(c.outline, cornerRadius = CornerRadius(3f * density))
                        val x0 = ((d.tMin - lo) / span * size.width).toFloat(); val x1 = ((d.tMax - lo) / span * size.width).toFloat()
                        drawRoundRect(c.accent, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(size.height), size.height), CornerRadius(3f * density))
                    }
                    Text(fmt.temp(d.tMax), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ schede dei dettagli (indicatori animati condivisi con Android)

@Composable
private fun Details(f: Forecast, fmt: Fmt, air: AirQuality?, cols: Int) {
    val cur = f.current
    val next24 = f.hours.take(24).map { it.precip }.filter { it.ok() }.sum()
    val maxProb = f.hours.take(24).mapNotNull { h -> h.precipProb.takeIf { it.ok() } }.maxOrNull()
    val uv = if (cur.uv.ok()) cur.uv else f.days.firstOrNull()?.uvMax ?: Double.NaN
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> Detail(m, "Vento", fmt.windParts(cur.windSpeed).let { "${it.first} ${it.second}" }, if (cur.windDir.ok()) "Da ${windDirLong(cur.windDir)}" else "",
            if (cur.windGusts.ok()) "Raffiche ${fmt.wind(cur.windGusts)}" else "") { WindCompass(Modifier.size(70.dp), cur.windDir, cur.windSpeed) } },
        { m -> Detail(m, "Umidità", if (cur.humidity.ok()) "${cur.humidity.roundToInt()}%" else "—",
            when { !cur.humidity.ok() -> ""; cur.humidity < 30 -> "Secca"; cur.humidity < 60 -> "Normale"; cur.humidity < 85 -> "Umida"; else -> "Molto umida" },
            "Rugiada ${fmt.temp(cur.dewPoint)}") { HumidityGauge(Modifier.width(18.dp).height(84.dp), ((if (cur.humidity.ok()) cur.humidity else 0.0) / 100).toFloat()) } },
        { m -> Detail(m, "Pioggia", fmt.rain(next24), "prossime 24 ore", maxProb?.let { "Probabilità max ${it.roundToInt()}%" } ?: "") {
            RainBeaker(Modifier.width(64.dp).height(88.dp), next24, cur.precip.ok() && cur.precip > 0.05) } },
        { m -> Detail(m, "Pressione", fmt.pressure(cur.pressure),
            when { !cur.pressure.ok() -> ""; cur.pressure < 1000 -> "Bassa"; cur.pressure > 1025 -> "Alta"; else -> "Normale" }, "al livello del mare") {
            VerticalScale(Modifier.width(26.dp).height(84.dp), (((if (cur.pressure.ok()) cur.pressure else 1013.0) - 970) / 70).toFloat(),
                listOf(Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFCA28), Color(0xFFFF7043))) } },
        { m -> Detail(m, "Indice UV", if (uv.ok()) "${uv.roundToInt()}" else "—", uvText(uv), f.days.firstOrNull()?.uvMax?.takeIf { it.ok() }?.let { "Max oggi ${it.roundToInt()}" } ?: "") {
            VerticalScale(Modifier.width(26.dp).height(84.dp), ((if (uv.ok()) uv else 0.0) / 11).toFloat(),
                listOf(Color(0xFF4CAF50), Color(0xFFFFEB3B), Color(0xFFFF9800), Color(0xFFE53935), Color(0xFF8E24AA))) } },
        { m -> Card(m.height(150.dp), "Qualità dell'aria") {
            val c = LocalAppColors.current
            Text("${air?.aqi?.takeIf { it.ok() }?.roundToInt() ?: "—"}  ${aqiText(air?.aqi ?: Double.NaN)}", color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            AqiBar(Modifier.fillMaxWidth().height(14.dp), air?.aqi ?: Double.NaN)
            if (air != null && air.pm25.ok()) Text("PM2,5 ${air.pm25.roundToInt()} · PM10 ${air.pm10.roundToInt()} µg/m³", color = c.inkDim, fontSize = 12.sp)
        } },
        { m -> SunCard(m, f, fmt) },
        { m -> Detail(m, "Visibilità", fmt.visibility(cur.visibility),
            when { !cur.visibility.ok() -> ""; cur.visibility < 1000 -> "Nebbia fitta"; cur.visibility < 5000 -> "Foschia"; cur.visibility < 20000 -> "Buona"; else -> "Ottima" }, "") {} },
        { m -> MoonCard(m, f) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { card -> card(Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Detail(m: Modifier, title: String, big: String, sub1: String, sub2: String, gauge: @Composable () -> Unit) {
    val c = LocalAppColors.current
    Card(m.height(150.dp), title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(big, color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (sub1.isNotBlank()) Text(sub1, color = c.inkDim, fontSize = 13.sp)
                if (sub2.isNotBlank()) Text(sub2, color = c.inkDim, fontSize = 12.sp)
            }
            gauge()
        }
    }
}

@Composable
private fun SunCard(m: Modifier, f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    val today = f.days.firstOrNull()
    val sr = today?.sunrise; val ss = today?.sunset
    val now = f.localNow.plusSeconds((System.currentTimeMillis() - f.fetchedAt) / 1000)
    val night = sr != null && ss != null && (now.isAfter(ss) || now.isBefore(sr))
    val prog = when {
        sr == null || ss == null -> 0f
        !night -> (Duration.between(sr, now).seconds.toFloat() / Duration.between(sr, ss).seconds).coerceIn(0f, 1f)
        else -> {
            val nightLen = (86400f - Duration.between(sr, ss).seconds).coerceAtLeast(1f)
            val since = if (now.isAfter(ss)) Duration.between(ss, now).seconds.toFloat() else nightLen - Duration.between(now, sr).seconds
            (since / nightLen).coerceIn(0f, 1f)
        }
    }
    val g = rememberGrow(prog)
    val t = gaugeClock()
    Card(m.height(150.dp), "Alba e tramonto") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("↑ ${fmt.time(sr)}", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("↓ ${fmt.time(ss)}", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Canvas(Modifier.width(110.dp).height(84.dp)) {
                val w = size.width; val h = size.height; val base = h * 0.6f; val r = w * 0.42f
                val cx = w / 2
                drawLine(c.outline, Offset(0f, base), Offset(w, base), 1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawArc(c.inkDim.copy(alpha = 0.6f), 180f, 180f, false, Offset(cx - r, base - r), Size(2 * r, 2 * r), style = Stroke(1.5f * density))
                drawArc(c.inkDim.copy(alpha = 0.35f), 0f, 180f, false, Offset(cx - r, base - r * 0.55f), Size(2 * r, r * 1.1f),
                    style = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f))))
                if (!night) {
                    val a = Math.PI * (1 - g)
                    val p = Offset(cx + r * kotlin.math.cos(a).toFloat(), base - r * kotlin.math.sin(a).toFloat())
                    drawCircle(Color(0xFFFFC64A).copy(alpha = 0.25f), (11 + kotlin.math.sin(t * 2f) * 1.5f) * density, p)
                    drawCircle(Color(0xFFFFC64A), 7 * density, p)
                } else {
                    val a = Math.PI * g
                    val p = Offset(cx + r * kotlin.math.cos(a).toFloat(), base + r * 0.55f * kotlin.math.sin(a).toFloat())
                    drawCircle(Color(0xFFE9E3C8), 6 * density, p)
                    drawCircle(c.card, 5 * density, p + Offset(3 * density, -2 * density))
                }
            }
        }
    }
}

@Composable
private fun MoonCard(m: Modifier, f: Forecast) {
    val c = LocalAppColors.current
    val mi = moonInfo(f.localNow.toLocalDate())
    Card(m.height(150.dp), "Luna") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mi.name, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Illuminata al ${(mi.illumination * 100).roundToInt()}%", color = c.inkDim, fontSize = 13.sp)
            }
            Canvas(Modifier.size(56.dp)) {
                val r = size.minDimension / 2
                drawCircle(c.outline, r)
                val lit = Color(0xFFE9E3C8)
                val k = kotlin.math.cos(2 * Math.PI * mi.fraction).toFloat()
                val waxing = mi.fraction < 0.5
                val path = Path().apply {
                    moveTo(center.x, center.y - r)
                    arcTo(androidx.compose.ui.geometry.Rect(center, r), -90f, if (waxing) 180f else -180f, false)
                    val w = r * kotlin.math.abs(k)
                    arcTo(androidx.compose.ui.geometry.Rect(center.x - w, center.y - r, center.x + w, center.y + r), 90f,
                        if (waxing) (if (k > 0) -180f else 180f) else (if (k > 0) 180f else -180f), false)
                    close()
                }
                drawPath(path, lit)
            }
        }
    }
}

// ------------------------------------------------------------------ grafici

@Composable
private fun RainChart(f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    val hrs = f.hours.take(24)
    if (hrs.size < 2) return
    val maxMm = hrs.maxOf { if (it.precip.ok()) it.precip else 0.0 }.coerceAtLeast(2.0)
    val total = hrs.sumOf { if (it.precip.ok()) it.precip else 0.0 }
    val maxProb = hrs.mapNotNull { h -> h.precipProb.takeIf { it.ok() } }.maxOrNull()
    val probCol = Color(0xFF64B5F6); val mmCol = Color(0xFF1E88E5)
    Card(Modifier.fillMaxWidth(), "Pioggia nelle prossime 24 ore · ${fmt.rain(total)}" + (maxProb?.let { " · ${it.roundToInt()}%" } ?: "")) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = hrs.size; val slot = size.width / n
            listOf(0f, 0.5f, 1f).forEach { y -> drawLine(c.outline, Offset(0f, size.height * y), Offset(size.width, size.height * y), 1f) }
            val area = Path(); val line = Path()
            hrs.forEachIndexed { i, h ->
                val x = i * slot + slot / 2
                val y = (size.height * (1 - (if (h.precipProb.ok()) h.precipProb else 0.0) / 100)).toFloat()
                if (i == 0) { area.moveTo(x, size.height); area.lineTo(x, y); line.moveTo(x, y) } else { area.lineTo(x, y); line.lineTo(x, y) }
                if (i == n - 1) { area.lineTo(x, size.height); area.close() }
            }
            drawPath(area, probCol.copy(alpha = 0.35f))
            drawPath(line, probCol, style = Stroke(2f * density, cap = StrokeCap.Round))
            hrs.forEachIndexed { i, h ->
                val v = if (h.precip.ok()) h.precip else 0.0
                if (v > 0) {
                    val bh = (v / maxMm * size.height).toFloat().coerceAtLeast(2f * density)
                    drawRoundRect(mmCol, Offset(i * slot + slot * 0.22f, size.height - bh), Size(slot * 0.56f, bh), CornerRadius(2f * density))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { i -> Text(if (i == 0) "Ora" else fmt.hourLabel(hrs[i.coerceAtMost(hrs.size - 1)].time), color = c.inkDim, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun TempChart(f: Forecast, fmt: Fmt) {
    val c = LocalAppColors.current
    val hrs = f.hours.take(24).filter { it.temp.ok() }
    if (hrs.size < 2) return
    val lo = hrs.minOf { it.temp }; val hi = hrs.maxOf { it.temp }
    Card(Modifier.fillMaxWidth(), "Temperatura nelle prossime 24 ore · ${fmt.temp(lo)} / ${fmt.temp(hi)}") {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val span = (hi - lo).coerceAtLeast(1.0)
            val p = Path()
            hrs.forEachIndexed { i, h ->
                val x = size.width * i / (hrs.size - 1)
                val y = (size.height * 0.9f - (h.temp - lo) / span * size.height * 0.8f).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, c.accent, style = Stroke(2.5f * density, cap = StrokeCap.Round))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { i -> Text(if (i == 0) "Ora" else fmt.hourLabel(hrs[i.coerceAtMost(hrs.size - 1)].time), color = c.inkDim, fontSize = 11.sp) }
        }
    }
}
