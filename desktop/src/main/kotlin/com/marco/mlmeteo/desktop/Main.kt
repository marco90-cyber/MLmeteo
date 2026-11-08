package com.marco.mlmeteo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.marco.mlmeteo.AppColors
import com.marco.mlmeteo.AppState
import com.marco.mlmeteo.LocalAppColors
import com.marco.mlmeteo.LocalUiStyle
import com.marco.mlmeteo.buildColors
import com.marco.mlmeteo.drawWeather
import com.marco.mlmeteo.isDarkMode
import com.marco.mlmeteo.levelName
import com.marco.mlmeteo.skyOf
import com.marco.mlmeteo.styleColors
import com.marco.mlmeteo.uiStyleOf
import kotlinx.coroutines.delay
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt

/** Collegamenti dallo stato condiviso alla finestra. */
object Desktop {
    var changed by mutableStateOf(0)
    fun onDataChanged() { changed++ }
}

fun main(args: Array<String>) = application {
    AppState.init()
    androidx.compose.runtime.SideEffect { Dbg.app++ }
    if ("--selftest" in args) LaunchedEffect(Unit) {
        delay(4000); Nav.page = Page.PLACES; delay(1500); Dbg.query = "Palermo"
        repeat(20) {
            delay(1000)
            val rt = Runtime.getRuntime()
            println("SELFTEST t=$it app=${Dbg.app} win=${Dbg.win} page=${Dbg.page} places=${Dbg.places} rows=${Dbg.rows} changed=${Desktop.changed} mem=${(rt.totalMemory() - rt.freeMemory()) / 1_000_000}MB")
        }
        exitApplication()
    }
    val tray = rememberTrayState()
    // avviato con Windows: parte nascosto nell'area di notifica
    var visible by remember { mutableStateOf("--tray" !in args) }
    val saved = remember { WindowPrefs.load() }
    val win = rememberWindowState(
        size = DpSize(saved.w.dp, saved.h.dp),
        position = if (saved.x >= 0) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
    )
    val s = AppState.settings
    val place = AppState.mainPlace()
    val f = place?.let { AppState.forecasts[it.id] }
    val alertLevel = place?.let { p -> AppState.alerts[p.id]?.maxOfOrNull { it.level } } ?: 0
    @Suppress("UNUSED_VARIABLE") val tick = Desktop.changed
    val dark = s.uiStyle == "glass" || when (s.mode) { "light" -> false; "dark", "amoled" -> true; else -> WinSys.darkMode() }
    val accent = if (s.useWallpaper) WinSys.accent() ?: Color(s.manualColor) else Color(s.manualColor)
    val colors = styleColors(s.uiStyle, buildColors(dark, s.mode == "amoled", accent, null))

    // icona della finestra (barra delle applicazioni): il tempo attuale, con il pallino delle allerte
    val winIcon: Painter = remember(f?.current?.code, f?.current?.isDay, s.iconSet, alertLevel, colors.accent) {
        BitmapPainter(weatherBitmap(f?.current?.code ?: 2, f?.current?.isDay ?: true, s.iconSet, colors, 64, alertLevel))
    }
    // icona nell'area di notifica: la temperatura
    val trayIcon: Painter = remember(f?.current?.temp, s.tempUnit, dark) {
        BitmapPainter(tempIcon(f?.current?.temp?.let { com.marco.mlmeteo.Fmt(s).tempValue(it) }, dark))
    }
    val tip = if (place != null && f != null) "${place.name} · ${com.marco.mlmeteo.Fmt(s).temp(f.current.temp)} · ${com.marco.mlmeteo.conditionText(f.current.code, f.current.isDay)}"
        else "MLMeteo"

    Tray(
        icon = trayIcon, state = tray, tooltip = tip,
        onAction = { visible = true },
        menu = {
            Item("Apri MLMeteo", onClick = { visible = true })
            Item("Aggiorna", onClick = { AppState.refreshAll(force = true) })
            Separator()
            AppState.places.forEach { p ->
                val pf = AppState.forecasts[p.id]
                Item(p.name + (pf?.let { "  " + com.marco.mlmeteo.Fmt(s).temp(it.current.temp) } ?: ""), onClick = {
                    AppState.selectedId = p.id; Nav.page = Page.METEO; visible = true
                })
            }
            Separator()
            Item("Esci", onClick = { WindowPrefs.save(win); exitApplication() })
        },
    )

    // aggiornamento periodico e notifiche delle allerte
    LaunchedEffect(Unit) {
        AppState.refreshAll()
        while (true) {
            delay((AppState.settings.updateFreq.coerceAtLeast(15)) * 60_000L)
            AppState.refreshAll(force = true)
        }
    }
    val notified = remember { mutableSetOf<String>() }
    LaunchedEffect(Desktop.changed) {
        if (!AppState.settings.notifAlerts) return@LaunchedEffect
        AppState.mainPlace()?.let { p ->
            AppState.alerts[p.id].orEmpty().filter { it.level >= AppState.settings.alertMinLevel && it.id !in notified }.forEach { a ->
                notified += a.id
                tray.sendNotification(Notification("Allerta ${levelName(a.level)} · ${p.name}", a.title, Notification.Type.Warning))
            }
        }
    }

    Window(
        onCloseRequest = {
            WindowPrefs.save(win)
            if (AppState.closeToTray) visible = false else exitApplication()
        },
        state = win, visible = visible, title = "MLMeteo", icon = winIcon,
    ) {
        androidx.compose.runtime.SideEffect { Dbg.win++ }
        DesktopTheme(colors) { App() }
    }
}

@Composable
fun DesktopTheme(c: AppColors, content: @Composable () -> Unit) {
    val s = AppState.settings
    val st = uiStyleOf(s.uiStyle).let { if (it.id == "fluent") it.copy(border = c.outline) else it }
    val scheme = if (c.dark) darkColorScheme(
        primary = c.accent, onPrimary = Color(0xFF16151C), primaryContainer = c.accentContainer, onPrimaryContainer = c.onAccentContainer,
        background = c.bg, onBackground = c.ink, surface = c.card, onSurface = c.ink, surfaceVariant = c.card2, onSurfaceVariant = c.inkDim,
        outline = c.outline, outlineVariant = c.outline, surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.card2,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = Color.White, primaryContainer = c.accentContainer, onPrimaryContainer = c.onAccentContainer,
        background = c.bg, onBackground = c.ink, surface = c.card, onSurface = c.ink, surfaceVariant = c.card2, onSurfaceVariant = c.inkDim,
        outline = c.outline, outlineVariant = c.outline, surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.card2,
    )
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalAppColors provides c, LocalUiStyle provides st,
        LocalDensity provides Density(d.density * AppState.zoom / 100f, d.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme) { Box(Modifier.fillMaxSize()) { content() } }
    }
}

/** Icona del meteo disegnata in un'immagine (finestra e barra delle applicazioni). */
fun weatherBitmap(code: Int, isDay: Boolean, set: Int, c: AppColors, px: Int, alert: Int = 0): ImageBitmap {
    val bmp = ImageBitmap(px, px)
    val canvas = Canvas(bmp)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
        drawWeather(skyOf(code), isDay, set, c.accent, c.ink)
        if (alert > 0) {
            val col = when (alert) { 3 -> Color(0xFFE53935); 2 -> Color(0xFFFB8C00); else -> Color(0xFFFDD835) }
            drawCircle(Color.White, px * 0.2f, Offset(px * 0.78f, px * 0.22f))
            drawCircle(col, px * 0.16f, Offset(px * 0.78f, px * 0.22f))
        }
    }
    return bmp
}

/** Icona dell'area di notifica con la temperatura. */
fun tempIcon(temp: Double?, dark: Boolean): ImageBitmap {
    val s = 64
    val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val txt = temp?.let { "${it.roundToInt()}°" } ?: "–"
    var size = 46
    g.font = Font("Segoe UI", Font.BOLD, size)
    while (g.fontMetrics.stringWidth(txt) > s - 2 && size > 20) { size -= 2; g.font = Font("Segoe UI", Font.BOLD, size) }
    g.color = if (dark) java.awt.Color.WHITE else java.awt.Color(0x1B1B1B)
    val fm = g.fontMetrics
    g.drawString(txt, (s - fm.stringWidth(txt)) / 2, (s - fm.height) / 2 + fm.ascent)
    g.dispose()
    return img.toComposeImageBitmap()
}

/** Grandezza e posizione della finestra, ricordate alla chiusura. */
object WindowPrefs {
    data class W(val w: Int, val h: Int, val x: Int, val y: Int)
    private val file get() = File(AppState.dir, "finestra.txt")
    fun load(): W = try {
        val p = file.readText().trim().split(",").map { it.toInt() }
        W(p[0].coerceAtLeast(480), p[1].coerceAtLeast(480), p[2], p[3])
    } catch (e: Exception) { W(1200, 820, -1, -1) }
    fun save(st: androidx.compose.ui.window.WindowState) {
        try {
            val pos = st.position
            val x = if (pos is WindowPosition.Absolute) pos.x.value.toInt() else -1
            val y = if (pos is WindowPosition.Absolute) pos.y.value.toInt() else -1
            file.writeText("${st.size.width.value.toInt()},${st.size.height.value.toInt()},$x,$y")
        } catch (e: Exception) { }
    }
}

/** Piccole cose di Windows: tema, colore d'accento, avvio automatico. */
object WinSys {
    private fun reg(vararg args: String): String = try {
        val p = ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    } catch (e: Exception) { "" }

    fun darkMode(): Boolean {
        val out = reg("query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
        return Regex("0x0\\b").containsMatchIn(out.substringAfter("AppsUseLightTheme", ""))
    }

    private var cachedAccent: Color? = null
    private var accentRead = false
    /** Colore d'accento di Windows (registro: AccentColor in formato ABGR). */
    fun accent(): Color? {
        if (accentRead) return cachedAccent
        accentRead = true
        val out = reg("query", "HKCU\\Software\\Microsoft\\Windows\\DWM", "/v", "AccentColor")
        val hex = Regex("0x([0-9a-fA-F]+)").find(out.substringAfter("AccentColor", ""))?.groupValues?.get(1) ?: return null
        val v = hex.toLong(16)
        val r = (v and 0xFF).toInt(); val g = ((v shr 8) and 0xFF).toInt(); val b = ((v shr 16) and 0xFF).toInt()
        cachedAccent = Color(r, g, b)
        return cachedAccent
    }

    private const val RUN = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    fun setAutostart(on: Boolean) {
        val exe = System.getProperty("jpackage.app-path") ?: return
        if (on) reg("add", RUN, "/v", "MLMeteo", "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f")
        else reg("delete", RUN, "/v", "MLMeteo", "/f")
    }

    fun open(url: String) {
        try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (e: Exception) { }
    }
}
