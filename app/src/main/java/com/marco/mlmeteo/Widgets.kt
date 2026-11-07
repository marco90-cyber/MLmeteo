package com.marco.mlmeteo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

open class BaseWidget(private val kind: WidgetKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { Widgets.update(context, manager, it, kind) }
        if (kind == WidgetKind.RADAR) Widgets.refreshRadarIfOld(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        Widgets.update(context, manager, id, kind)
        if (kind == WidgetKind.RADAR) Widgets.refreshNow(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.remove(context, it) }
    }

    override fun onEnabled(context: Context) {
        Background.schedule(context)
    }
}

/** Widget meteo: si adatta alla grandezza. (Piccolo e grande restano solo per i widget già messi.) */
class WidgetMedium : BaseWidget(WidgetKind.WEATHER)
class WidgetSmall : BaseWidget(WidgetKind.WEATHER)
class WidgetLarge : BaseWidget(WidgetKind.WEATHER)
class WidgetMini : BaseWidget(WidgetKind.MINI)
class WidgetRadar : BaseWidget(WidgetKind.RADAR)
class WidgetRain : BaseWidget(WidgetKind.RAIN)

/**
 * Widget disegnati come immagine, con la stessa grafica, la stessa fonte e gli stessi dati dell'app.
 * Sopra l'immagine ci sono zone trasparenti per i tocchi (parte alta, ore, pioggia, giorni).
 */
object Widgets {
    private val providers = listOf(
        WidgetMedium::class.java to WidgetKind.WEATHER,
        WidgetSmall::class.java to WidgetKind.WEATHER,
        WidgetLarge::class.java to WidgetKind.WEATHER,
        WidgetMini::class.java to WidgetKind.MINI,
        WidgetRadar::class.java to WidgetKind.RADAR,
        WidgetRain::class.java to WidgetKind.RAIN,
    )

    /** Tutti i widget messi: (id, tipo). */
    fun all(ctx: Context): List<Pair<Int, WidgetKind>> {
        val m = AppWidgetManager.getInstance(ctx)
        return providers.flatMap { (cls, k) -> m.getAppWidgetIds(ComponentName(ctx, cls)).map { it to k } }
    }

    fun kindOf(ctx: Context, id: Int): WidgetKind {
        val info = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) ?: return WidgetKind.WEATHER
        return providers.firstOrNull { it.first.name == info.provider.className }?.second ?: WidgetKind.WEATHER
    }

    fun updateAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        all(ctx).forEach { (id, k) -> update(ctx, m, id, k) }
    }

    fun placeFor(ctx: Context, widgetId: Int, cfg: WidgetCfg = WidgetStore.cfg(ctx, widgetId)): Place? {
        val s = Prefs.loadSettings(ctx)
        val places = listOfNotNull(Prefs.loadGps(ctx).takeIf { s.useGps }) + Prefs.loadPlaces(ctx)
        return places.firstOrNull { it.id == cfg.place }
            ?: places.firstOrNull { it.id == s.mainPlaceId } ?: places.firstOrNull()
    }

    /** Località usate dai widget (per aggiornarle in background). */
    fun placeIds(ctx: Context): List<String> = all(ctx).filter { !WidgetStore.isRemoved(ctx, it.first) }
        .mapNotNull { placeFor(ctx, it.first)?.id }.distinct()

    const val ACTION_REFRESH = "com.marco.mlmeteo.WIDGET_REFRESH"

    /** Tocco sull'icona di aggiornamento: scarica subito i dati e ridisegna i widget. */
    fun refreshNow(ctx: Context) {
        ctx.getSharedPreferences("mlmeteo_widgets", Context.MODE_PRIVATE).edit()
            .putLong("refreshing", System.currentTimeMillis()).apply()
        updateAll(ctx)
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
            "mlmeteo-widget-refresh", androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun refreshRadarIfOld(ctx: Context) {
        val f = RadarWidgetData.frame(ctx)
        if (f == null || System.currentTimeMillis() - f.time > 15 * 60_000) refreshNow(ctx)
    }

    fun refreshDone(ctx: Context) {
        ctx.getSharedPreferences("mlmeteo_widgets", Context.MODE_PRIVATE).edit().remove("refreshing").apply()
    }

    private fun refreshing(ctx: Context): Boolean {
        val t = ctx.getSharedPreferences("mlmeteo_widgets", Context.MODE_PRIVATE).getLong("refreshing", 0)
        return System.currentTimeMillis() - t < 60_000
    }

    /** Dimensioni reali del widget in dp (cambiano quando lo ridimensioni). */
    fun sizeDp(ctx: Context, m: AppWidgetManager, id: Int, kind: WidgetKind): Pair<Int, Int> {
        val opts = m.getAppWidgetOptions(id)
        val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes = opts.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                val sz = if (portrait) sizes.minByOrNull { it.width }!! else sizes.maxByOrNull { it.width }!!
                if (sz.width > 0 && sz.height > 0) return sz.width.toInt() to sz.height.toInt()
            }
        }
        var w = if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        var h = if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (w <= 0) w = defaultSize(kind).first
        if (h <= 0) h = defaultSize(kind).second
        return w to h
    }

    fun defaultSize(kind: WidgetKind) = when (kind) {
        WidgetKind.WEATHER -> 320 to 190
        WidgetKind.MINI -> 80 to 80
        WidgetKind.RADAR -> 320 to 190
        WidgetKind.RAIN -> 320 to 160
    }

    /** Pixel dell'immagine per ogni dp (ridotti se il widget è enorme, per restare sotto i limiti di Android). */
    fun bitmapScale(wDp: Int, hDp: Int, density: Float): Float {
        var scale = density
        while (wDp * scale * hDp * scale > 1_400_000) scale *= 0.85f
        return scale
    }

    private fun action(ctx: Context, id: Int, what: String, slot: Int): PendingIntent {
        val code = id * 10 + slot
        return when (what) {
            "settings" -> PendingIntent.getActivity(
                ctx, code,
                Intent(ctx, WidgetConfigActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(android.net.Uri.parse("mlmeteo://widget/$id")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            "refresh" -> PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            else -> PendingIntent.getActivity(
                ctx, code,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("open", what),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    private val zoneViews = listOf(
        "main" to R.id.zone_main, "hours" to R.id.zone_hours, "rain" to R.id.zone_rain, "days" to R.id.zone_days,
    )

    fun update(ctx: Context, m: AppWidgetManager, id: Int, kind: WidgetKind) {
        try {
            val (wDp, hDp) = sizeDp(ctx, m, id, kind)
            if (WidgetStore.isRemoved(ctx, id)) { m.updateAppWidget(id, removedViews(ctx, wDp, hDp)); return }
            val cfg = WidgetStore.cfg(ctx, id)
            val place = placeFor(ctx, id, cfg)
            val busy = refreshing(ctx)
            val r = render(ctx, kind, cfg, place, wDp, hDp, busy)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_image)
            rv.setImageViewBitmap(R.id.widget_image, r.bitmap)
            rv.setOnClickPendingIntent(R.id.widget_image, action(ctx, id, cfg.tapMain, 0))
            val dens = ctx.resources.displayMetrics.density
            zoneViews.forEachIndexed { i, (name, viewId) ->
                val z = r.zones.firstOrNull { it.first == name }
                if (z == null || kind != WidgetKind.WEATHER) {
                    rv.setViewVisibility(viewId, View.GONE)
                } else {
                    val px = (z.second * dens).roundToInt().coerceAtLeast(1)
                    rv.setImageViewBitmap(viewId, Bitmap.createBitmap(1, px, Bitmap.Config.ARGB_8888))
                    rv.setViewVisibility(viewId, View.VISIBLE)
                    val what = when (name) { "hours" -> cfg.tapHours; "rain" -> cfg.tapRain; "days" -> cfg.tapDays; else -> cfg.tapMain }
                    rv.setOnClickPendingIntent(viewId, action(ctx, id, what, i + 1))
                }
            }
            // aggiorna
            val showRefresh = kind != WidgetKind.MINI && wDp >= 140 && hDp >= 56
            val c = colors(ctx, cfg)
            rv.setOnClickPendingIntent(R.id.widget_refresh, action(ctx, id, "refresh", 6))
            rv.setInt(R.id.widget_refresh, "setColorFilter", (if (busy) c.accent else c.inkDim).toArgb())
            rv.setViewVisibility(R.id.widget_refresh, if (showRefresh) View.VISIBLE else View.GONE)
            // ingranaggio (widget pioggia): apre le impostazioni del widget
            if (kind == WidgetKind.RAIN && showRefresh) {
                val gp = (24 * dens).roundToInt()
                val gb = Bitmap.createBitmap(gp, gp, Bitmap.Config.ARGB_8888)
                drawVector(android.graphics.Canvas(gb), Icons.Filled.Settings, 0f, 0f, gp.toFloat(), c.inkDim.toArgb())
                rv.setImageViewBitmap(R.id.widget_settings, gb)
                rv.setViewVisibility(R.id.widget_settings, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.widget_settings, action(ctx, id, "settings", 8))
            } else rv.setViewVisibility(R.id.widget_settings, View.GONE)
            // pallino allerta
            val lvl = if (cfg.showAlert && place != null && kind != WidgetKind.RAIN) WidgetStore.alertLevel(ctx, place.id) else 0
            val dotView = if (showRefresh) R.id.widget_alert else R.id.widget_alert2
            val other = if (showRefresh) R.id.widget_alert2 else R.id.widget_alert
            rv.setViewVisibility(other, View.GONE)
            if (lvl > 0) {
                rv.setImageViewBitmap(dotView, alertDot(lvl, (24 * dens).roundToInt()))
                rv.setViewVisibility(dotView, View.VISIBLE)
                rv.setOnClickPendingIntent(dotView, action(ctx, id, "app", 7))
            } else rv.setViewVisibility(dotView, View.GONE)
            m.updateAppWidget(id, rv)
        } catch (e: Exception) { }
    }

    /** Widget rimosso dalla pagina Widget: un messaggio e nessun tocco. */
    private fun removedViews(ctx: Context, wDp: Int, hDp: Int): RemoteViews {
        val p = Pen(ctx, WidgetCfg(opacity = 70), wDp, hDp)
        p.background()
        val t1 = p.text(13f, p.c.ink, true, Paint.Align.CENTER)
        val t2 = p.text(11f, p.c.inkDim, align = Paint.Align.CENTER)
        p.cv.drawText(p.fit(t1, "Widget rimosso", p.w - p.dp(16f)), p.w / 2f, p.h / 2f - p.dp(2f), t1)
        p.cv.drawText(p.fit(t2, "Tienilo premuto per toglierlo", p.w - p.dp(16f)), p.w / 2f, p.h / 2f + p.dp(14f), t2)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_image)
        rv.setImageViewBitmap(R.id.widget_image, p.bmp)
        listOf(R.id.zone_main, R.id.zone_hours, R.id.zone_rain, R.id.zone_days, R.id.widget_alert, R.id.widget_alert2,
            R.id.widget_refresh, R.id.widget_settings).forEach { rv.setViewVisibility(it, View.GONE) }
        return rv
    }

    /** Pallino colorato con il punto esclamativo. */
    fun alertDot(level: Int, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        val fill = when (level) { 3 -> 0xFFE53935.toInt(); 2 -> 0xFFFB8C00.toInt(); else -> 0xFFFDD835.toInt() }
        cv.drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        cv.drawCircle(px / 2f, px / 2f, px / 2f - px * 0.08f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (level == 1) 0xFF3E2723.toInt() else 0xFFFFFFFF.toInt()
            textSize = px * 0.7f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        cv.drawText("!", px / 2f, px / 2f + px * 0.25f, t)
        return b
    }

    // ------------------------------------------------------------------ colori

    fun colors(ctx: Context, cfg: WidgetCfg): AppColors {
        val s = Prefs.loadSettings(ctx)
        val sysDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val appDark = when (s.mode) { "light" -> false; "dark", "amoled" -> true; else -> sysDark }
        val dark = when (cfg.textMode) { "light" -> true; "dark" -> false; else -> appDark }
        val wallpaper = if (Build.VERSION.SDK_INT >= 31) Color(ctx.getColor(android.R.color.system_accent1_200)) else null
        val accent = when (cfg.colorMode) {
            "wallpaper" -> wallpaper ?: Color(s.manualColor)
            "custom" -> Color(cfg.customColor)
            else -> if (s.useWallpaper && wallpaper != null) wallpaper else Color(s.manualColor)
        }
        val base = buildColors(dark, s.mode == "amoled" && dark && cfg.textMode == "auto", accent, null)
        return if (cfg.colorMode == "custom") base.copy(card = lerp(base.card, Color(cfg.customColor), if (dark) 0.22f else 0.30f)) else base
    }

    private fun icon(code: Int, isDay: Boolean, px: Int, set: Int, c: AppColors): Bitmap {
        val ib = Bitmap.createBitmap(px.coerceAtLeast(1), px.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = androidx.compose.ui.graphics.Canvas(ib.asImageBitmap())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
            drawWeather(skyOf(code), isDay, set, c.accent, c.ink)
        }
        return ib
    }

    /** Disegna un'icona Material (vettoriale) sul Canvas di Android. */
    fun drawVector(cv: android.graphics.Canvas, v: ImageVector, x: Float, y: Float, size: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val m = android.graphics.Matrix()
        m.setScale(size / v.viewportWidth, size / v.viewportHeight)
        m.postTranslate(x, y)
        fun walk(g: VectorGroup) {
            for (n in g) when (n) {
                is VectorPath -> {
                    val path = n.pathData.toPath().asAndroidPath()
                    path.transform(m)
                    cv.drawPath(path, p)
                }
                is VectorGroup -> walk(n)
            }
        }
        walk(v.root)
    }

    class Rendered(val bitmap: Bitmap, val zones: List<Pair<String, Float>>)

    fun render(ctx: Context, kind: WidgetKind, cfg: WidgetCfg, place: Place?, wDp: Int, hDp: Int, busy: Boolean = false): Rendered {
        val f = place?.let { ForecastStore.load(ctx, it.id) }
        return when (kind) {
            WidgetKind.WEATHER -> drawWeatherWidget(ctx, cfg, wDp, hDp, place, f, busy)
            WidgetKind.MINI -> Rendered(drawMini(ctx, cfg, wDp, hDp, f), emptyList())
            WidgetKind.RADAR -> Rendered(drawRadar(ctx, cfg, wDp, hDp, place, busy), emptyList())
            WidgetKind.RAIN -> Rendered(drawRainWidget(ctx, cfg, wDp, hDp, place, f, busy), emptyList())
        }
    }

    // ------------------------------------------------------------------ disegno: strumenti comuni

    private class Pen(val ctx: Context, val cfg: WidgetCfg, wDp: Int, hDp: Int) {
        val s = Prefs.loadSettings(ctx)
        val c = colors(ctx, cfg)
        val fmt = Fmt(s)
        val scale = bitmapScale(wDp, hDp, ctx.resources.displayMetrics.density)
        val w = (wDp * scale).toInt().coerceAtLeast(10)
        val h = (hDp * scale).toInt().coerceAtLeast(10)
        val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val shadow = cfg.opacity < 60
        fun dp(v: Float) = v * scale
        fun text(size: Float, color: Color, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(size); this.color = color.toArgb(); textAlign = align
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            // con lo sfondo trasparente il testo ha un'ombra per restare leggibile
            if (shadow) setShadowLayer(dp(3f), 0f, dp(1f), if (c.dark) 0xAA000000.toInt() else 0x99FFFFFF.toInt())
        }
        fun fit(p: Paint, t: String, maxW: Float): String {
            if (p.measureText(t) <= maxW) return t
            var x = t
            while (x.length > 1 && p.measureText("$x…") > maxW) x = x.dropLast(1)
            return "$x…"
        }
        fun background() {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.card.copy(alpha = c.card.alpha * cfg.opacity / 100f).toArgb() }
            cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), dp(cfg.corner.toFloat()), dp(cfg.corner.toFloat()), bg)
        }
        fun pill(l: Float, t: Float, r: Float, b: Float, color: Color) {
            cv.drawRoundRect(RectF(l, t, r, b), (b - t) / 2, (b - t) / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() })
        }
    }

    // ------------------------------------------------------------------ widget meteo

    /**
     * Si adatta alla grandezza: le parti scelte vengono disegnate dall'alto finché c'è spazio
     * (meteo attuale, dati extra, ore, grafico pioggia, giorni).
     */
    private fun drawWeatherWidget(ctx: Context, cfg: WidgetCfg, wDp: Int, hDp: Int, place: Place?, f: Forecast?, busy: Boolean): Rendered {
        val p = Pen(ctx, cfg, wDp, hDp)
        val c = p.c; val fmt = p.fmt; val s = p.s; val w = p.w; val h = p.h; val cv = p.cv
        fun dp(v: Float) = p.dp(v)
        p.background()
        val pad = dp(if (hDp < 90) 12f else 16f)
        val refreshSpace = if (wDp >= 140 && hDp >= 56) dp(40f) else 0f
        if (place == null || f == null) {
            cv.drawText("MLMeteo", pad, pad + dp(18f), p.text(18f, c.ink, true))
            val msg = if (place == null) "Aggiungi una località nell'app" else if (busy) "Aggiornamento…" else "Tocca ⟳ o apri l'app"
            cv.drawText(p.fit(p.text(13f, c.inkDim), msg, w - pad * 2), pad, pad + dp(40f), p.text(13f, c.inkDim))
            return Rendered(p.bmp, listOf("main" to hDp.toFloat()))
        }
        val cur = f.current
        val today = f.days.firstOrNull()
        val upd = Instant.ofEpochMilli(f.fetchedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val updTxt = if (busy) "Aggiornamento…" else "Agg. ${fmt.time(upd)}"
        val zones = ArrayList<Pair<String, Float>>()
        var zoneStart = 0f
        fun closeZone(name: String, yPx: Float) {
            val end = yPx / p.scale
            if (end > zoneStart) { zones += name to (end - zoneStart); zoneStart = end }
        }

        // --- piccolo (altezza sotto i 110 dp): una riga sola
        if (hDp < 110) {
            val ic = (h - pad * 2).coerceAtMost(dp(64f)).toInt()
            cv.drawBitmap(icon(cur.code, cur.isDay, ic, s.iconSet, c), pad, (h - ic) / 2f, null)
            var x = pad + ic + dp(10f)
            val tSize = (hDp * 0.42f).coerceIn(22f, 44f)
            val tp = p.text(tSize, c.ink, true)
            val temp = fmt.temp(cur.temp)
            val base = h / 2f + dp(tSize * 0.35f)
            cv.drawText(temp, x, base, tp)
            x += tp.measureText(temp) + dp(10f)
            var avail = w - x - pad - refreshSpace
            // dati extra a destra, se il widget è largo
            val ex = extrasFor(ctx, cfg, place, f)
            val exN = if (wDp >= 330) ((avail - dp(130f)) / dp(84f)).toInt().coerceIn(0, minOf(2, ex.size)) else 0
            if (exN > 0) {
                var ex0 = w - pad - refreshSpace - exN * dp(84f)
                ex.take(exN).forEach { (iv, v) ->
                    drawVector(cv, iv, ex0, h / 2f - dp(9f), dp(18f), c.accent.toArgb())
                    cv.drawText(p.fit(p.text(12f, c.ink), v, dp(60f)), ex0 + dp(22f), h / 2f + dp(5f), p.text(12f, c.ink))
                    ex0 += dp(84f)
                }
                avail -= exN * dp(84f)
            }
            if (avail > dp(60f)) {
                val nameP = p.text(13f, c.ink, true)
                cv.drawText(p.fit(nameP, place.name, avail), x, base - dp(tSize * 0.45f), nameP)
                val sub = if (wDp >= 240 && exN == 0) "${conditionText(cur.code, cur.isDay)} · ${fmt.temp(today?.tMax ?: Double.NaN)}/${fmt.temp(today?.tMin ?: Double.NaN)}"
                    else conditionText(cur.code, cur.isDay)
                cv.drawText(p.fit(p.text(12f, c.inkDim), sub, avail), x, base, p.text(12f, c.inkDim))
            }
            if (cfg.showUpdated && refreshSpace > 0 && wDp >= 200) cv.drawText(updTxt, w - pad, h - dp(10f), p.text(10f, c.inkDim, align = Paint.Align.RIGHT))
            return Rendered(p.bmp, listOf("main" to hDp.toFloat()))
        }

        // ---------------- piano: cosa entra, in ordine di priorità
        // meteo attuale → prossime ore → dati extra → pioggia → giorni
        val ex = extrasFor(ctx, cfg, place, f)
        // con il widget largo i dati extra vanno a destra della temperatura (spazio che altrimenti resta vuoto)
        val sideExtras = cfg.showCurrent && ex.isNotEmpty() && wDp >= 290
        val sCur = cfg.size("current"); val sEx = cfg.size("extras"); val sRain = cfg.size("rain")
        val hIs = cfg.size("hoursIcon"); val hTs = cfg.size("hoursText"); val dIs = cfg.size("daysIcon"); val dTs = cfg.size("daysText")
        val icDp = (if (hDp < 170) 52f else 64f) * sCur
        val headerDp = 16f + (if (cfg.showUpdated || sideExtras) 16f else 0f) + 8f
        var free = hDp - (pad / p.scale) - headerDp - (if (cfg.showCurrent) icDp + 10f else 0f) - 6f
        val hrs24 = f.hours.take(24)
        val anyRain = hrs24.any { (it.precip.ok() && it.precip >= 0.1) || (it.precipProb.ok() && it.precipProb >= 20) }
        // pillole: complete (con % e mm) o compatte; l'altezza dipende dalla grandezza di icone e testo
        fun fullDp(isz: Float, ts: Float) = 22f * ts + 32f * isz + 16f * ts + 14f * ts + 12f * ts + 8f
        fun smallDp(isz: Float, ts: Float) = 18f * ts + 24f * isz + 14f * ts + 6f
        fun pillDp(isz: Float, ts: Float): Float = if (free >= fullDp(isz, ts) + 10f) fullDp(isz, ts) else if (free >= smallDp(isz, ts) + 10f) smallDp(isz, ts) else 0f
        var hoursDp = 0f
        if (cfg.showHours) {
            hoursDp = pillDp(hIs, hTs)
            if (hoursDp > 0) free -= hoursDp + 10f
        }
        var extrasRow = false
        if (!sideExtras && ex.isNotEmpty() && free >= 38f * sEx) { extrasRow = true; free -= 38f * sEx }
        var rainDp = 0f
        if (cfg.showRain && hrs24.isNotEmpty()) {
            val want = if (anyRain) 36f + 40f * sRain else 30f
            if (free >= want + 10f) { rainDp = want; free -= want + 10f }
        }
        var daysDp = 0f
        if (cfg.showDays && f.days.isNotEmpty()) {
            daysDp = pillDp(dIs, dTs)
            if (daysDp > 0) free -= daysDp + 10f
        }
        // lo spazio che avanza viene diviso tra le parti
        val parts = listOf(cfg.showCurrent, hoursDp > 0, extrasRow, rainDp > 0, daysDp > 0).count { it }
        val gap = dp((free / (parts + 1)).coerceIn(0f, 16f))

        // --- intestazione
        var y = pad + dp(16f) + gap / 2
        val nameP = p.text(16f, c.ink, true)
        cv.drawText(p.fit(nameP, place.name, w - pad * 2 - refreshSpace - dp(28f)), pad, y, nameP)
        if (sideExtras) {
            y += dp(16f)
            val sub = listOfNotNull(
                conditionText(cur.code, cur.isDay),
                "${fmt.temp(today?.tMax ?: Double.NaN)}/${fmt.temp(today?.tMin ?: Double.NaN)}",
                if (cfg.showUpdated) updTxt.lowercase().replaceFirstChar { it } else null,
            ).joinToString(" · ")
            cv.drawText(p.fit(p.text(12f, c.inkDim), sub, w - pad * 2 - refreshSpace), pad, y, p.text(12f, c.inkDim))
        } else if (cfg.showUpdated) {
            y += dp(16f)
            cv.drawText(updTxt, pad, y, p.text(11f, c.inkDim))
        }
        y += dp(8f) + gap
        if (cfg.showCurrent) {
            val ic = dp(icDp).toInt()
            cv.drawBitmap(icon(cur.code, cur.isDay, ic, s.iconSet, c), pad, y, null)
            val tp = p.text((if (hDp < 170) 36f else 44f) * sCur, c.ink, true)
            val tX = pad + ic + dp(12f)
            cv.drawText(fmt.temp(cur.temp), tX, y + ic * 0.72f, tp)
            val tx = tX + tp.measureText(fmt.temp(cur.temp)) + dp(14f)
            val availR = w - tx - pad
            if (sideExtras) {
                // griglia 2×2 (o 1 colonna se lo spazio è poco)
                var cols = if (ex.size >= 2 && availR / 2 >= dp(76f)) 2 else 1
                if (availR < dp(70f)) cols = 0
                if (cols > 0) {
                    val rows = 2
                    val n = (cols * rows).coerceAtMost(ex.size)
                    val cw = availR / cols
                    val ch = (ic - dp(6f)) / 2
                    ex.take(n).forEachIndexed { i, (iv, v) ->
                        val col = i % cols; val row = i / cols
                        val x0 = tx + cw * col; val y0 = y + row * (ch + dp(6f))
                        p.pill(x0 + dp(2f), y0, x0 + cw - dp(2f), y0 + ch, c.card2.copy(alpha = c.card2.alpha * maxOf(cfg.opacity, 40) / 100f))
                        val isz = (ch * 0.58f).coerceAtMost(dp(16f))
                        drawVector(cv, iv, x0 + dp(9f), y0 + (ch - isz) / 2, isz, c.accent.toArgb())
                        val tpe = p.text(12f * sEx, c.ink)
                        cv.drawText(p.fit(tpe, v, cw - isz - dp(20f)), x0 + dp(13f) + isz, y0 + ch / 2 + dp(4.5f), tpe)
                    }
                }
            } else if (availR > dp(70f)) {
                cv.drawText(p.fit(p.text(14f, c.ink), conditionText(cur.code, cur.isDay), availR), tx, y + ic * 0.42f, p.text(14f, c.ink))
                cv.drawText(p.fit(p.text(12f, c.inkDim), "Max ${fmt.temp(today?.tMax ?: Double.NaN)} · Min ${fmt.temp(today?.tMin ?: Double.NaN)}", availR),
                    tx, y + ic * 0.72f, p.text(12f, c.inkDim))
            }
            y += ic + dp(10f) + gap
        }

        // --- dati extra in riga (widget stretti)
        fun extrasLine() {
            val n = ((w - pad * 2) / dp(74f * sEx)).toInt().coerceIn(1, ex.size)
            val colW = (w - pad * 2) / n
            ex.take(n).forEachIndexed { i, (iv, v) ->
                val x0 = pad + colW * i
                p.pill(x0 + dp(2f), y, x0 + colW - dp(2f), y + dp(28f * sEx), c.card2.copy(alpha = c.card2.alpha * maxOf(cfg.opacity, 40) / 100f))
                drawVector(cv, iv, x0 + dp(10f * sEx), y + dp(6f * sEx), dp(16f * sEx), c.accent.toArgb())
                cv.drawText(p.fit(p.text(12f * sEx, c.ink), v, colW - dp(36f * sEx)), x0 + dp(30f * sEx), y + dp(19f * sEx), p.text(12f * sEx, c.ink))
            }
            y += dp(28f * sEx) + dp(10f) + gap
        }
        if (extrasRow) extrasLine()
        closeZone("main", y - gap / 2)

        // --- pillole (ore e giorni): etichetta, icona, temperatura, % e mm di pioggia
        val rainBlue = Color(0xFF6CB8FF)
        class PillItem(val label: String, val code: Int, val isDay: Boolean, val temp: String, val prob: Double, val mm: Double)
        fun pills(items: List<PillItem>, heightDp: Float, highlightFirst: Boolean, isz: Float, ts: Float) {
            val hh = dp(heightDp)
            val small = heightDp < fullDp(isz, ts) - 0.5f
            val colDp = (if (small) 46f else 52f) * maxOf(ts, isz * 0.85f, 1f)
            val n = ((w - pad * 2) / dp(colDp)).toInt().coerceIn(3, 12).coerceAtMost(items.size)
            if (n <= 0) return
            val colW = (w - pad * 2) / n
            items.take(n).forEachIndexed { i, item ->
                val cx = pad + colW * i + colW / 2
                val hl = highlightFirst && i == 0
                if (hl) p.pill(cx - colW / 2 + dp(2f), y, cx + colW / 2 - dp(2f), y + hh, c.accentContainer)
                else if (!small) p.pill(cx - colW / 2 + dp(2f), y, cx + colW / 2 - dp(2f), y + hh, c.card2.copy(alpha = c.card2.alpha * maxOf(cfg.opacity, 40) / 100f))
                val ink = if (hl) c.onAccentContainer else c.ink
                var yy = y + dp((if (small) 14f else 16f) * ts)
                cv.drawText(item.label, cx, yy, p.text(11f * ts, if (hl) ink else c.inkDim, align = Paint.Align.CENTER))
                yy += dp(4f * ts)
                val hi = dp((if (small) 24f else 32f) * isz).toInt()
                cv.drawBitmap(icon(item.code, item.isDay, hi, s.iconSet, c), cx - hi / 2f, yy, null)
                yy += hi + dp((if (small) 13f else 15f) * ts)
                cv.drawText(item.temp, cx, yy, p.text(13f * ts, ink, true, Paint.Align.CENTER))
                if (!small) {
                    yy += dp(14f * ts)
                    if (item.prob.ok() && item.prob >= 1) cv.drawText("${item.prob.roundToInt()}%", cx, yy, p.text(10f * ts, if (hl) ink else c.accent, true, Paint.Align.CENTER))
                    yy += dp(12f * ts)
                    if ((item.mm.ok() && item.mm >= 0.05) || (item.prob.ok() && item.prob >= 1)) cv.drawText(p.fit(p.text(10f * ts, rainBlue, true), fmt.rain(if (item.mm.ok()) item.mm else 0.0), colW - dp(4f)), cx, yy, p.text(10f * ts, rainBlue, true, Paint.Align.CENTER))
                }
            }
        }

        // --- prossime ore
        if (hoursDp > 0) {
            pills(f.hours.take(12).mapIndexed { i, hr ->
                PillItem(if (i == 0) "Ora" else fmt.hourLabel(hr.time), hr.code, hr.isDay, fmt.temp(if (i == 0) cur.temp else hr.temp), hr.precipProb, hr.precip)
            }, hoursDp, true, hIs, hTs)
            y += dp(hoursDp) + dp(10f) + gap
            closeZone("hours", y - gap / 2)
        }

        // --- grafico della pioggia (prossime ore)
        if (rainDp > 0) {
            val hrs = hrs24
            if (!anyRain) {
                drawVector(cv, Icons.Filled.WaterDrop, pad, y + dp(4f), dp(16f), c.accent.toArgb())
                cv.drawText("Niente pioggia nelle prossime 24 ore", pad + dp(22f), y + dp(17f), p.text(12f, c.inkDim))
            } else {
                val total = hrs.sumOf { if (it.precip.ok()) it.precip else 0.0 }
                cv.drawText("Pioggia prossime ${hrs.size} ore", pad, y + dp(13f), p.text(12f, c.ink, true))
                cv.drawText(fmt.rain(total), w - pad, y + dp(13f), p.text(12f, c.accent, true, Paint.Align.RIGHT))
                val top = y + dp(20f); val bottom = y + dp(20f + 40f * sRain)
                val maxMm = hrs.maxOf { if (it.precip.ok()) it.precip else 0.0 }.coerceAtLeast(2.0)
                val hasProb = hrs.any { it.precipProb.ok() }
                val bw = (w - pad * 2) / hrs.size
                val probP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent.copy(alpha = 0.18f).toArgb() }
                val mmP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent.toArgb() }
                hrs.forEachIndexed { i, hr ->
                    val x0 = pad + bw * i + bw * 0.15f; val x1 = pad + bw * (i + 1) - bw * 0.15f
                    if (hasProb && hr.precipProb.ok() && hr.precipProb > 0) {
                        val ph = ((hr.precipProb / 100.0) * (bottom - top)).toFloat()
                        cv.drawRoundRect(RectF(x0, bottom - ph, x1, bottom), dp(2f), dp(2f), probP)
                    }
                    val v = if (hr.precip.ok()) hr.precip else 0.0
                    if (v > 0) {
                        val bh = (v / maxMm * (bottom - top)).toFloat().coerceAtLeast(dp(2f))
                        cv.drawRoundRect(RectF(x0, bottom - bh, x1, bottom), dp(2f), dp(2f), mmP)
                    }
                }
                for (i in listOf(0, hrs.size / 2, hrs.size - 1)) {
                    val al = when (i) { 0 -> Paint.Align.LEFT; hrs.size - 1 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                    val x = when (i) { 0 -> pad; hrs.size - 1 -> w - pad; else -> pad + bw * i + bw / 2 }
                    cv.drawText(if (i == 0) "Ora" else fmt.hourLabel(hrs[i].time), x, bottom + dp(13f), p.text(10f, c.inkDim, align = al))
                }
            }
            y += dp(rainDp) + dp(10f) + gap
            closeZone("rain", y - gap / 2)
        }

        // --- prossimi giorni (pillole come le ore)
        if (daysDp > 0) {
            val todayDate = f.localNow.toLocalDate()
            pills(f.days.take(12).map { d ->
                val name = if (d.date == todayDate) "Oggi"
                    else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ITALY).replaceFirstChar { it.uppercase() }.trimEnd('.')
                PillItem(name, d.code, true, fmt.temp(d.tMax), d.precipProb, d.precipSum)
            }, daysDp, f.days.firstOrNull()?.date == todayDate, dIs, dTs)
            y += dp(daysDp) + dp(10f)
            closeZone("days", h.toFloat())
        }
        // l'ultima zona arriva fino in fondo
        if (zones.isNotEmpty() && zoneStart < hDp) {
            val last = zones.removeAt(zones.lastIndex)
            zones += last.first to (last.second + hDp - zoneStart)
        }
        return Rendered(p.bmp, zones)
    }

    /** Valori dei dati extra scelti: (icona, testo). */
    private fun extrasFor(ctx: Context, cfg: WidgetCfg, place: Place, f: Forecast): List<Pair<ImageVector, String>> {
        val fmt = Fmt(Prefs.loadSettings(ctx))
        val cur = f.current
        val today = f.days.firstOrNull()
        return cfg.extras.take(4).mapNotNull { e ->
            when (e) {
                "wind" -> Icons.Filled.Air to fmt.wind(cur.windSpeed)
                "humidity" -> Icons.Filled.WaterDrop to (if (cur.humidity.ok()) "${cur.humidity.roundToInt()}%" else "—")
                "feels" -> Icons.Filled.Thermostat to fmt.temp(cur.feels)
                "uv" -> Icons.Filled.WbSunny to (if (cur.uv.ok()) "UV ${cur.uv.roundToInt()}" else "UV —")
                "sun" -> {
                    val now = f.localNow
                    val rise = today?.sunrise; val set = today?.sunset
                    val txt = when {
                        rise != null && now.isBefore(rise) -> "↑ ${fmt.time(rise)}"
                        set != null && now.isBefore(set) -> "↓ ${fmt.time(set)}"
                        else -> f.days.getOrNull(1)?.sunrise?.let { "↑ ${fmt.time(it)}" } ?: "—"
                    }
                    Icons.Filled.WbTwilight to txt
                }
                "air" -> {
                    val a = WidgetStore.air(ctx, place.id)
                    Icons.Filled.Eco to (if (a.ok()) "AQI ${a.roundToInt()}" else "AQI —")
                }
                "pressure" -> Icons.Filled.Speed to fmt.pressure(cur.pressure)
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ grafico pioggia

    /** Probabilità di pioggia (area) e millimetri (barre) nelle prossime ore: più è largo, più ore. */
    private fun drawRainWidget(ctx: Context, cfg: WidgetCfg, wDp: Int, hDp: Int, place: Place?, f: Forecast?, busy: Boolean): Bitmap {
        val p = Pen(ctx, cfg, wDp, hDp)
        val c = p.c; val cv = p.cv; val w = p.w; val h = p.h
        fun dp(v: Float) = p.dp(v)
        p.background()
        val pad = dp(14f)
        val probCol = Color(0xFF64B5F6); val mmCol = Color(0xFF1E88E5)
        if (place == null || f == null) {
            cv.drawText(if (busy) "Aggiornamento…" else "Pioggia: tocca ⟳", pad, pad + dp(16f), p.text(13f, c.ink, true))
            return p.bmp
        }
        val n = ((wDp - 60) / 13).coerceIn(6, 24)
        val hrs = f.hours.take(n)
        val total = hrs.sumOf { if (it.precip.ok()) it.precip else 0.0 }
        val maxProb = hrs.mapNotNull { x -> x.precipProb.takeIf { it.ok() } }.maxOrNull()
        val rightSpace = if (wDp >= 140) dp(72f) else 0f
        val title = if (wDp >= 260) "Pioggia · ${place.name}" else "Pioggia"
        cv.drawText(p.fit(p.text(14f, c.ink, true), title, w - pad * 2 - rightSpace), pad, pad + dp(14f), p.text(14f, c.ink, true))
        val sub = fmt2(p.fmt, total) + (maxProb?.let { " · max ${it.roundToInt()}%" } ?: "")
        cv.drawText(p.fit(p.text(11f, c.inkDim), sub, w - pad * 2 - rightSpace), pad, pad + dp(30f), p.text(11f, c.inkDim))
        val axisW = dp(34f)
        val left = pad + axisW; val right = w - pad
        val top = pad + dp(44f); val bottom = h - pad - dp(16f)
        if (bottom - top < dp(24f) || hrs.size < 2) return p.bmp
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.outline.toArgb(); strokeWidth = dp(1f) }
        listOf(0f, 0.5f, 1f).forEachIndexed { i, fr ->
            val yy = top + (bottom - top) * fr
            cv.drawLine(left, yy, right, yy, grid)
            cv.drawText(listOf("100%", "50%", "0%")[i], left - dp(6f), yy + dp(4f), p.text(10f, probCol, align = Paint.Align.RIGHT))
        }
        val slot = (right - left) / hrs.size
        // area della probabilità
        val path = Path(); val line = Path()
        hrs.forEachIndexed { i, x ->
            val pr = if (x.precipProb.ok()) x.precipProb else 0.0
            val px = left + slot * i + slot / 2
            val py = (bottom - (bottom - top) * pr / 100.0).toFloat()
            if (i == 0) { path.moveTo(px, bottom); path.lineTo(px, py); line.moveTo(px, py) } else { path.lineTo(px, py); line.lineTo(px, py) }
            if (i == hrs.lastIndex) { path.lineTo(px, bottom); path.close() }
        }
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = probCol.copy(alpha = 0.4f).toArgb() })
        cv.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = probCol.toArgb(); style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeJoin = Paint.Join.ROUND })
        // barre dei millimetri
        val maxMm = hrs.maxOf { if (it.precip.ok()) it.precip else 0.0 }.coerceAtLeast(2.0)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mmCol.toArgb() }
        hrs.forEachIndexed { i, x ->
            val v = if (x.precip.ok()) x.precip else 0.0
            if (v > 0) {
                val bh = ((v / maxMm) * (bottom - top)).toFloat().coerceAtLeast(dp(2f))
                val x0 = left + slot * i + slot * 0.22f
                cv.drawRoundRect(RectF(x0, bottom - bh, x0 + slot * 0.56f, bottom), dp(2f), dp(2f), bar)
            }
        }
        // ore
        val step = when { hrs.size > 16 -> 4; hrs.size > 8 -> 3; else -> 2 }
        for (i in hrs.indices step step) {
            val px = left + slot * i + slot / 2
            cv.drawText(if (i == 0) "Ora" else p.fmt.hourLabel(hrs[i].time), px, bottom + dp(13f), p.text(10f, c.inkDim, align = Paint.Align.CENTER))
        }
        return p.bmp
    }

    private fun fmt2(fmt: Fmt, mm: Double) = if (mm < 0.05) "Niente pioggia" else fmt.rain(mm)

    // ------------------------------------------------------------------ mini 1×1

    private fun drawMini(ctx: Context, cfg: WidgetCfg, wDp: Int, hDp: Int, f: Forecast?): Bitmap {
        val p = Pen(ctx, cfg, wDp, hDp)
        val c = p.c
        p.background()
        val side = minOf(p.w, p.h).toFloat()
        if (f == null) {
            p.cv.drawText("—", p.w / 2f, p.h / 2f + p.dp(8f), p.text(22f, c.ink, true, Paint.Align.CENTER))
            return p.bmp
        }
        val ic = (side * 0.48f).toInt()
        val tSize = (minOf(wDp, hDp) * 0.24f).coerceIn(12f, 40f)
        val total = ic + p.dp(tSize)
        val top = (p.h - total) / 2f
        p.cv.drawBitmap(icon(f.current.code, f.current.isDay, ic, p.s.iconSet, c), (p.w - ic) / 2f, top, null)
        p.cv.drawText(p.fmt.temp(f.current.temp), p.w / 2f, top + ic + p.dp(tSize * 0.92f), p.text(tSize, c.ink, true, Paint.Align.CENTER))
        return p.bmp
    }

    // ------------------------------------------------------------------ radar

    private val darkMap = ColorMatrixColorFilter(floatArrayOf(
        -0.22f, -0.43f, -0.085f, 0f, 205f,
        -0.22f, -0.43f, -0.085f, 0f, 207f,
        -0.22f, -0.43f, -0.085f, 0f, 218f,
        0f, 0f, 0f, 1f, 0f,
    ))

    /** Mappa centrata sulla località con l'ultima immagine del radar. Più è grande, più zona mostra. */
    private fun drawRadar(ctx: Context, cfg: WidgetCfg, wDp: Int, hDp: Int, place: Place?, busy: Boolean): Bitmap {
        val p = Pen(ctx, cfg, wDp, hDp)
        val c = p.c; val cv = p.cv; val w = p.w; val h = p.h
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), p.dp(cfg.corner.toFloat()), p.dp(cfg.corner.toFloat()), Path.Direction.CW) }
        cv.save()
        cv.clipPath(clip)
        cv.drawColor(c.card.toArgb())
        if (place == null) {
            cv.restore()
            cv.drawText("Aggiungi una località", p.dp(16f), p.dp(30f), p.text(13f, c.ink))
            return p.bmp
        }
        // tessere della mappa
        val z = RadarWidgetData.tileZoom(p.scale)
        val world = 256.0 * (1 shl z)
        val k = (1 shl (z - RadarWidgetData.BASE_ZOOM)).toDouble() // pixel di mappa per dp
        val pxPerMap = p.scale / k // pixel dell'immagine per pixel di mappa
        val cx = lonToX(place.lon) * world; val cy = latToY(place.lat) * world
        val left = cx - (w / pxPerMap) / 2; val topM = cy - (h / pxPerMap) / 2
        val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { if (c.dark) colorFilter = darkMap }
        var missing = 0
        val n = 1 shl z
        val tx0 = (left / 256).toInt(); val tx1 = ((left + w / pxPerMap) / 256).toInt()
        val ty0 = (topM / 256).toInt(); val ty1 = ((topM + h / pxPerMap) / 256).toInt()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val bm = RadarWidgetData.tile(ctx, z, ((tx % n) + n) % n, ty.coerceIn(0, n - 1))
            val dx = ((tx * 256 - left) * pxPerMap).toFloat(); val dy = ((ty * 256 - topM) * pxPerMap).toFloat()
            val size = (256 * pxPerMap).toFloat()
            if (bm != null) cv.drawBitmap(bm, null, RectF(dx, dy, dx + size, dy + size), tilePaint) else missing++
        }
        // radar
        val fr = RadarWidgetData.frame(ctx)
        val inside = DpcRadar.contains(place.lat, place.lon)
        if (fr != null) {
            val gw = DpcRadar.W; val gh = DpcRadar.H
            val gx0 = lonToX(DpcRadar.WEST); val gx1 = lonToX(DpcRadar.EAST)
            val gy0 = latToY(DpcRadar.NORTH); val gy1 = latToY(DpcRadar.SOUTH)
            val step = 2 // un pixel ogni 2 (poi ingrandito): più veloce e già morbido
            val ow = (w + step - 1) / step; val oh = (h + step - 1) / step
            val px = IntArray(ow * oh)
            for (j in 0 until oh) {
                val my = (topM + (j * step + 0.5) / pxPerMap) / world
                val gj = ((my - gy0) / (gy1 - gy0) * gh).toInt()
                if (gj !in 0 until gh) continue
                for (i in 0 until ow) {
                    val mx = (left + (i * step + 0.5) / pxPerMap) / world
                    val gi = ((mx - gx0) / (gx1 - gx0) * gw).toInt()
                    if (gi !in 0 until gw) continue
                    px[j * ow + i] = DpcRadar.colorOf(fr.q[gj * gw + gi].toInt())
                }
            }
            val ov = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
            cv.drawBitmap(ov, null, RectF(0f, 0f, (ow * step).toFloat(), (oh * step).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        // posizione
        cv.drawCircle(w / 2f, h / 2f, p.dp(7f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        cv.drawCircle(w / 2f, h / 2f, p.dp(5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent.toArgb() })
        cv.restore()
        // etichetta
        val label = when {
            !inside -> "Radar disponibile solo in Italia"
            fr == null || missing > 0 && busy -> if (busy) "Aggiornamento…" else "Tocca ⟳ per scaricare"
            else -> "Radar " + p.fmt.time(Instant.ofEpochMilli(fr.time).atZone(ZoneId.systemDefault()).toLocalDateTime()) +
                (if (cfg.showUpdated) "" else "")
        }
        val tp = p.text(12f, c.ink, true)
        val lw = tp.measureText(label) + p.dp(20f)
        p.pill(p.dp(8f), h - p.dp(34f), p.dp(8f) + lw, h - p.dp(8f), c.card.copy(alpha = 0.85f))
        cv.drawText(label, p.dp(18f), h - p.dp(16f), tp)
        return p.bmp
    }
}

/** Riceve il tocco sull'icona di aggiornamento dei widget. */
class WidgetRefreshReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_REFRESH) Widgets.refreshNow(context)
    }
}
