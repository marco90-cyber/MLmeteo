package com.marco.mlmeteo

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Procedura guidata che si apre quando metti un widget in Home (o lo modifichi):
 * in alto l'anteprima dal vivo, sotto pochi passaggi.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        AppState.init(this)
        val kind = Widgets.kindOf(this, id)
        val start = WidgetStore.cfg(this, id)
        setContent {
            MLTheme(AppState.settings, null) {
                WidgetWizard(id, kind, start,
                    onDone = { cfg ->
                        WidgetStore.save(this, id, cfg)
                        Widgets.update(this, AppWidgetManager.getInstance(this), id, kind)
                        if (kind == WidgetKind.RADAR) Widgets.refreshNow(this)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    },
                    onCancel = { finish() })
            }
        }
    }
}

private enum class Step(val title: String) {
    PLACE("Località"), CONTENT("Cosa mostrare"), EXTRAS("Dati extra"), SIZE("Grandezza"), LOOK("Aspetto"), TAP("Tocco")
}

@Composable
private fun WidgetWizard(id: Int, kind: WidgetKind, start: WidgetCfg, onDone: (WidgetCfg) -> Unit, onCancel: () -> Unit) {
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    val steps = when (kind) {
        WidgetKind.WEATHER -> Step.entries.toList()
        else -> listOf(Step.PLACE, Step.LOOK, Step.TAP)
    }
    var cfg by remember { mutableStateOf(start) }
    var step by remember { mutableIntStateOf(0) }
    // grandezza dell'anteprima: quella reale oppure una delle misure tipiche
    val real = remember { Widgets.sizeDp(ctx, AppWidgetManager.getInstance(ctx), id, kind) }
    var previewSize by remember { mutableStateOf("real") }
    val size = when (previewSize) {
        "4x1" -> 320 to 90
        "4x2" -> 320 to 190
        "4x4" -> 320 to 400
        else -> real
    }
    BackHandler { if (step > 0) step-- else onCancel() }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        // --- barra in alto
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (step > 0) step-- else onCancel() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(when (kind) { WidgetKind.WEATHER -> "Widget MLMeteo"; WidgetKind.MINI -> "Widget Mini"; WidgetKind.RADAR -> "Widget Radar"; WidgetKind.RAIN -> "Widget Pioggia" },
                    color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text("Passo ${step + 1} di ${steps.size} · ${steps[step].title}", color = c.inkDim, fontSize = 13.sp)
            }
        }
        // --- anteprima dal vivo (su uno sfondo finto, così si vede la trasparenza)
        val place = Widgets.placeFor(ctx, id, cfg)
        val bmp = remember(cfg, size, place?.id) { Widgets.render(ctx, kind, cfg, place, size.first, size.second).bitmap.asImageBitmap() }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.55f), Color(0xFF3A6073), Color(0xFF16222A))))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = size.first.toFloat() / size.second
            Image(bmp, contentDescription = "Anteprima",
                modifier = Modifier.heightIn(max = 260.dp).widthIn(max = maxOf(size.first, 60).dp).aspectRatio(ratio))
        }
        if (kind == WidgetKind.WEATHER) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Segmented(listOf("real" to "Attuale", "4x1" to "4×1", "4x2" to "4×2", "4x4" to "4×4"), previewSize) { previewSize = it }
            }
        }
        Text("Se non c'è spazio, le parti in fondo si nascondono: allarga il widget per vederle.",
            color = c.inkDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
        // indicatore dei passaggi
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
            steps.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(width = if (i == step) 22.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape).background(if (i <= step) c.accent else c.outline))
            }
        }
        // --- passaggio corrente
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (steps[step]) {
                Step.PLACE -> PlaceStep(cfg) { cfg = it }
                Step.CONTENT -> ContentStep(cfg) { cfg = it }
                Step.EXTRAS -> ExtrasStep(cfg) { cfg = it }
                Step.SIZE -> SizeStep(cfg) { cfg = it }
                Step.LOOK -> LookStep(cfg, kind) { cfg = it }
                Step.TAP -> TapStep(cfg, kind) { cfg = it }
            }
            Spacer(Modifier.height(8.dp))
        }
        // --- pulsanti
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Indietro", color = c.inkDim, fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            val last = step == steps.lastIndex
            Box(
                Modifier.clip(RoundedCornerShape(24.dp)).background(c.accent)
                    .clickable { if (last) onDone(cfg) else step++ }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text(if (last) "Fatto" else "Avanti", color = if (c.dark) Color(0xFF16151C) else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PlaceStep(cfg: WidgetCfg, set: (WidgetCfg) -> Unit) {
    val c = LocalAppColors.current
    Hint("Il widget usa la stessa fonte dati dell'app. Con \"Posizione attuale\" segue il GPS.")
    Group {
        val items = listOf(Triple("main", "Località principale", "Quella con la campanella")) +
            AppState.places.map { Triple(it.id, it.name, if (it.gps) "Posizione attuale (GPS)" else it.region) }
        items.forEachIndexed { i, (pid, name, sub) ->
            if (i > 0) Divider()
            Row(
                Modifier.fillMaxWidth().clickable { set(cfg.copy(place = pid)) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = cfg.place == pid, onClick = { set(cfg.copy(place = pid)) })
                val icon = when { pid == "main" -> Icons.Filled.Notifications; AppState.places.firstOrNull { it.id == pid }?.gps == true -> Icons.Filled.Navigation; else -> Icons.Filled.Place }
                Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = c.ink, fontSize = 16.sp)
                    if (sub.isNotBlank()) Text(sub, color = c.inkDim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ContentStep(cfg: WidgetCfg, set: (WidgetCfg) -> Unit) {
    Hint("Le parti vengono disegnate dall'alto in questo ordine, finché c'è spazio.")
    Group {
        SwitchRow("Meteo attuale", "Icona grande, temperatura, massima e minima", checked = cfg.showCurrent) { set(cfg.copy(showCurrent = it)) }
        Divider()
        SwitchRow("Prossime ore", "Icona e temperatura ora per ora", checked = cfg.showHours) { set(cfg.copy(showHours = it)) }
        Divider()
        SwitchRow("Grafico pioggia", "Prossime 24 ore, in mm e probabilità", checked = cfg.showRain) { set(cfg.copy(showRain = it)) }
        Divider()
        SwitchRow("Prossimi giorni", "Quanti ne entrano", checked = cfg.showDays) { set(cfg.copy(showDays = it)) }
    }
    Group {
        SwitchRow("Pallino allerta", "Giallo, arancione o rosso quando c'è un'allerta", checked = cfg.showAlert) { set(cfg.copy(showAlert = it)) }
        Divider()
        SwitchRow("Ultimo aggiornamento", "Orario scritto in piccolo sotto il nome", checked = cfg.showUpdated) { set(cfg.copy(showUpdated = it)) }
    }
}

@Composable
private fun ExtrasStep(cfg: WidgetCfg, set: (WidgetCfg) -> Unit) {
    val c = LocalAppColors.current
    Hint("Scegline fino a 4: compaiono come una riga di icone sotto la temperatura. Se il widget è stretto se ne vedono meno.")
    Group {
        WidgetExtras.forEachIndexed { i, (key, label) ->
            if (i > 0) Divider()
            val on = key in cfg.extras
            val full = cfg.extras.size >= 4 && !on
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(enabled = !full) { set(cfg.copy(extras = if (on) cfg.extras - key else cfg.extras + key)) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = if (full) c.inkDim else c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(24.dp).clip(RoundedCornerShape(7.dp))
                        .background(if (on) c.accent else Color.Transparent)
                        .border(2.dp, if (on) c.accent else c.outline, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Icon(Icons.Filled.Check, null, tint = if (c.dark) Color(0xFF16151C) else Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    Hint("Scelti: ${cfg.extras.size} di 4")
}

@Composable
private fun SizeStep(cfg: WidgetCfg, set: (WidgetCfg) -> Unit) {
    val c = LocalAppColors.current
    Hint("Ingrandisci o rimpicciolisci ogni parte. Se non c'è più spazio, si nascondono prima le parti in fondo.")
    val shown = WidgetSizeKeys.filter { (k, _) ->
        when (k) {
            "current" -> cfg.showCurrent
            "extras" -> cfg.extras.isNotEmpty()
            "hoursIcon", "hoursText" -> cfg.showHours
            "rain" -> cfg.showRain
            else -> cfg.showDays
        }
    }
    shown.forEach { (key, label) ->
        val v = cfg.sizes[key] ?: 100
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("$v%", color = c.inkDim, fontSize = 14.sp)
            }
            Slider(
                value = v.toFloat(), valueRange = 70f..150f,
                onValueChange = { x -> set(cfg.copy(sizes = cfg.sizes + (key to ((x / 5).roundToInt() * 5)))) },
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
            )
        }
    }
    if (cfg.sizes.isNotEmpty()) {
        TextButton(onClick = { set(cfg.copy(sizes = emptyMap())) }) { Text("Ripristina tutto a 100%", color = c.accent) }
    }
}

@Composable
private fun LookStep(cfg: WidgetCfg, kind: WidgetKind, set: (WidgetCfg) -> Unit) {
    val c = LocalAppColors.current
    if (kind != WidgetKind.RADAR) {
        SectionTitle("Sfondo")
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opacità", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${cfg.opacity}%", color = c.inkDim, fontSize = 14.sp)
            }
            Slider(
                value = cfg.opacity.toFloat(), onValueChange = { set(cfg.copy(opacity = (it / 5).roundToInt() * 5)) }, valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
            )
            Text("Con lo sfondo trasparente il testo prende un'ombra per restare leggibile.", color = c.inkDim, fontSize = 12.sp)
        }
    }
    SectionTitle("Colori")
    Segmented(listOf("app" to "Come l'app", "wallpaper" to "Dallo sfondo", "custom" to "Tinta"), cfg.colorMode) { set(cfg.copy(colorMode = it)) }
    if (cfg.colorMode == "custom") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SixColors.forEach { (argb, _) ->
                val sel = cfg.customColor == argb
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(argb))
                        .border(if (sel) 3.dp else 0.dp, c.ink, CircleShape)
                        .clickable { set(cfg.copy(customColor = argb)) },
                )
            }
        }
    }
    if (cfg.colorMode == "wallpaper" && android.os.Build.VERSION.SDK_INT < 31) Hint("I colori dello sfondo servono Android 12 o successivo.")
    SectionTitle("Testo")
    Segmented(listOf("auto" to "Automatico", "light" to "Chiaro", "dark" to "Scuro"), cfg.textMode) { set(cfg.copy(textMode = it)) }
    SectionTitle("Angoli")
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Arrotondamento", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (cfg.corner == 0) "Squadrati" else "${cfg.corner} dp", color = c.inkDim, fontSize = 14.sp)
        }
        Slider(
            value = cfg.corner.toFloat(), onValueChange = { set(cfg.copy(corner = (it / 2).roundToInt() * 2)) }, valueRange = 0f..40f,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
        )
    }
}

@Composable
private fun TapStep(cfg: WidgetCfg, kind: WidgetKind, set: (WidgetCfg) -> Unit) {
    Hint("Cosa succede quando tocchi le varie parti del widget. Il pallino dell'allerta apre sempre l'app.")
    @Composable
    fun zone(title: String, value: String, onPick: (String) -> Unit) {
        SectionTitle(title)
        Segmented(listOf("app" to "App", "radar" to "Radar", "refresh" to "Aggiorna"), value, onPick)
    }
    if (kind == WidgetKind.WEATHER) {
        zone("Parte alta (meteo attuale)", cfg.tapMain) { set(cfg.copy(tapMain = it)) }
        if (cfg.showHours) zone("Prossime ore", cfg.tapHours) { set(cfg.copy(tapHours = it)) }
        if (cfg.showRain) zone("Grafico pioggia", cfg.tapRain) { set(cfg.copy(tapRain = it)) }
        if (cfg.showDays) zone("Prossimi giorni", cfg.tapDays) { set(cfg.copy(tapDays = it)) }
    } else {
        zone("Tocco sul widget", cfg.tapMain) { set(cfg.copy(tapMain = it)) }
    }
}

// ------------------------------------------------------------------ pagina "Widget" nelle impostazioni

@Composable
fun WidgetsScreen() {
    val ctx = LocalContext.current
    val c = LocalAppColors.current
    var version by remember { mutableIntStateOf(0) }
    val list = remember(version) { Widgets.all(ctx).filter { !WidgetStore.isRemoved(ctx, it.first) } }
    var confirm by remember { mutableStateOf<Int?>(null) }
    Page("Widget") {
        Hint("Per aggiungerne uno: tieni premuto sulla schermata Home → Widget → MLMeteo. Si apre la procedura guidata.")
        if (list.isEmpty()) {
            CardBox { Text("Non hai widget di MLMeteo attivi.", color = c.inkDim, fontSize = 14.sp) }
        } else {
            SectionTitle("I tuoi widget")
            Group {
                list.forEachIndexed { i, (wid, kind) ->
                    if (i > 0) Divider()
                    val cfg = WidgetStore.cfg(ctx, wid)
                    val place = Widgets.placeFor(ctx, wid, cfg)
                    SettingRow(
                        when (kind) { WidgetKind.WEATHER -> "MLMeteo"; WidgetKind.MINI -> "Mini"; WidgetKind.RADAR -> "Radar"; WidgetKind.RAIN -> "Pioggia" },
                        (if (cfg.place == "main") "Località principale" else place?.name ?: "—") + " · tocca per modificare",
                        when (kind) { WidgetKind.WEATHER -> Icons.Filled.Widgets; WidgetKind.MINI -> Icons.Filled.Thermostat; WidgetKind.RADAR -> Icons.Filled.Radar; WidgetKind.RAIN -> Icons.Filled.WaterDrop },
                        onClick = {
                            ctx.startActivity(Intent(ctx, WidgetConfigActivity::class.java)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        trailing = {
                            IconButton(onClick = { confirm = wid }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Rimuovi", tint = c.inkDim)
                            }
                        },
                    )
                }
            }
            Hint("Android non permette alle app di togliere un widget dalla Home: \"Rimuovi\" lo ferma (non si aggiorna più) e poi lo togli tenendolo premuto. Così spariscono anche i widget fantasma che alcuni launcher lasciano registrati.")
        }
        SectionTitle("Tipi di widget")
        Group {
            SettingRow("MLMeteo", "Si adatta alla grandezza: meteo, dati extra, ore, pioggia e giorni", Icons.Filled.Widgets)
            Divider()
            SettingRow("Pioggia", "Grafico di probabilità e millimetri nelle prossime ore", Icons.Filled.WaterDrop)
            Divider()
            SettingRow("Mini", "1×1: solo icona e temperatura", Icons.Filled.Thermostat)
            Divider()
            SettingRow("Radar", "Mappa con il radar della Protezione Civile: più è grande, più zona mostra", Icons.Filled.Radar)
        }
    }
    confirm?.let { wid ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = c.card,
            title = { Text("Rimuovere il widget?", color = c.ink) },
            text = { Text("Smetterà di aggiornarsi e mostrerà \"Widget rimosso\". Poi tienilo premuto sulla Home per toglierlo.", color = c.inkDim) },
            confirmButton = {
                TextButton(onClick = {
                    WidgetStore.markRemoved(ctx, wid)
                    Widgets.update(ctx, AppWidgetManager.getInstance(ctx), wid, Widgets.kindOf(ctx, wid))
                    confirm = null; version++
                }) { Text("Rimuovi", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla", color = c.inkDim) } },
        )
    }
}
