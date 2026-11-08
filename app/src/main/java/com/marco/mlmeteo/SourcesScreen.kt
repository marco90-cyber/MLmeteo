package com.marco.mlmeteo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun currentPlace(): Place? = AppState.places.let { it.getOrNull(Nav.page) ?: it.firstOrNull() }

@Composable
fun SourcesScreen() {
    val c = LocalAppColors.current
    val s = AppState.settings
    val fmt = Fmt(s)
    val place = currentPlace()
    var compare by remember { mutableStateOf<Map<String, Pair<Double, Int>?>?>(null) }
    LaunchedEffect(place?.id) {
        if (place != null) compare = try { WeatherRepo.compareAll(place) } catch (e: Exception) { emptyMap() }
    }
    fun pick(id: String) {
        AppState.update { it.copy(sourceId = id) }
    }
    Page("Fonte dati") {
        Hint("La fonte scelta vale per tutte le località." + (place?.let { " Temperature attuali a ${it.name}." } ?: ""))

        val cons = compare?.filterKeys { it in Sources.consensusMembers }?.values?.filterNotNull()?.map { it.first }?.takeIf { it.isNotEmpty() }?.average()
        Row(
            Modifier.fillMaxWidth().uiCard(if (s.sourceId == "consensus") c.accentContainer.copy(alpha = 0.5f) else c.card)
                .clickable { pick("consensus") }.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = s.sourceId == "consensus", onClick = { pick("consensus") })
            Column(Modifier.weight(1f)) {
                Text("Multimodello", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Media pesata di ECMWF, AIFS, ICON, ItaliaMeteo, Météo-France e Met Office: nelle prime 48 ore pesano di più i modelli ad alta risoluzione, poi ECMWF", color = c.inkDim, fontSize = 13.sp)
            }
            Text(cons?.let { fmt.temp(it) } ?: "", color = c.ink, fontSize = 28.sp, modifier = Modifier.padding(end = 8.dp))
        }

        SectionTitle("Gratuite, senza chiave")
        Group {
            Sources.free.forEachIndexed { i, src ->
                if (i > 0) Divider()
                val v = compare?.get(src.id)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { pick(src.id) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.sourceId == src.id, onClick = { pick(src.id) })
                    Column(Modifier.weight(1f)) {
                        Text(src.name, color = c.ink, fontSize = 15.sp)
                        Text(
                            when {
                                compare == null -> src.area
                                v == null -> "Non disponibile qui"
                                else -> conditionText(v.second)
                            },
                            color = c.inkDim, fontSize = 13.sp,
                        )
                    }
                    when {
                        compare == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                        v != null -> {
                            WeatherIcon(v.second, true, 28.dp)
                            Gap(6)
                            Text(fmt.temp(v.first), color = c.ink, fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
            }
        }

        SectionTitle("Con la tua chiave API")
        Group {
            Sources.keyed.forEachIndexed { i, src ->
                if (i > 0) Divider()
                SettingRow(src.name, if (s.apiKeys[src.id].isNullOrBlank()) "Chiave non inserita" else "Chiave salvata · in arrivo", Icons.Filled.Key) {
                    FilledTonalButton(onClick = { Nav.go(Screen.API_KEYS) }) { Text("Chiave", fontSize = 13.sp) }
                }
            }
        }
        Hint("Le fonti con chiave verranno attivate in una prossima versione: puoi già salvare la chiave.")

        SectionTitle("Altri dati")
        Group {
            RadarSourceRow()
            Divider()
            SettingRow("Qualità dell'aria", "Copernicus CAMS (via Open-Meteo)", Icons.Filled.Air)
        }
    }
}

@Composable
fun RadarSourceRow() {
    val s = AppState.settings
    ChoiceRow(
        title = "Radar",
        icon = Icons.Filled.Radar,
        choices = RadarSource.entries.map { Choice(it.id, it.title, it.info, it.available) },
        selected = s.radarId,
        onPick = { id -> AppState.update { it.copy(radarId = id) } },
    )
}
