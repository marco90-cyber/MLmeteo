package com.marco.mlmeteo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pagina standard con barra in alto e contenuto scorrevole. */
@Composable
fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = c.ink)
            }
            Text(title, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    val c = LocalAppColors.current
    Text(text, color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
fun CardBox(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    Column(
        modifier.fillMaxWidth().uiCard().padding(padding.dp),
        content = content,
    )
}

/** true dentro un [Group]: i separatori diventano lo stacco tra le schede, come in MLauncher. */
private val LocalInGroup = androidx.compose.runtime.compositionLocalOf { false }

/** Gruppo di righe impostazioni nello stile di MLauncher (MLTheme): angoli da 28 dp, voci separate da un filo di spazio. */
@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    androidx.compose.runtime.CompositionLocalProvider(LocalInGroup provides true) {
        Column(Modifier.fillMaxWidth().uiCard(group = true), content = content)
    }
}

@Composable
fun Divider() {
    if (LocalInGroup.current) {
        // separé di MLauncher: stacco di 2 dp del colore della pagina
        Box(Modifier.fillMaxWidth().height(2.dp).background(LocalAppColors.current.bg))
    } else {
        HorizontalDivider(color = LocalAppColors.current.outline.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
fun IconBubble(icon: ImageVector) {
    val c = LocalAppColors.current
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentContainer), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = c.onAccentContainer, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconBubble(icon)
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) c.ink else c.inkDim, fontSize = 16.sp)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = c.inkDim, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, icon: ImageVector? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, icon, enabled, onClick = { onChange(!checked) }) {
        androidx.compose.runtime.CompositionLocalProvider(com.marco.mltheme.LocalPrefEnabled provides enabled) {
            com.marco.mltheme.MLSwitch(checked = checked, onCheckedChange = onChange)
        }
    }
}

data class Choice(val id: String, val label: String, val info: String? = null, val enabled: Boolean = true)

/** Riga che apre un dialogo con scelta singola. */
@Composable
fun ChoiceRow(title: String, icon: ImageVector? = null, choices: List<Choice>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, choices.firstOrNull { it.id == selected }?.label, icon, onClick = { open = true })
    if (open) ChoiceDialog(title, choices, selected, onPick = { onPick(it); open = false }, onDismiss = { open = false })
}

@Composable
fun ChoiceDialog(title: String, choices: List<Choice>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    var info by remember { mutableStateOf<Choice?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { ch ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .let { if (ch.enabled) it.clickable { onPick(ch.id) } else it }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ch.id == selected, onClick = { onPick(ch.id) }, enabled = ch.enabled)
                        Text(ch.label, color = if (ch.enabled) c.ink else c.inkDim, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (ch.info != null) {
                            IconButton(onClick = { info = ch }) {
                                Icon(Icons.Filled.Info, contentDescription = "Informazioni", tint = c.inkDim)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
    info?.let { ch ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(ch.label) },
            text = { Text(ch.info ?: "") },
            confirmButton = { TextButton(onClick = { info = null }) { Text("OK") } },
        )
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card2)) {
        options.forEach { (id, label) ->
            val sel = id == selected
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (sel) c.accentContainer else c.card2)
                    .clickable { onPick(id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) c.onAccentContainer else c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, color = LocalAppColors.current.inkDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
fun Gap(w: Int) = Spacer(Modifier.width(w.dp))
