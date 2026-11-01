package com.marco.mlmeteo

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Link della community e delle donazioni (vuoti = voce nascosta). */
const val DONATE_URL = ""
const val TELEGRAM_URL = ""

@Composable
fun SettingsScreen() {
    val s = AppState.settings
    Page("Impostazioni") {
        SectionTitle("Fonti dati")
        Group {
            SettingRow("Dati meteo", Sources.byId(s.sourceId).name, Icons.Filled.CloudQueue, onClick = { Nav.go(Screen.SOURCES) })
            Divider()
            RadarSourceRow()
            Divider()
            SettingRow("Qualità dell'aria", "Copernicus CAMS", Icons.Filled.Air)
            Divider()
            ChoiceRow(
                "Ore della mappa dettagliata", Icons.Filled.AccessTime,
                listOf(
                    Choice("6", "Prossime 6 ore", "Circa 8 MB per strato."),
                    Choice("12", "Prossime 12 ore", "Circa 15 MB per strato."),
                    Choice("24", "Prossime 24 ore", "ICON-2I arriva al massimo a 24 ore."),
                    Choice("48", "Prossime 48 ore (solo WRF)", "ICON-2I resta a 24 ore."),
                    Choice("72", "Prossimi 3 giorni (solo WRF)", "ICON-2I resta a 24 ore."),
                ),
                s.icon2iHours.toString(),
            ) { v -> AppState.update { it.copy(icon2iHours = v.toInt()) } }
            Divider()
            SettingRow("Chiavi API", "OpenWeatherMap, WeatherAPI, AccuWeather…", Icons.Filled.Key, onClick = { Nav.go(Screen.API_KEYS) })
        }

        SectionTitle("Località")
        Group {
            SettingRow("Gestisci località", "${AppState.savedPlaces.size} salvate", Icons.Filled.Place, onClick = { Nav.go(Screen.PLACES) })
            Divider()
            SwitchRow("Usa posizione GPS", "Mostra il meteo dove ti trovi", Icons.Filled.MyLocation, checked = s.useGps) { v ->
                AppState.update { it.copy(useGps = v) }
                if (v) AppState.locate()
            }
        }

        SectionTitle("Aspetto")
        Group {
            SettingRow("Tema e colori", if (s.useWallpaper) "Colori dallo sfondo" else "Colore manuale", Icons.Filled.Palette,
                onClick = { Nav.go(Screen.APPEARANCE) })
            Divider()
            SettingRow("Set di icone", IconSetNames[s.iconSet.coerceIn(0, 5)].first, Icons.Filled.Brush, onClick = { Nav.go(Screen.APPEARANCE) })
            Divider()
            SettingRow("Elementi della Home", "Scegli cosa vedere e in che ordine", Icons.Filled.Dashboard, onClick = { Nav.go(Screen.HOME_ITEMS) })
        }

        SectionTitle("Unità")
        Group {
            SettingRow("Unità di misura", "Temperatura, vento, pioggia, pressione…", Icons.Filled.Straighten, onClick = { Nav.go(Screen.UNITS) })
        }

        SectionTitle("Notifiche e widget")
        Group {
            SettingRow("Notifiche", "Allerte, pioggia in arrivo, previsione del mattino", Icons.Filled.Notifications,
                onClick = { Nav.go(Screen.NOTIFICATIONS) })
            Divider()
            SettingRow("Widget", "In arrivo", Icons.Filled.Widgets, enabled = false)
            Divider()
            SettingRow("Aggiornamento in background", "In arrivo con notifiche e widget", Icons.Filled.Refresh, enabled = false)
        }

        SectionTitle("Info")
        Group {
            SettingRow("Informazioni", "Versione, aggiornamenti, crediti", Icons.Filled.Info, onClick = { Nav.go(Screen.INFO) })
        }
    }
}

// ------------------------------------------------------------------ aspetto

@Composable
fun AppearanceScreen() {
    val c = LocalAppColors.current
    val s = AppState.settings
    Page("Aspetto") {
        SectionTitle("Tema")
        Group {
            val canWall = Build.VERSION.SDK_INT >= 31
            SwitchRow(
                "Colori dallo sfondo",
                if (canWall) "Material You: la palette segue il wallpaper" else "Richiede Android 12 o successivo",
                checked = s.useWallpaper && canWall, enabled = canWall,
            ) { v -> AppState.update { it.copy(useWallpaper = v) } }
            Divider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Colore manuale", color = c.ink, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SixColors.forEach { (argb, name) ->
                        val sel = !s.useWallpaper && s.manualColor == argb
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Color(argb))
                                .border(3.dp, if (sel) c.ink else Color.Transparent, CircleShape)
                                .clickable { AppState.update { it.copy(useWallpaper = false, manualColor = argb) } },
                            contentAlignment = Alignment.Center,
                        ) { if (sel) Icon(Icons.Filled.Check, contentDescription = name, tint = Color(0xFF16151C)) }
                    }
                }
            }
            Divider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Modalità", color = c.ink, fontSize = 16.sp)
                Segmented(
                    listOf("light" to "Chiaro", "dark" to "Scuro", "auto" to "Auto", "amoled" to "AMOLED"),
                    s.mode,
                ) { m -> AppState.update { it.copy(mode = m) } }
            }
            Divider()
            SwitchRow("Sfumatura in base al meteo", "Più calda col sole, più fredda con la pioggia", checked = s.weatherTint) { v ->
                AppState.update { it.copy(weatherTint = v) }
            }
        }

        SectionTitle("Icone meteo")
        val rows = IconSetNames.indices.chunked(2)
        rows.forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { i ->
                    val sel = s.iconSet == i
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(c.card)
                            .border(2.dp, if (sel) c.accent else Color.Transparent, RoundedCornerShape(22.dp))
                            .clickable { AppState.update { it.copy(iconSet = i) } }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(0 to true, 2 to true, 63 to true, 95 to true).forEach { (code, day) ->
                                WeatherIcon(code, day, 32.dp, set = i)
                            }
                        }
                        Text(IconSetNames[i].first, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(IconSetNames[i].second, color = c.inkDim, fontSize = 12.sp)
                    }
                }
            }
        }
        Hint("Tutte le icone sono disegnate da MLMeteo, nessuna licenza esterna.")
    }
}

// ------------------------------------------------------------------ elementi della Home

@Composable
fun HomeItemsScreen() {
    val c = LocalAppColors.current
    val s = AppState.settings
    val items = s.orderedItems()
    Page("Elementi della Home") {
        // anteprima in piccolo
        CardBox(padding = 12) {
            Text("Anteprima", color = c.inkDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            val vis = items.filter { s.visible(it) }
            var i = 0
            while (i < vis.size) {
                val it = vis[i]
                if (it.small) {
                    val next = vis.getOrNull(i + 1)?.takeIf { n -> n.small }
                    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MiniBlock(it, Modifier.weight(1f))
                        if (next != null) MiniBlock(next, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    }
                    i += if (next != null) 2 else 1
                } else {
                    MiniBlock(it, Modifier.fillMaxWidth().padding(vertical = 2.dp))
                    i++
                }
            }
        }

        val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }
        var dragIndex by remember { mutableStateOf(-1) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        Group {
            items.forEachIndexed { i, item ->
                if (i > 0) Divider()
                val currentIndex by rememberUpdatedState(i)
                val handle = Modifier.pointerInput(item.id) {
                    detectDragGestures(
                        onDragStart = { dragIndex = currentIndex; dragOffset = 0f },
                        onDragEnd = { dragIndex = -1; dragOffset = 0f },
                        onDragCancel = { dragIndex = -1; dragOffset = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val idx = dragIndex
                        val list = AppState.settings.orderedItems().map { x -> x.id }.toMutableList()
                        val to = when {
                            dragOffset > rowHeight * 0.6f && idx < list.lastIndex -> idx + 1
                            dragOffset < -rowHeight * 0.6f && idx > 0 -> idx - 1
                            else -> idx
                        }
                        if (to != idx && idx >= 0) {
                            list.add(to, list.removeAt(idx))
                            AppState.update { st -> st.copy(homeOrder = list) }
                            dragOffset += if (to > idx) -rowHeight else rowHeight
                            dragIndex = to
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .graphicsLayer { translationY = if (dragIndex == i) dragOffset else 0f }
                        .background(if (dragIndex == i) c.card2 else Color.Transparent)
                        .padding(start = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.DragHandle, contentDescription = "Trascina", tint = c.inkDim,
                        modifier = handle.padding(12.dp).size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = c.ink, fontSize = 16.sp)
                        if (item.locked) Text("Sempre visibile", color = c.inkDim, fontSize = 12.sp)
                    }
                    if (item.locked) {
                        Icon(Icons.Filled.Lock, contentDescription = "Sempre visibile", tint = c.inkDim, modifier = Modifier.padding(end = 12.dp))
                    } else {
                        Switch(checked = item.id !in s.hidden, onCheckedChange = { v ->
                            AppState.update { st -> st.copy(hidden = if (v) st.hidden - item.id else st.hidden + item.id) }
                        })
                    }
                }
                if (item == HomeItem.DAYS && item.id !in s.hidden) {
                    Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                        Text("Quanti giorni", color = c.inkDim, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Segmented(listOf("5" to "5", "7" to "7", "10" to "10", "16" to "16"), s.daysCount.toString()) { v ->
                            AppState.update { it.copy(daysCount = v.toInt()) }
                        }
                    }
                }
            }
        }
        TextButton(onClick = {
            AppState.update { it.copy(homeOrder = HomeItem.entries.map { h -> h.id }, hidden = emptySet(), daysCount = 10) }
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Ripristina predefiniti") }
    }
}

@Composable
private fun MiniBlock(item: HomeItem, modifier: Modifier) {
    val c = LocalAppColors.current
    val h = when (item) {
        HomeItem.MAIN -> 46; HomeItem.SOURCE -> 12; HomeItem.DAYS -> 52; HomeItem.HOURS -> 30
        HomeItem.RADAR -> 36; else -> 24
    }
    Box(
        modifier.height(h.dp).clip(RoundedCornerShape(8.dp))
            .background(if (item == HomeItem.MAIN) c.accentContainer else c.card2),
        contentAlignment = Alignment.Center,
    ) {
        Text(item.title, color = if (item == HomeItem.MAIN) c.onAccentContainer else c.inkDim, fontSize = 10.sp, maxLines = 1)
    }
}

// ------------------------------------------------------------------ unità

@Composable
fun UnitsScreen() {
    val s = AppState.settings
    Page("Unità") {
        Group {
            ChoiceRow("Temperatura", Icons.Filled.Thermostat, listOf(Choice("C", "Celsius (°C)"), Choice("F", "Fahrenheit (°F)")), s.tempUnit) { v ->
                AppState.update { it.copy(tempUnit = v) }
            }
            Divider()
            ChoiceRow(
                "Vento", Icons.Filled.Air,
                listOf(Choice("kmh", "km/h"), Choice("ms", "m/s"), Choice("kn", "Nodi"), Choice("bft", "Beaufort"), Choice("mph", "mph")),
                s.windUnit,
            ) { v -> AppState.update { it.copy(windUnit = v) } }
            Divider()
            ChoiceRow(
                "Pioggia: cosa mostrare", Icons.Filled.WaterDrop,
                listOf(
                    Choice("prob", "Probabilità (%)", "Se la fonte non dà la probabilità vengono mostrati i millimetri."),
                    Choice("mm", "Quantità (mm)"),
                    Choice("both", "Entrambe (% · mm)"),
                ),
                s.rainDisplay,
            ) { v -> AppState.update { it.copy(rainDisplay = v) } }
            Divider()
            ChoiceRow("Quantità di pioggia", Icons.Filled.WaterDrop, listOf(Choice("mm", "Millimetri (mm)"), Choice("in", "Pollici (in)")), s.rainUnit) { v ->
                AppState.update { it.copy(rainUnit = v) }
            }
            Divider()
            ChoiceRow("Pressione", Icons.Filled.Speed, listOf(Choice("hpa", "hPa"), Choice("mmhg", "mmHg"), Choice("inhg", "inHg")), s.pressureUnit) { v ->
                AppState.update { it.copy(pressureUnit = v) }
            }
            Divider()
            ChoiceRow("Visibilità", Icons.Filled.Visibility, listOf(Choice("km", "Chilometri"), Choice("mi", "Miglia")), s.visUnit) { v ->
                AppState.update { it.copy(visUnit = v) }
            }
            Divider()
            ChoiceRow("Formato ora", Icons.Filled.AccessTime, listOf(Choice("24", "24 ore"), Choice("12", "12 ore")), if (s.clock24) "24" else "12") { v ->
                AppState.update { it.copy(clock24 = v == "24") }
            }
        }
    }
}

// ------------------------------------------------------------------ notifiche (prossima versione)

@Composable
fun NotificationsScreen() {
    Page("Notifiche") {
        Hint("Le notifiche arrivano nella prossima versione. Ecco cosa ci sarà:")
        Group {
            SettingRow("Allerte meteo", "Protezione Civile e MeteoAlarm, solo per la località con la campanella", Icons.Filled.Notifications, enabled = false)
            Divider()
            SettingRow("Pioggia in arrivo", "\"Pioggia tra 15 minuti\"", Icons.Filled.WaterDrop, enabled = false)
            Divider()
            SettingRow("Previsione del mattino", "All'orario che scegli", Icons.Filled.AccessTime, enabled = false)
            Divider()
            SettingRow("Temperatura nella barra di stato", "Notifica fissa", Icons.Filled.Thermostat, enabled = false)
        }
    }
}

// ------------------------------------------------------------------ chiavi API

@Composable
fun ApiKeysScreen() {
    val s = AppState.settings
    Page("Chiavi API") {
        Hint("Le chiavi restano solo sul tuo telefono. Le fonti con chiave verranno attivate in una prossima versione.")
        Sources.keyed.forEach { src ->
            var text by remember(src.id) { mutableStateOf(s.apiKeys[src.id] ?: "") }
            CardBox {
                Text(src.name, color = LocalAppColors.current.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.trim() }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Chiave API") },
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    AppState.update { st -> st.copy(apiKeys = st.apiKeys + (src.id to text)) }
                }, enabled = text != (s.apiKeys[src.id] ?: "")) { Text("Salva") }
            }
        }
    }
}

// ------------------------------------------------------------------ info

@Composable
fun InfoScreen() {
    val ctx = LocalContext.current
    fun open(url: String) = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) { }
    Page("Informazioni") {
        Group {
            SettingRow("MLMeteo", "Versione ${AppUpdate.installedName(ctx)} · parte delle MLapps", Icons.Filled.Info)
            Divider()
            SettingRow("Controlla aggiornamenti", "Dalle release su GitHub", Icons.Filled.SystemUpdate, onClick = { AppUpdate.open(ctx) })
            if (DONATE_URL.isNotBlank()) {
                Divider()
                SettingRow("Dona", "Sostieni lo sviluppo con PayPal", Icons.Filled.Favorite, onClick = { open(DONATE_URL) })
            }
            if (TELEGRAM_URL.isNotBlank()) {
                Divider()
                SettingRow("Community MLapps", "Gruppo Telegram", Icons.Filled.Groups, onClick = { open(TELEGRAM_URL) })
            }
            Divider()
            SettingRow("Codice sorgente", "github.com/marco90-cyber/MLmeteo", Icons.Filled.Policy, onClick = { open("https://github.com/marco90-cyber/MLmeteo") })
        }
        SectionTitle("Crediti dati")
        CardBox {
            Text(
                "Previsioni: Open-Meteo.com (CC BY 4.0) con i modelli di ECMWF, DWD, Météo-France, ItaliaMeteo ARPAE, UK Met Office, " +
                    "NOAA, MeteoSwiss, KNMI, DMI, JMA, KMA, CMA, Environment Canada.\n" +
                    "YR.no: MET Norway (api.met.no, CC BY 4.0).\n" +
                    "Qualità dell'aria: Copernicus Atmosphere Monitoring Service (via Open-Meteo).\n" +
                    "Radar: Rain Viewer (rainviewer.com).\n" +
                    "Mappe: © OpenStreetMap contributors (openstreetmap.org/copyright).\n" +
                    "Ricerca località: Open-Meteo Geocoding (GeoNames).",
                color = LocalAppColors.current.inkDim, fontSize = 13.sp,
            )
        }
        SectionTitle("Privacy")
        CardBox {
            Text(
                "MLMeteo non raccoglie dati. La posizione viene usata solo sul telefono e inviata, arrotondata, ai servizi meteo per scaricare la previsione.",
                color = LocalAppColors.current.inkDim, fontSize = 13.sp,
            )
        }
    }
}
