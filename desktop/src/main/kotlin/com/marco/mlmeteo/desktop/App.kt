package com.marco.mlmeteo.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlmeteo.AppState
import com.marco.mlmeteo.LocalAppColors
import com.marco.mlmeteo.LocalUiStyle

enum class Page(val title: String, val icon: ImageVector) {
    METEO("Meteo", Icons.Outlined.Cloud),
    PLACES("Località", Icons.Outlined.LocationOn),
    RADAR("Radar", Icons.Outlined.Radar),
    ALERTS("Allerte", Icons.Outlined.WarningAmber),
    SETTINGS("Impostazioni", Icons.Outlined.Settings),
}

object Nav {
    var page by mutableStateOf(Page.METEO)
}

/**
 * Finestra principale in stile Windows 11: riquadro di navigazione a sinistra e pagina a destra.
 * Il riquadro si stringe a sole icone sotto i 1000 dp e si nasconde sotto i 600.
 */
@Composable
fun App() {
    val c = LocalAppColors.current
    var expandedByUser by remember { mutableStateOf<Boolean?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val w = maxWidth
        val narrow = w < 600.dp
        val auto = w >= 1000.dp
        val expanded = expandedByUser ?: auto
        var overlay by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxSize()) {
            if (!narrow) NavPane(expanded) { expandedByUser = !expanded }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .then(
                        if (narrow) Modifier else Modifier.clip(RoundedCornerShape(topStart = 8.dp)).background(c.card2.copy(alpha = if (c.dark) 0.35f else 0.6f))
                    ),
            ) {
                AnimatedContent(Nav.page, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "pagina") { p ->
                    when (p) {
                        Page.METEO -> MeteoPage(w, onMenu = if (narrow) ({ overlay = true }) else null)
                        Page.PLACES -> PlacesPage()
                        Page.RADAR -> RadarPage()
                        Page.ALERTS -> AlertsPage()
                        Page.SETTINGS -> SettingsPage()
                    }
                }
            }
        }
        if (narrow && overlay) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable { overlay = false }) {
                Box(Modifier.width(240.dp).fillMaxHeight().background(c.bg)) { NavPane(true) { overlay = false } }
            }
        }
    }
}

@Composable
private fun NavPane(expanded: Boolean, onToggle: () -> Unit) {
    val c = LocalAppColors.current
    Column(
        Modifier.width(if (expanded) 220.dp else 56.dp).fillMaxHeight().padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        NavItem(Icons.Outlined.Menu, "", false, expanded, onToggle)
        Spacer(Modifier.height(4.dp))
        Page.entries.filter { it != Page.SETTINGS }.forEach { p ->
            NavItem(p.icon, p.title, Nav.page == p, expanded, badge = p == Page.ALERTS && AppState.alerts.values.any { l -> l.isNotEmpty() }) { Nav.page = p }
        }
        Spacer(Modifier.weight(1f))
        NavItem(Page.SETTINGS.icon, Page.SETTINGS.title, Nav.page == Page.SETTINGS, expanded) { Nav.page = Page.SETTINGS }
    }
    @Suppress("UNUSED_VARIABLE") val unused = c
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) =
    NavItem(icon, label, selected, expanded, false, onClick)

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, badge: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(minOf(LocalUiStyle.current.radius, 8.dp)))
            .background(when { selected -> c.card; hovered -> c.card.copy(alpha = 0.6f); else -> Color.Transparent })
            .hoverable(src).clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (selected) Box(Modifier.padding(start = 1.dp).width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Icon(icon, contentDescription = label, tint = c.ink, modifier = Modifier.size(20.dp))
                if (badge) Box(Modifier.size(8.dp).align(Alignment.TopEnd).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFB8C00)))
            }
            if (expanded && label.isNotEmpty()) {
                Spacer(Modifier.width(14.dp))
                Text(label, color = c.ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}
