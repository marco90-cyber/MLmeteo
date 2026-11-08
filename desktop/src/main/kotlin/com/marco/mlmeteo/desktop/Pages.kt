package com.marco.mlmeteo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlmeteo.AppState
import com.marco.mlmeteo.Fmt
import com.marco.mlmeteo.IconSets
import com.marco.mlmeteo.LocalAppColors
import com.marco.mlmeteo.Place
import com.marco.mlmeteo.SixColors
import com.marco.mlmeteo.Sources
import com.marco.mlmeteo.UiStyles
import com.marco.mlmeteo.WeatherIcon
import com.marco.mlmeteo.WeatherRepo
import com.marco.mlmeteo.conditionText
import com.marco.mlmeteo.levelName
import com.marco.mlmeteo.uiCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.format.DateTimeFormatter

// ------------------------------------------------------------------ Località

object Dbg {
    var query by mutableStateOf("")
    var lastResults: List<Place> = emptyList() }

@Composable
fun PlacesPage() {
    val c = LocalAppColors.current
    val s = AppState.settings
    val fmt = Fmt(s)
    var query by Dbg::query
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        results = try { WeatherRepo.searchPlaces(query.trim()).also { Log.d("Ricerca '${query.trim()}': ${it.size} risultati") } }
            catch (e: Exception) { Log.e("Ricerca '${query.trim()}' non riuscita", e); emptyList() }
        Dbg.lastResults = results
        searching = false
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Località")
        ScrollPage {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Cerca una città (es. Palermo)") },
            )
            if (searching) Text("Cerco…", color = c.inkDim, fontSize = 13.sp)
            results.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().uiCard().clickable { AppState.addPlace(p); query = "" }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = c.ink, fontSize = 15.sp)
                        Text(p.region, color = c.inkDim, fontSize = 12.sp)
                    }
                    Text("Aggiungi", color = c.accent, fontSize = 14.sp)
                }
            }
            Text("Le tue località", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            if (AppState.places.isEmpty()) Text("Nessuna località: cercane una qui sopra.", color = c.inkDim, fontSize = 14.sp)
            AppState.places.forEachIndexed { i, p ->
                val f = AppState.forecasts[p.id]
                val main = AppState.mainPlace()?.id == p.id
                LaunchedEffect(p.id) { AppState.refresh(p) }
                Row(
                    Modifier.fillMaxWidth().uiCard().clickable { AppState.selectedId = p.id; Nav.page = Page.METEO }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (f != null) WeatherIcon(f.current.code, f.current.isDay, 36.dp) else Spacer(Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(p.region.takeIf { it.isNotBlank() }, f?.let { conditionText(it.current.code, it.current.isDay) }).joinToString(" · "), color = c.inkDim, fontSize = 12.sp)
                    }
                    Text(f?.let { fmt.temp(it.current.temp) } ?: "", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { AppState.setMain(p) }) {
                        Icon(if (main) Icons.Outlined.Notifications else Icons.Outlined.NotificationsNone, "Principale (allerte e notifiche)", tint = if (main) c.accent else c.inkDim)
                    }
                    IconButton(onClick = { AppState.movePlace(i, i - 1) }, enabled = i > 0) { Icon(Icons.Outlined.ArrowUpward, "Su", tint = c.inkDim) }
                    IconButton(onClick = { AppState.movePlace(i, i + 1) }, enabled = i < AppState.places.size - 1) { Icon(Icons.Outlined.ArrowDownward, "Giù", tint = c.inkDim) }
                    IconButton(onClick = { AppState.removePlace(p) }) { Icon(Icons.Outlined.Delete, "Rimuovi", tint = c.inkDim) }
                }
            }
            Text("La campanella indica la località principale: allerte, notifiche e icona nell'area di notifica usano quella.", color = c.inkDim, fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------ Allerte

@Composable
fun AlertsPage() {
    val c = LocalAppColors.current
    val fmtT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.ITALY)
    Column(Modifier.fillMaxSize()) {
        PageHeader("Allerte")
        ScrollPage {
            val all = AppState.places.map { it to AppState.alerts[it.id].orEmpty() }
            if (all.all { it.second.isEmpty() }) Card(Modifier.fillMaxWidth()) { Text("Nessuna allerta per le tue località.", color = c.ink, fontSize = 15.sp) }
            all.filter { it.second.isNotEmpty() }.forEach { (p, list) ->
                Text(p.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                list.sortedByDescending { it.level }.forEach { a ->
                    val col = when (a.level) { 3 -> Color(0xFFE53935); 2 -> Color(0xFFFB8C00); else -> Color(0xFFFDD835) }
                    Row(Modifier.fillMaxWidth().uiCard().padding(12.dp)) {
                        Box(Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp)).background(col))
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Allerta ${levelName(a.level)} · ${a.title}", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(a.area + " · " + a.source, color = c.inkDim, fontSize = 12.sp)
                            val period = listOfNotNull(a.start?.format(fmtT), a.end?.format(fmtT)).joinToString(" → ")
                            if (period.isNotBlank()) Text(period, color = c.inkDim, fontSize = 12.sp)
                            if (a.detail.isNotBlank()) Text(a.detail, color = c.ink, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Radar

@Composable
fun RadarPage() {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxSize()) {
        PageHeader("Radar")
        ScrollPage {
            Card(Modifier.fillMaxWidth()) {
                Text("Radar e mappe in arrivo", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Il radar della Protezione Civile, il nowcast, i fulmini e le mappe dei modelli (ICON-2I, WRF, ICON-EU, ECMWF, GFS, Multimodello) arrivano con il prossimo aggiornamento della versione per Windows.",
                    color = c.inkDim, fontSize = 14.sp,
                )
                Text("Apri il radar della Protezione Civile nel browser", color = c.accent, fontSize = 14.sp,
                    modifier = Modifier.clickable { WinSys.open("https://mappe.protezionecivile.gov.it/it/mappe-rischi/piattaforma-radar/") })
            }
        }
    }
}

// ------------------------------------------------------------------ Impostazioni (come Windows 11: categorie a sinistra)

private enum class SetCat(val title: String) { LOOK("Aspetto"), SOURCE("Fonte dati"), UNITS("Unità"), NOTIF("Notifiche"), SYSTEM("Sistema"), INFO("Info") }

@Composable
fun SettingsPage() {
    val c = LocalAppColors.current
    var cat by remember { mutableStateOf(SetCat.LOOK) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Impostazioni")
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(190.dp).fillMaxHeight().padding(start = 16.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SetCat.entries.forEach { k ->
                    val sel = k == cat
                    Text(
                        k.title, color = c.ink, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (sel) c.card else Color.Transparent)
                            .clickable { cat = k }.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                ScrollPage {
                    when (cat) {
                        SetCat.LOOK -> LookSettings()
                        SetCat.SOURCE -> SourceSettings()
                        SetCat.UNITS -> UnitSettings()
                        SetCat.NOTIF -> NotifSettings()
                        SetCat.SYSTEM -> SystemSettings()
                        SetCat.INFO -> InfoSettings()
                    }
                }
            }
        }
    }
}

@Composable
private fun SetRow(title: String, sub: String? = null, trailing: @Composable () -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().uiCard().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.sp)
            if (sub != null) Text(sub, color = c.inkDim, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun Toggle(v: Boolean, on: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    Switch(v, on, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = c.accent))
}

@Composable
private fun Choices(items: List<Pair<String, String>>, value: String, on: (String) -> Unit) = Tabs(items, value, on)

@Composable
private fun LookSettings() {
    val c = LocalAppColors.current
    val s = AppState.settings
    SetRow("Stile dell'interfaccia", UiStyles.firstOrNull { it.first == s.uiStyle }?.third) {
        Choices(UiStyles.map { it.first to it.second }, s.uiStyle) { v -> AppState.update { it.copy(uiStyle = v) } }
    }
    SetRow("Modalità") {
        Choices(listOf("auto" to "Come Windows", "light" to "Chiaro", "dark" to "Scuro", "amoled" to "AMOLED"), s.mode) { v -> AppState.update { it.copy(mode = v) } }
    }
    SetRow("Colore d'accento di Windows", "Altrimenti usa il colore scelto qui sotto") { Toggle(s.useWallpaper) { v -> AppState.update { it.copy(useWallpaper = v) } } }
    Row(Modifier.fillMaxWidth().uiCard().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SixColors.forEach { (argb, _) ->
            val sel = !s.useWallpaper && s.manualColor == argb
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(argb)).border(3.dp, if (sel) c.ink else Color.Transparent, CircleShape)
                .clickable { AppState.update { it.copy(useWallpaper = false, manualColor = argb) } })
        }
    }
    SetRow("Animazioni", "Icone e sfondo dell'intestazione") {
        Choices(listOf("once" to "All'apertura", "always" to "Sempre", "icons" to "Solo icone", "off" to "No"), s.animations) { v -> AppState.update { it.copy(animations = v) } }
    }
    SetRow("Zoom", "Ctrl + rotellina non serve: scegli qui la grandezza di testo e schede") {
        Slider(AppState.zoom.toFloat(), { AppState.zoom = (it / 10).toInt() * 10; AppState.save() }, valueRange = 90f..150f, modifier = Modifier.width(220.dp),
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
        Text("${AppState.zoom}%", color = c.inkDim, fontSize = 13.sp)
    }
    Text("Icone meteo", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    IconSets.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (id, name, desc) ->
                val sel = s.iconSet == id
                Column(
                    Modifier.weight(1f).uiCard().border(2.dp, if (sel) c.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { AppState.update { it.copy(iconSet = id) } }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(0, 2, 63, 95).forEach { WeatherIcon(it, true, 30.dp, set = id) } }
                    Text(name, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(desc, color = c.inkDim, fontSize = 12.sp)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SourceSettings() {
    val c = LocalAppColors.current
    val s = AppState.settings
    (listOf(Sources.consensus) + Sources.free).forEach { src ->
        Row(
            Modifier.fillMaxWidth().uiCard().clickable { AppState.update { it.copy(sourceId = src.id) } }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(s.sourceId == src.id, { AppState.update { it.copy(sourceId = src.id) } })
            Column {
                Text(src.name, color = c.ink, fontSize = 14.sp)
                Text(if (src.id == "consensus") "Media pesata di ECMWF, AIFS, ICON, ItaliaMeteo, Météo-France e Met Office" else src.area, color = c.inkDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun UnitSettings() {
    val s = AppState.settings
    SetRow("Temperatura") { Choices(listOf("C" to "°C", "F" to "°F"), s.tempUnit) { v -> AppState.update { it.copy(tempUnit = v) } } }
    SetRow("Vento") { Choices(listOf("kmh" to "km/h", "ms" to "m/s", "kn" to "nodi", "bft" to "Beaufort"), s.windUnit) { v -> AppState.update { it.copy(windUnit = v) } } }
    SetRow("Pioggia") { Choices(listOf("mm" to "mm", "in" to "pollici"), s.rainUnit) { v -> AppState.update { it.copy(rainUnit = v) } } }
    SetRow("Pressione") { Choices(listOf("hpa" to "hPa", "mmhg" to "mmHg", "inhg" to "inHg"), s.pressureUnit) { v -> AppState.update { it.copy(pressureUnit = v) } } }
    SetRow("Orologio a 24 ore") { Toggle(s.clock24) { v -> AppState.update { it.copy(clock24 = v) } } }
    SetRow("Giorni di previsione") { Choices(listOf("7" to "7", "10" to "10", "14" to "14", "16" to "16"), s.daysCount.toString()) { v -> AppState.update { it.copy(daysCount = v.toInt()) } } }
}

@Composable
private fun NotifSettings() {
    val s = AppState.settings
    SetRow("Allerte meteo", "Notifica di Windows per la località principale") { Toggle(s.notifAlerts) { v -> AppState.update { it.copy(notifAlerts = v) } } }
    SetRow("Livello minimo") {
        Choices(listOf("1" to "Gialla", "2" to "Arancione", "3" to "Rossa"), s.alertMinLevel.toString()) { v -> AppState.update { it.copy(alertMinLevel = v.toInt()) } }
    }
}

@Composable
private fun SystemSettings() {
    val s = AppState.settings
    SetRow("Chiudi nell'area di notifica", "La X nasconde la finestra: MLMeteo resta vicino all'orologio") {
        Toggle(AppState.closeToTray) { AppState.closeToTray = it; AppState.save() }
    }
    SetRow("Avvia con Windows", "Parte nascosta nell'area di notifica") {
        Toggle(AppState.autostart) { AppState.autostart = it; WinSys.setAutostart(it); AppState.save() }
    }
    SetRow("Aggiornamento automatico") {
        Choices(listOf("30" to "30 min", "60" to "1 ora", "120" to "2 ore", "180" to "3 ore"), s.updateFreq.toString()) { v -> AppState.update { it.copy(updateFreq = v.toInt()) } }
    }
}

@Composable
private fun InfoSettings() {
    val c = LocalAppColors.current
    val version = System.getProperty("jpackage.app-version") ?: "sviluppo"
    var latest by remember { mutableStateOf<Pair<String, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SetRow("MLMeteo per Windows", "Versione $version · parte delle MLapps") {}
    SetRow("Aggiornamenti", latest?.let { (tag, _) -> if (tag.removePrefix("v") == version) "Hai l'ultima versione" else "Disponibile la $tag" } ?: "Controlla se c'è una versione nuova") {
        val l = latest
        if (l != null && l.first.removePrefix("v") != version) {
            Text("Scarica", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable { WinSys.open(l.second) })
        } else {
            Text(if (checking) "Controllo…" else "Controlla", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable {
                checking = true
                scope.launch {
                    latest = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val o = JSONObject(com.marco.mlmeteo.Net.get("https://api.github.com/repos/marco90-cyber/MLmeteo/releases/latest"))
                            val assets = o.getJSONArray("assets")
                            val msi = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".msi") }
                            o.getString("tag_name") to (msi?.getString("browser_download_url") ?: o.getString("html_url"))
                        }
                    } catch (e: Exception) { null }
                    checking = false
                }
            })
        }
    }
    var showLog by remember { mutableStateOf(false) }
    var verbose by remember { mutableStateOf(Log.verbose) }
    SetRow("Registro (log)", "Memoria: ${Log.memory()} · ${Log.file.absolutePath}") {
        Text(if (showLog) "Nascondi" else "Mostra", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable { showLog = !showLog })
        Spacer(Modifier.width(16.dp))
        Text("Apri cartella", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable {
            try { java.awt.Desktop.getDesktop().open(Log.dir) } catch (e: Exception) { }
        })
        Spacer(Modifier.width(16.dp))
        Text("Copia", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable {
            try {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(Log.tail(500)), null)
            } catch (e: Exception) { }
        })
    }
    SetRow("Log dettagliato", "Registra anche ricerche e aggiornamenti (utile per segnalare un problema)") {
        Toggle(verbose) { verbose = it; Log.verbose = it; Log.i("Log dettagliato: $it") }
    }
    if (showLog) {
        Text(
            Log.tail(200).ifBlank { "Il registro è vuoto." }, color = c.ink, fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().uiCard().padding(12.dp),
        )
    }
    SetRow("Codice sorgente", "github.com/marco90-cyber/MLmeteo") {
        Text("Apri", color = c.accent, fontSize = 14.sp, modifier = Modifier.clickable { WinSys.open("https://github.com/marco90-cyber/MLmeteo") })
    }
    Text("Dati: Open-Meteo, MET Norway, Copernicus CAMS, Protezione Civile, MeteoAlarm. Icone disegnate da MLMeteo.", color = c.inkDim, fontSize = 12.sp)
}
