package com.marco.mlmeteo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
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
    val scroll = rememberScrollState()
    Page("Località", scroll) {
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
        ReorderablePlaces(scroll, s.mainPlaceId)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
            Gap(8)
            Text(
                "La campanella indica la località per le allerte. Trascina la maniglia (o tieni premuta la località) per riordinare. Cerca in alto per aggiungere.",
                color = c.inkDim, fontSize = 13.sp,
            )
        }
    }
}

/**
 * Località salvate riordinabili: si trascina dalla maniglia (subito) o tenendo premuta la card.
 * Durante il trascinamento la lista NON viene modificata: la card sollevata segue il dito e le
 * altre scivolano per farle posto; l'ordine viene salvato solo al rilascio.
 */
@Composable
private fun ReorderablePlaces(scroll: ScrollState, mainId: String?) {
    val saved = AppState.savedPlaces
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val gapPx = with(density) { 12.dp.toPx() }
    val edgePx = with(density) { 96.dp.toPx() }
    val topLimitPx = with(density) { 140.dp.toPx() }
    val windowH = LocalView.current.rootView.height.toFloat()
    val scope = rememberCoroutineScope()

    val heights = remember { mutableStateMapOf<String, Int>() }
    val tops = remember { mutableStateMapOf<String, Float>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(-1) }
    val settle = remember { Animatable(0f) }
    var settleId by remember { mutableStateOf<String?>(null) }

    // le funzioni sotto sono chiamate dai gesti: leggono sempre lo stato attuale, non valori catturati
    fun list() = AppState.savedPlaces
    fun fromIdx() = list().indexOfFirst { it.id == dragId }
    fun h(i: Int) = (heights[list()[i].id] ?: 0) + gapPx
    val from = fromIdx()

    fun retarget() {
        val from = fromIdx()
        val saved = list()
        if (from < 0) return
        var t = from
        if (dragOffset > 0) {
            var acc = 0f
            for (k in from + 1..saved.lastIndex) { acc += h(k); if (dragOffset > acc - h(k) / 2) t = k else break }
        } else {
            var acc = 0f
            for (k in from - 1 downTo 0) { acc += h(k); if (-dragOffset > acc - h(k) / 2) t = k else break }
        }
        if (t != target) { if (target >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); target = t }
    }

    fun start(id: String, startFingerY: Float) {
        scope.launch { settle.snapTo(0f) }
        settleId = null
        dragId = id; dragOffset = 0f; fingerY = startFingerY
        target = list().indexOfFirst { it.id == id }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun end(commit: Boolean) {
        val f = fromIdx()
        val id = dragId ?: return
        val t = target
        var residual = dragOffset
        if (commit && f >= 0 && t >= 0 && t != f) {
            // la card finisce nella nuova posizione: tolgo lo spazio già percorso
            residual -= if (t > f) (f + 1..t).sumOf { h(it).toDouble() }.toFloat()
            else -(t until f).sumOf { h(it).toDouble() }.toFloat()
            AppState.movePlace(f, t)
        }
        dragId = null; dragOffset = 0f; target = -1
        settleId = id
        scope.launch { settle.snapTo(residual); settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)); if (settleId == id) settleId = null }
    }

    // scorrimento automatico quando il dito si avvicina ai bordi dello schermo
    LaunchedEffect(dragId) {
        if (dragId == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val speed = when {
                fingerY < topLimitPx + edgePx -> -(1f - ((fingerY - topLimitPx) / edgePx).coerceIn(0f, 1f))
                fingerY > windowH - edgePx -> (1f - ((windowH - fingerY) / edgePx).coerceIn(0f, 1f))
                else -> 0f
            } * 28f
            if (speed != 0f) {
                val used = scroll.scrollBy(speed)
                if (used != 0f) { dragOffset += used; retarget() }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        saved.forEachIndexed { i, p ->
            key(p.id) {
                val isDragged = p.id == dragId
                val dh = (dragId?.let { heights[it] } ?: 0) + gapPx
                val shiftTarget = when {
                    from < 0 || isDragged -> 0f
                    target > from && i in (from + 1)..target -> -dh
                    target < from && i in target until from -> dh
                    else -> 0f
                }
                val shift by animateFloatAsState(
                    shiftTarget,
                    if (dragId != null) spring(stiffness = Spring.StiffnessMediumLow) else snap(),
                    label = "shift",
                )
                val lift by animateFloatAsState(if (isDragged) 1f else 0f, label = "lift")
                val handle = Modifier.pointerInput(p.id) {
                    detectDragGestures(
                        onDragStart = { _ -> start(p.id, (tops[p.id] ?: 0f) + (heights[p.id] ?: 0) / 2f) },
                        onDragEnd = { end(true) },
                        onDragCancel = { end(false) },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount.y; fingerY += amount.y
                        retarget()
                    }
                }
                Box(
                    Modifier
                        .onGloballyPositioned { if (!isDragged) tops[p.id] = it.positionInRoot().y }
                        .onSizeChanged { heights[p.id] = it.height }
                        .zIndex(if (isDragged || settleId == p.id) 10f else 0f)
                        .graphicsLayer {
                            translationY = when {
                                isDragged -> dragOffset
                                settleId == p.id -> settle.value
                                dragId != null -> shift
                                else -> 0f
                            }
                            val sc = 1f + 0.03f * lift
                            scaleX = sc; scaleY = sc
                            shadowElevation = 24f * lift
                            shape = RoundedCornerShape(24.dp)
                            clip = false
                        }
                        .pointerInput(p.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { off -> start(p.id, (tops[p.id] ?: 0f) + off.y) },
                                onDragEnd = { end(true) },
                                onDragCancel = { end(false) },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y; fingerY += amount.y
                                retarget()
                            }
                        }
                ) {
                    PlaceCard(p, i, mainId == p.id, handle, clickEnabled = dragId == null)
                }
            }
        }
    }
}

@Composable
private fun PlaceCard(p: Place, index: Int, main: Boolean, handle: Modifier? = null, clickEnabled: Boolean = true) {
    val c = LocalAppColors.current
    val s = AppState.settings
    val f = AppState.forecasts[p.id]
    val fmt = Fmt(s)
    LaunchedEffect(p.id) { AppState.refreshIfOld(p) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).uiCard(if (main) c.accentContainer.copy(alpha = 0.45f) else c.card)
            .clickable(enabled = clickEnabled) {
                Nav.page = AppState.places.indexOfFirst { it.id == p.id }.coerceAtLeast(0)
                Nav.back()
            }
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { AppState.update { it.copy(mainPlaceId = p.id) } }) {
            Icon(
                if (main) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
                contentDescription = if (main) "Località per le allerte" else "Usa per le allerte",
                tint = if (main) c.accent else c.inkDim,
            )
        }
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
