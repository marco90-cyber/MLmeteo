package com.marco.mlmeteo

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Tipo di widget: meteo (si adatta alla grandezza), mini 1×1, radar. */
enum class WidgetKind { WEATHER, MINI, RADAR, RAIN }

/** Impostazioni di un singolo widget, scelte con la procedura guidata. */
data class WidgetCfg(
    val place: String = "main",
    val showCurrent: Boolean = true,
    val showHours: Boolean = true,
    val showRain: Boolean = true,
    val showDays: Boolean = true,
    val showAlert: Boolean = true,
    val showUpdated: Boolean = true,
    val extras: List<String> = listOf("wind", "humidity"),
    /** Opacità dello sfondo, 0..100. */
    val opacity: Int = 100,
    /** "app" = come l'app, "wallpaper" = colori dello sfondo del telefono, "custom" = tinta scelta. */
    val colorMode: String = "app",
    val customColor: Long = 0xFF7FD0FF,
    /** "auto", "light" (testo chiaro su fondo scuro), "dark" (testo scuro su fondo chiaro). */
    val textMode: String = "auto",
    val corner: Int = 24,
    val tapMain: String = "app",
    val tapHours: String = "app",
    val tapRain: String = "radar",
    val tapDays: String = "app",
    /** Grandezza dei singoli elementi, in percentuale (100 = normale). */
    val sizes: Map<String, Int> = emptyMap(),
) {
    fun size(key: String): Float = (sizes[key] ?: 100).coerceIn(60, 160) / 100f
    fun toJson(): String = JSONObject().apply {
        put("place", place); put("showCurrent", showCurrent); put("showHours", showHours); put("showRain", showRain)
        put("showDays", showDays); put("showAlert", showAlert); put("showUpdated", showUpdated)
        put("extras", JSONArray(extras)); put("opacity", opacity); put("colorMode", colorMode); put("customColor", customColor)
        put("textMode", textMode); put("corner", corner)
        put("tapMain", tapMain); put("tapHours", tapHours); put("tapRain", tapRain); put("tapDays", tapDays)
        put("sizes", JSONObject().apply { sizes.forEach { (k, v) -> put(k, v) } })
    }.toString()

    companion object {
        fun fromJson(s: String?, def: WidgetCfg = WidgetCfg()): WidgetCfg {
            if (s.isNullOrBlank()) return def
            return try {
                val o = JSONObject(s)
                WidgetCfg(
                    place = o.optString("place", def.place),
                    showCurrent = o.optBoolean("showCurrent", def.showCurrent),
                    showHours = o.optBoolean("showHours", def.showHours),
                    showRain = o.optBoolean("showRain", def.showRain),
                    showDays = o.optBoolean("showDays", def.showDays),
                    showAlert = o.optBoolean("showAlert", def.showAlert),
                    showUpdated = o.optBoolean("showUpdated", def.showUpdated),
                    extras = o.optJSONArray("extras")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: def.extras,
                    opacity = o.optInt("opacity", def.opacity),
                    colorMode = o.optString("colorMode", def.colorMode),
                    customColor = o.optLong("customColor", def.customColor),
                    textMode = o.optString("textMode", def.textMode),
                    corner = o.optInt("corner", def.corner),
                    tapMain = o.optString("tapMain", def.tapMain),
                    tapHours = o.optString("tapHours", def.tapHours),
                    tapRain = o.optString("tapRain", def.tapRain),
                    tapDays = o.optString("tapDays", def.tapDays),
                    sizes = o.optJSONObject("sizes")?.let { j -> j.keys().asSequence().associateWith { j.optInt(it, 100) } } ?: def.sizes,
                )
            } catch (e: Exception) { def }
        }
    }
}

/** Dati extra che si possono mostrare nel widget (massimo 4). */
val WidgetExtras = listOf(
    "wind" to "Vento",
    "humidity" to "Umidità",
    "feels" to "Percepita",
    "uv" to "Indice UV",
    "sun" to "Alba e tramonto",
    "air" to "Qualità dell'aria",
    "pressure" to "Pressione",
)

/** Elementi di cui si può cambiare la grandezza. */
val WidgetSizeKeys = listOf(
    "current" to "Meteo attuale (icona e temperatura)",
    "extras" to "Dati extra",
    "hoursIcon" to "Prossime ore: icone",
    "hoursText" to "Prossime ore: testo",
    "rain" to "Grafico pioggia",
    "daysIcon" to "Prossimi giorni: icone",
    "daysText" to "Prossimi giorni: testo",
)

/** Azioni al tocco. */
val WidgetTaps = listOf("app" to "Apri l'app", "radar" to "Apri il radar", "refresh" to "Aggiorna")

object WidgetStore {
    private const val PREFS = "mlmeteo_widgets"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cfg(ctx: Context, id: Int): WidgetCfg {
        val p = sp(ctx)
        // i vecchi widget avevano solo la località
        val old = p.getString("w$id", null)
        return WidgetCfg.fromJson(p.getString("c$id", null), WidgetCfg(place = old ?: "main"))
    }

    fun save(ctx: Context, id: Int, c: WidgetCfg) {
        sp(ctx).edit().putString("c$id", c.toJson()).remove("w$id").apply()
    }

    fun remove(ctx: Context, id: Int) {
        sp(ctx).edit().remove("c$id").remove("w$id").remove("x$id").apply()
    }

    /** Widget rimosso dalla pagina Widget: non si aggiorna più (Android non permette all'app di toglierlo dalla Home). */
    fun markRemoved(ctx: Context, id: Int) { sp(ctx).edit().putBoolean("x$id", true).apply() }
    fun isRemoved(ctx: Context, id: Int) = sp(ctx).getBoolean("x$id", false)

    // ------------------------------------------------------------------ allerte e aria (per i widget)

    private fun cache(ctx: Context) = ctx.getSharedPreferences("mlmeteo_wcache", Context.MODE_PRIVATE)

    fun saveAlertLevel(ctx: Context, placeId: String, alerts: List<Alert>) {
        val now = java.time.ZonedDateTime.now()
        val lvl = alerts.filter { a -> (a.end == null || a.end.isAfter(now)) && (a.start == null || a.start.isBefore(now.plusHours(24))) }
            .maxOfOrNull { it.level } ?: 0
        cache(ctx).edit().putInt("al_$placeId", lvl).putLong("alt_$placeId", System.currentTimeMillis()).apply()
    }

    fun alertLevel(ctx: Context, placeId: String): Int {
        val c = cache(ctx)
        if (System.currentTimeMillis() - c.getLong("alt_$placeId", 0) > 12 * 3600_000L) return 0
        return c.getInt("al_$placeId", 0)
    }

    fun saveAir(ctx: Context, placeId: String, aqi: Double) {
        cache(ctx).edit().putFloat("aq_$placeId", aqi.toFloat()).putLong("aqt_$placeId", System.currentTimeMillis()).apply()
    }

    fun air(ctx: Context, placeId: String): Double {
        val c = cache(ctx)
        if (System.currentTimeMillis() - c.getLong("aqt_$placeId", 0) > 6 * 3600_000L) return Double.NaN
        return c.getFloat("aq_$placeId", Float.NaN).toDouble()
    }
}

/**
 * Dati del widget radar: ultima immagine della Protezione Civile e tessere della mappa
 * salvate su disco (il widget si disegna senza rete).
 */
object RadarWidgetData {
    /** Mappa: 1 dp del widget = 1 pixel allo zoom 8 (il widget più grande mostra una zona più ampia). */
    const val BASE_ZOOM = 8

    private fun frameFile(ctx: Context) = File(ctx.cacheDir, "wradar.bin")
    private fun tileFile(ctx: Context, z: Int, x: Int, y: Int) = File(ctx.filesDir, "wtiles/${z}_${x}_$y.png")

    class Cached(val time: Long, val q: ByteArray)

    @Volatile private var mem: Cached? = null

    fun frame(ctx: Context): Cached? {
        mem?.let { return it }
        val f = frameFile(ctx)
        if (!f.exists()) return null
        return try {
            val b = f.readBytes()
            val t = java.nio.ByteBuffer.wrap(b, 0, 8).long
            Cached(t, b.copyOfRange(8, b.size)).also { mem = it }
        } catch (e: Exception) { null }
    }

    fun tile(ctx: Context, z: Int, x: Int, y: Int): android.graphics.Bitmap? {
        val f = tileFile(ctx, z, x, y)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    fun tileZoom(scale: Float) = if (scale > 1.3f) BASE_ZOOM + 1 else BASE_ZOOM

    /** Tessere necessarie per un widget centrato su (lat, lon) grande wDp × hDp. */
    fun tilesFor(lat: Double, lon: Double, wDp: Int, hDp: Int, scale: Float): List<Triple<Int, Int, Int>> {
        val z = tileZoom(scale)
        val world = 256.0 * (1 shl z)
        val k = (1 shl (z - BASE_ZOOM)).toDouble()
        val cx = lonToX(lon) * world; val cy = latToY(lat) * world
        val x0 = ((cx - wDp * k / 2) / 256).toInt(); val x1 = ((cx + wDp * k / 2) / 256).toInt()
        val y0 = ((cy - hDp * k / 2) / 256).toInt(); val y1 = ((cy + hDp * k / 2) / 256).toInt()
        val n = 1 shl z
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> Triple(z, ((x % n) + n) % n, y.coerceIn(0, n - 1)) } }
    }

    fun radarIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WidgetRadar::class.java))

    /** Scarica ciò che manca (chiamato in background). */
    suspend fun prepare(ctx: Context, force: Boolean = false) {
        val ids = radarIds(ctx)
        if (ids.isEmpty()) return
        val m = AppWidgetManager.getInstance(ctx)
        val dens = ctx.resources.displayMetrics.density
        for (id in ids) {
            val place = Widgets.placeFor(ctx, id) ?: continue
            val (w, h) = Widgets.sizeDp(ctx, m, id, WidgetKind.RADAR)
            for ((z, x, y) in tilesFor(place.lat, place.lon, w, h, Widgets.bitmapScale(w, h, dens))) {
                val f = tileFile(ctx, z, x, y)
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600_000) continue
                val b = Net.getBytes("https://tile.openstreetmap.org/$z/$x/$y.png") ?: continue
                f.parentFile?.mkdirs()
                try { f.writeBytes(b) } catch (e: Exception) { }
            }
        }
        val old = frame(ctx)
        if (force || old == null || System.currentTimeMillis() - old.time > 5 * 60_000) {
            try {
                val fr = DpcRadar.latest()
                val out = java.nio.ByteBuffer.allocate(8 + fr.q.size).putLong(fr.time).put(fr.q).array()
                frameFile(ctx).writeBytes(out)
                mem = Cached(fr.time, fr.q)
            } catch (e: Exception) { }
        }
    }
}
