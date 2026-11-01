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

    fun refreshDone(ctx: Context) {
        ctx.getSharedPreferences("mlmeteo_widgets", Context.MODE_PRIVATE).edit().remove("refreshing").apply()
    }

    private fun refreshing(ctx: Context): Boolean {
        val t = ctx.getSharedPreferences("mlmeteo_widgets", Context.MODE_PRIVATE).getLong("refreshing", 0)
        return System.currentTimeMillis() - t < 60_000
    }

    /** Dimensioni reali del widget in dp (cambiano quando lo ridimensioni). */
    private fun sizeDp(ctx: Context, m: AppWidgetManager, id: Int, kind: WidgetKind): Pair<Int, Int> {
        val opts = m.getAppWidgetOptions(id)
        val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes = opts.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                // in verticale la misura più stretta e alta, in orizzontale la più larga
                val sz = if (portrait) sizes.minByOrNull { it.width }!! else sizes.maxByOrNull { it.width }!!
                if (sz.width > 0 && sz.height > 0) return sz.width.toInt() to sz.height.toInt()
            }
        }
        var w = if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        var h = if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (w <= 0) w = when (kind) { WidgetKind.SMALL -> 180; else -> 320 }
        if (h <= 0) h = when (kind) { WidgetKind.SMALL -> 90; WidgetKind.MEDIUM -> 180; WidgetKind.LARGE -> 360 }
        return w to h
    }

    fun update(ctx: Context, m: AppWidgetManager, id: Int, kind: WidgetKind) {
        try {
            val (wDp, hDp) = sizeDp(ctx, m, id, kind)
            val place = placeFor(ctx, id)
            val f = place?.let { ForecastStore.load(ctx, it.id) }
            val s = Prefs.loadSettings(ctx)
            val c = colors(ctx, s)
            val busy = refreshing(ctx)
            val bmp = draw(ctx, wDp, hDp, place, f, busy)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_image)
            rv.setImageViewBitmap(R.id.widget_image, bmp)
            val open = PendingIntent.getActivity(
                ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.widget_image, open)
            // icona per aggiornare (nascosta se il widget è minuscolo)
            val refresh = PendingIntent.getBroadcast(
                ctx, 10_000 + id, Intent(ctx, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.widget_refresh, refresh)
            rv.setInt(R.id.widget_refresh, "setColorFilter", (if (busy) c.accent else c.inkDim).toArgb())
            rv.setViewVisibility(R.id.widget_refresh, if (wDp >= 140 && hDp >= 56) android.view.View.VISIBLE else android.view.View.GONE)
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

    /**
     * Disegna il widget adattandosi alla grandezza:
     * basso → solo temperatura; medio → anche le prossime ore; alto → anche i prossimi giorni.
     */
    fun draw(ctx: Context, wDp: Int, hDp: Int, place: Place?, f: Forecast?, busy: Boolean = false): Bitmap {
        val s = Prefs.loadSettings(ctx)
        val c = colors(ctx, s)
        val fmt = Fmt(s)
        val dens = ctx.resources.displayMetrics.density
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
        fun fit(p: Paint, t: String, maxW: Float): String {
            if (p.measureText(t) <= maxW) return t
            var x = t
            while (x.length > 1 && p.measureText("$x…") > maxW) x = x.dropLast(1)
            return "$x…"
        }
        val pad = dp(if (hDp < 90) 12f else 16f)
        val refreshSpace = if (wDp >= 140 && hDp >= 56) dp(40f) else 0f
        if (place == null || f == null) {
            cv.drawText("MLMeteo", pad, pad + dp(18f), text(18f, c.ink, true))
            val msg = if (place == null) "Aggiungi una località nell'app" else if (busy) "Aggiornamento…" else "Tocca ⟳ o apri l'app"
            cv.drawText(fit(text(13f, c.inkDim), msg, w - pad * 2), pad, pad + dp(40f), text(13f, c.inkDim))
            return bmp
        }
        val cur = f.current
        val today = f.days.firstOrNull()
        val upd = Instant.ofEpochMilli(f.fetchedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val updTxt = if (busy) "Aggiornamento…" else "Agg. ${fmt.time(upd)}"

        // --- piccolo (altezza sotto i 110 dp): una riga sola
        if (hDp < 110) {
            val ic = (h - pad * 2).coerceAtMost(dp(64f)).toInt()
            cv.drawBitmap(icon(cur.code, cur.isDay, ic, s.iconSet, c), pad, (h - ic) / 2f, null)
            var x = pad + ic + dp(10f)
            val tSize = (hDp * 0.42f).coerceIn(22f, 44f)
            val tp = text(tSize, c.ink, true)
            val temp = fmt.temp(cur.temp)
            val base = h / 2f + dp(tSize * 0.35f)
            cv.drawText(temp, x, base, tp)
            x += tp.measureText(temp) + dp(10f)
            val avail = w - x - pad - refreshSpace
            if (avail > dp(60f)) {
                val nameP = text(13f, c.ink, true)
                cv.drawText(fit(nameP, place.name, avail), x, base - dp(tSize * 0.45f), nameP)
                val sub = if (wDp >= 240) "${conditionText(cur.code, cur.isDay)} · ${fmt.temp(today?.tMax ?: Double.NaN)}/${fmt.temp(today?.tMin ?: Double.NaN)}"
                    else conditionText(cur.code, cur.isDay)
                cv.drawText(fit(text(12f, c.inkDim), sub, avail), x, base, text(12f, c.inkDim))
            }
            if (refreshSpace > 0 && wDp >= 200) cv.drawText(updTxt, w - pad, h - dp(10f), text(10f, c.inkDim, align = Paint.Align.RIGHT))
            return bmp
        }

        // --- intestazione
        var y = pad + dp(16f)
        val nameP = text(16f, c.ink, true)
        cv.drawText(fit(nameP, place.name, w - pad * 2 - refreshSpace), pad, y, nameP)
        y += dp(16f)
        cv.drawText(updTxt, pad, y, text(11f, c.inkDim))
        y += dp(8f)
        val ic = (if (hDp < 160) dp(52f) else dp(64f)).toInt()
        cv.drawBitmap(icon(cur.code, cur.isDay, ic, s.iconSet, c), pad, y, null)
        val tp = text(if (hDp < 160) 36f else 44f, c.ink, true)
        val tX = pad + ic + dp(12f)
        cv.drawText(fmt.temp(cur.temp), tX, y + ic * 0.72f, tp)
        val tx = tX + tp.measureText(fmt.temp(cur.temp)) + dp(14f)
        val availR = w - tx - pad
        if (availR > dp(70f)) {
            cv.drawText(fit(text(14f, c.ink), conditionText(cur.code, cur.isDay), availR), tx, y + ic * 0.42f, text(14f, c.ink))
            cv.drawText(fit(text(12f, c.inkDim), "Max ${fmt.temp(today?.tMax ?: Double.NaN)} · Min ${fmt.temp(today?.tMin ?: Double.NaN)}", availR),
                tx, y + ic * 0.72f, text(12f, c.inkDim))
        }
        y += ic + dp(10f)

        // --- prossime ore (se c'è spazio)
        val hoursH = dp(78f)
        if (y + hoursH <= h - dp(6f)) {
            val n = ((w - pad * 2) / dp(52f)).toInt().coerceIn(3, 10)
            val colW = (w - pad * 2) / n
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

        // --- prossimi giorni (quanti ne entrano)
        val rowH = dp(30f)
        if (y + rowH <= h - dp(4f)) {
            val days = f.days.take(16)
            val shown = ((h - dp(4f) - y) / rowH).toInt().coerceIn(0, days.size)
            val list = days.take(shown)
            val lo = list.minOfOrNull { it.tMin } ?: 0.0
            val hi = list.maxOfOrNull { it.tMax } ?: 1.0
            val span = (hi - lo).coerceAtLeast(1.0)
            val todayDate = f.localNow.toLocalDate()
            val showBar = wDp >= 250
            for (d in list) {
                val name = when (d.date) {
                    todayDate -> "Oggi"
                    todayDate.plusDays(1) -> "Domani"
                    else -> d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ITALY).replaceFirstChar { it.uppercase() } + " ${d.date.dayOfMonth}"
                }
                cv.drawText(name, pad, y + dp(20f), text(13f, c.ink))
                val di = dp(22f).toInt()
                cv.drawBitmap(icon(d.code, true, di, s.iconSet, c), pad + dp(68f), y + dp(4f), null)
                cv.drawText(fmt.rainShort(d.precipProb, d.precipSum), pad + dp(96f), y + dp(20f), text(11f, c.accent, true))
                if (showBar) {
                    val bx0 = pad + dp(152f); val bx1 = w - pad - dp(36f)
                    cv.drawText(fmt.temp(d.tMin), bx0 - dp(6f), y + dp(20f), text(12f, c.inkDim, align = Paint.Align.RIGHT))
                    val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.outline.toArgb() }
                    val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent.toArgb() }
                    val by = y + dp(13f)
                    if (bx1 > bx0 + dp(20f)) {
                        cv.drawRoundRect(RectF(bx0 + dp(4f), by, bx1, by + dp(5f)), dp(3f), dp(3f), track)
                        val x0 = bx0 + dp(4f) + ((d.tMin - lo) / span * (bx1 - bx0 - dp(4f))).toFloat()
                        val x1 = bx0 + dp(4f) + ((d.tMax - lo) / span * (bx1 - bx0 - dp(4f))).toFloat()
                        cv.drawRoundRect(RectF(x0, by, x1.coerceAtLeast(x0 + dp(5f)), by + dp(5f)), dp(3f), dp(3f), bar)
                    }
                } else {
                    cv.drawText(fmt.temp(d.tMin), w - pad - dp(40f), y + dp(20f), text(12f, c.inkDim, align = Paint.Align.RIGHT))
                }
                cv.drawText(fmt.temp(d.tMax), w - pad, y + dp(20f), text(13f, c.ink, true, Paint.Align.RIGHT))
                y += rowH
            }
        }
        return bmp
    }
}

/** Riceve il tocco sull'icona di aggiornamento dei widget. */
class WidgetRefreshReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_REFRESH) Widgets.refreshNow(context)
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
