package com.marco.mlmeteo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class WidgetKind { SMALL, MEDIUM, LARGE }

open class BaseWidget(private val kind: WidgetKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { Widgets.update(context, manager, it, kind) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        Widgets.update(context, manager, id, kind)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val sp = context.getSharedPreferences("mlmeteo_widgets", Context.MODE_PRIVATE).edit()
        ids.forEach { sp.remove("w$it") }
        sp.apply()
    }

    override fun onEnabled(context: Context) {
        Background.schedule(context)
    }
}

class WidgetSmall : BaseWidget(WidgetKind.SMALL)
class WidgetMedium : BaseWidget(WidgetKind.MEDIUM)
class WidgetLarge : BaseWidget(WidgetKind.LARGE)

/**
 * Widget disegnati come immagine, con la stessa grafica, la stessa fonte e gli stessi dati dell'app.
 * Ogni widget mostra la località scelta (o la principale, quella con la campanella).
 */
object Widgets {
    private val kinds = mapOf(
        WidgetKind.SMALL to WidgetSmall::class.java,
        WidgetKind.MEDIUM to WidgetMedium::class.java,
        WidgetKind.LARGE to WidgetLarge::class.java,
    )

    fun updateAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        for ((kind, cls) in kinds) {
            m.getAppWidgetIds(ComponentName(ctx, cls)).forEach { update(ctx, m, it, kind) }
        }
    }

    private fun allIds(ctx: Context): List<Int> {
        val m = AppWidgetManager.getInstance(ctx)
        return kinds.values.flatMap { m.getAppWidgetIds(ComponentName(ctx, it)).toList() }
    }

    fun setPlace(ctx: Context, widgetId: Int, placeId: String) {
        ctx.getSharedPreferences("mlmeteo_widgets", Context.MODE_PRIVATE).edit().putString("w$widgetId", placeId).apply()
    }

    private fun placeFor(ctx: Context, widgetId: Int): Place? {
        val s = Prefs.loadSettings(ctx)
        val places = listOfNotNull(Prefs.loadGps(ctx).takeIf { s.useGps }) + Prefs.loadPlaces(ctx)
        val chosen = ctx.getSharedPreferences("mlmeteo_widgets", Context.MODE_PRIVATE).getString("w$widgetId", "main")
        return places.firstOrNull { it.id == chosen }
            ?: places.firstOrNull { it.id == s.mainPlaceId } ?: places.firstOrNull()
    }

    /** Località usate dai widget (per aggiornarle in background). */
    fun placeIds(ctx: Context): List<String> = allIds(ctx).mapNotNull { placeFor(ctx, it)?.id }

    fun update(ctx: Context, m: AppWidgetManager, id: Int, kind: WidgetKind) {
        try {
            val opts = m.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            var wDp = if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            var hDp = if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (wDp <= 0) wDp = when (kind) { WidgetKind.SMALL -> 180; else -> 320 }
            if (hDp <= 0) hDp = when (kind) { WidgetKind.SMALL -> 90; WidgetKind.MEDIUM -> 180; WidgetKind.LARGE -> 360 }
            val place = placeFor(ctx, id)
            val f = place?.let { ForecastStore.load(ctx, it.id) }
            val bmp = draw(ctx, kind, wDp, hDp, place, f)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_image)
            rv.setImageViewBitmap(R.id.widget_image, bmp)
            val pi = PendingIntent.getActivity(
                ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.widget_image, pi)
            m.updateAppWidget(id, rv)
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ disegno

    private fun colors(ctx: Context, s: Settings): AppColors {
        val sysDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (s.mode) { "light" -> false; "dark", "amoled" -> true; else -> sysDark }
        val accent = if (s.useWallpaper && Build.VERSION.SDK_INT >= 31) Color(ctx.getColor(android.R.color.system_accent1_200)) else Color(s.manualColor)
        return buildColors(dark, s.mode == "amoled", accent, null)
    }

    private fun icon(code: Int, isDay: Boolean, px: Int, set: Int, c: AppColors): Bitmap {
        val ib = Bitmap.createBitmap(px.coerceAtLeast(1), px.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = androidx.compose.ui.graphics.Canvas(ib.asImageBitmap())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
            drawWeather(skyOf(code), isDay, set, c.accent, c.ink)
        }
        return ib
    }

    fun draw(ctx: Context, kind: WidgetKind, wDp: Int, hDp: Int, place: Place?, f: Forecast?): Bitmap {
        val s = Prefs.loadSettings(ctx)
        val c = colors(ctx, s)
        val fmt = Fmt(s)
        val dens = ctx.resources.displayMetrics.density
        // limite di memoria per le immagini dei widget
        var scale = dens
        while (wDp * scale * hDp * scale > 1_400_000) scale *= 0.85f
        val w = (wDp * scale).toInt().coerceAtLeast(10)
        val h = (hDp * scale).toInt().coerceAtLeast(10)
        fun dp(v: Float) = v * scale
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.card.toArgb() }
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), dp(24f), dp(24f), bg)
        fun text(size: Float, color: Color, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(size); this.color = color.toArgb(); textAlign = align
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val pad = dp(16f)
        if (place == null || f == null) {
            cv.drawText("MLMeteo", pad, pad + dp(18f), text(18f, c.ink, true))
            cv.drawText(if (place == null) "Aggiungi una località nell'app" else "Apri l'app per scaricare il meteo", pad, pad + dp(42f), text(13f, c.inkDim))
            return bmp
        }
        val cur = f.current
        val today = f.days.firstOrNull()
        when (kind) {
            WidgetKind.SMALL -> {
                val ic = (h - pad * 2).coerceAtMost(w * 0.4f).toInt()
                cv.drawBitmap(icon(cur.code, cur.isDay, ic, s.iconSet, c), pad, (h - ic) / 2f, null)
                val x = pad + ic + dp(10f)
                val tp = text((h / scale * 0.36f).coerceIn(22f, 46f), c.ink, true)
                cv.drawText(fmt.temp(cur.temp), x, h / 2f + tp.textSize * 0.15f, tp)
                cv.drawText(place.name, x, h / 2f + tp.textSize * 0.15f + dp(18f), text(12f, c.inkDim))
            }
            WidgetKind.MEDIUM, WidgetKind.LARGE -> {
                var y = pad + dp(16f)
                cv.drawText(place.name, pad, y, text(16f, c.ink, true))
                val upd = Instant.ofEpochMilli(f.fetchedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
                cv.drawText("Agg. ${fmt.time(upd)}", w - pad, y, text(11f, c.inkDim, align = Paint.Align.RIGHT))
                y += dp(10f)
                val ic = dp(64f).toInt()
                cv.drawBitmap(icon(cur.code, cur.isDay, ic, s.iconSet, c), pad, y, null)
                val tp = text(44f, c.ink, true)
                cv.drawText(fmt.temp(cur.temp), pad + ic + dp(12f), y + dp(44f), tp)
                val tx = pad + ic + dp(12f) + tp.measureText(fmt.temp(cur.temp)) + dp(14f)
                cv.drawText(conditionText(cur.code, cur.isDay), tx, y + dp(26f), text(14f, c.ink))
                cv.drawText("Max ${fmt.temp(today?.tMax ?: Double.NaN)} · Min ${fmt.temp(today?.tMin ?: Double.NaN)}", tx, y + dp(46f), text(12f, c.inkDim))
                y += ic + dp(10f)
                // ore
                val n = ((w - pad * 2) / dp(52f)).toInt().coerceIn(3, 8)
                val colW = (w - pad * 2) / n
                val hoursH = dp(78f)
                if (y + hoursH <= h - dp(6f)) {
                    f.hours.take(n).forEachIndexed { i, hr ->
                        val cx = pad + colW * i + colW / 2
                        if (i == 0) {
                            val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accentContainer.toArgb() }
                            cv.drawRoundRect(RectF(cx - colW / 2 + dp(2f), y, cx + colW / 2 - dp(2f), y + hoursH), dp(20f), dp(20f), chip)
                        }
                        val ink = if (i == 0) c.onAccentContainer else c.ink
                        cv.drawText(if (i == 0) "Ora" else fmt.hourLabel(hr.time), cx, y + dp(16f), text(11f, if (i == 0) ink else c.inkDim, align = Paint.Align.CENTER))
                        val hi = dp(26f).toInt()
                        cv.drawBitmap(icon(hr.code, hr.isDay, hi, s.iconSet, c), cx - hi / 2f, y + dp(22f), null)
                        cv.drawText(fmt.temp(if (i == 0) cur.temp else hr.temp), cx, y + dp(66f), text(13f, ink, true, Paint.Align.CENTER))
                    }
                    y += hoursH + dp(10f)
                }
                if (kind == WidgetKind.LARGE) {
                    val days = f.days.take(7)
                    val lo = days.minOfOrNull { it.tMin } ?: 0.0
                    val hi = days.maxOfOrNull { it.tMax } ?: 1.0
                    val span = (hi - lo).coerceAtLeast(1.0)
                    val rowH = dp(30f)
                    val todayDate = f.localNow.toLocalDate()
                    for (d in days) {
                        if (y + rowH > h - dp(4f)) break
                        val name = when (d.date) {
                            todayDate -> "Oggi"
                            todayDate.plusDays(1) -> "Domani"
                            else -> d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ITALY).replaceFirstChar { it.uppercase() }
                        }
                        cv.drawText(name, pad, y + dp(20f), text(13f, c.ink))
                        val di = dp(22f).toInt()
                        cv.drawBitmap(icon(d.code, true, di, s.iconSet, c), pad + dp(64f), y + dp(4f), null)
                        val rain = fmt.rainShort(d.precipProb, d.precipSum)
                        cv.drawText(rain, pad + dp(92f), y + dp(20f), text(11f, c.accent, true))
                        val bx0 = pad + dp(148f); val bx1 = w - pad - dp(36f)
                        cv.drawText(fmt.temp(d.tMin), bx0 - dp(6f), y + dp(20f), text(12f, c.inkDim, align = Paint.Align.RIGHT))
                        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.outline.toArgb() }
                        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent.toArgb() }
                        val by = y + dp(13f)
                        cv.drawRoundRect(RectF(bx0 + dp(4f), by, bx1, by + dp(5f)), dp(3f), dp(3f), track)
                        val x0 = bx0 + dp(4f) + ((d.tMin - lo) / span * (bx1 - bx0 - dp(4f))).toFloat()
                        val x1 = bx0 + dp(4f) + ((d.tMax - lo) / span * (bx1 - bx0 - dp(4f))).toFloat()
                        cv.drawRoundRect(RectF(x0, by, x1.coerceAtLeast(x0 + dp(5f)), by + dp(5f)), dp(3f), dp(3f), bar)
                        cv.drawText(fmt.temp(d.tMax), w - pad, y + dp(20f), text(13f, c.ink, true, Paint.Align.RIGHT))
                        y += rowH
                    }
                }
            }
        }
        return bmp
    }
}

/** Scelta della località quando si aggiunge (o si riconfigura) un widget. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        AppState.init(this)
        fun choose(placeId: String) {
            Widgets.setPlace(this, id, placeId)
            Widgets.updateAll(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
        setContent {
            val s = AppState.settings
            MLTheme(s, null) {
                run {
                    Page("Località del widget") {
                        Hint("Il widget usa la stessa fonte e gli stessi dati dell'app.")
                        Group {
                            SettingRow("Località principale", "Quella con la campanella", Icons.Filled.Notifications, onClick = { choose("main") })
                            AppState.places.forEach { p ->
                                Divider()
                                SettingRow(p.name, p.region, if (p.gps) Icons.Filled.Navigation else Icons.Filled.Place, onClick = { choose(p.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun unusedDate(d: LocalDate) = d.format(DateTimeFormatter.ISO_DATE)
