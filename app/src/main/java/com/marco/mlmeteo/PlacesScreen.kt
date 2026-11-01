package com.marco.mlmeteo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun PlacesScreen() {
    val c = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); searchError = null; return@LaunchedEffect }
        delay(350)
        searching = true
        try {
            results = WeatherRepo.searchPlaces(query)
            searchError = if (results.isEmpty()) "Nessun risultato" else null
        } catch (e: Exception) {
            searchError = e.message ?: "Errore di rete"
        }
        searching = false
    }
    Page("Località") {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            label = { Text("Cerca città o paese") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Cancella") }
            },
        )
        if (query.trim().length >= 2) {
            if (searching) CircularProgressIndicator(color = c.accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            searchError?.let { Hint(it) }
            Group {
                results.forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    SettingRow(p.name, p.region, onClick = {
                        AppState.addPlace(p)
                        query = ""
                        Nav.page = AppState.places.indexOfFirst { it.id == p.id || roughKm(it, p) < 1.0 }.coerceAtLeast(0)
                    })
                }
            }
            return@Page
        }

        val s = AppState.settings
        val gps = AppState.gpsPlace
        if (s.useGps) {
            if (gps != null) PlaceCard(gps, -1, s.mainPlaceId == "gps")
            else Hint(AppState.gpsStatus.ifBlank { "Posizione GPS non ancora disponibile." })
        }
        val saved = AppState.savedPlaces
        val rowHeight = with(LocalDensity.current) { 124.dp.toPx() }
        var dragIndex by remember { mutableStateOf(-1) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        saved.forEachIndexed { i, p ->
            val currentIndex by rememberUpdatedState(i)
            val handle = Modifier.pointerInput(p.id) {
                detectDragGestures(
                    onDragStart = { dragIndex = currentIndex; dragOffset = 0f },
                    onDragEnd = { dragIndex = -1; dragOffset = 0f },
                    onDragCancel = { dragIndex = -1; dragOffset = 0f },
                ) { change, amount ->
                    change.consume()
                    dragOffset += amount.y
                    val idx = dragIndex
                    if (dragOffset > rowHeight * 0.6f && idx < AppState.savedPlaces.lastIndex) {
                        AppState.movePlace(idx, idx + 1); dragIndex = idx + 1; dragOffset -= rowHeight
                    } else if (dragOffset < -rowHeight * 0.6f && idx > 0) {
                        AppState.movePlace(idx, idx - 1); dragIndex = idx - 1; dragOffset += rowHeight
                    }
                }
            }
            Column(Modifier.graphicsLayer { translationY = if (dragIndex == i) dragOffset else 0f }) {
                PlaceCard(p, i, s.mainPlaceId == p.id, handle)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
            Gap(8)
            Text(
                "La campanella indica la località per le allerte. Trascina la maniglia per riordinare. Cerca in alto per aggiungere.",
                color = c.inkDim, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun PlaceCard(p: Place, index: Int, main: Boolean, handle: Modifier? = null) {
    val c = LocalAppColors.current
    val s = AppState.settings
    val f = AppState.forecasts[p.id]
    val fmt = Fmt(s)
    LaunchedEffect(p.id) { AppState.refreshIfOld(p) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(24.dp))
            .background(if (main) c.accentContainer.copy(alpha = 0.45f) else c.card)
            .clickable {
                Nav.page = AppState.places.indexOfFirst { it.id == p.id }.coerceAtLeast(0)
                Nav.back()
            }
            .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.gps) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, tint = c.accent, modifier = Modifier.size(14.dp).rotate(45f))
                    Gap(4)
                }
                Text(p.name, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(p.region, color = c.inkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f != null) {
                Text(
                    "${conditionText(f.current.code, f.current.isDay)} · ${fmt.temp(f.days.firstOrNull()?.tMax ?: Double.NaN)} / ${fmt.temp(f.days.firstOrNull()?.tMin ?: Double.NaN)}",
                    color = c.inkDim, fontSize = 13.sp, maxLines = 1,
                )
            }
        }
        if (f != null) {
            WeatherIcon(f.current.code, f.current.isDay, 32.dp)
            Text(fmt.temp(f.current.temp), color = c.ink, fontSize = 30.sp, modifier = Modifier.padding(start = 6.dp))
        }
        IconButton(onClick = { AppState.update { it.copy(mainPlaceId = p.id) } }) {
            Icon(
                if (main) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
                contentDescription = if (main) "Località per le allerte" else "Usa per le allerte",
                tint = if (main) c.accent else c.inkDim,
            )
        }
        if (!p.gps) {
            Column {
                IconButton(onClick = { AppState.removePlace(p) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Elimina", tint = c.inkDim)
                }
                if (handle != null) {
                    Icon(Icons.Filled.DragHandle, contentDescription = "Trascina per riordinare", tint = c.inkDim,
                        modifier = handle.padding(12.dp).size(24.dp))
                }
            }
        }
    }
}
