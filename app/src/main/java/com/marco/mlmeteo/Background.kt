package com.marco.mlmeteo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Pianificazione dei lavori in background. */
object Background {
    private const val PERIODIC = "mlmeteo-update"
    private const val MORNING = "mlmeteo-morning"

    fun schedule(ctx: Context) {
        val s = Prefs.loadSettings(ctx)
        val wm = WorkManager.getInstance(ctx)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(s.updateFreq.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        scheduleMorning(ctx, s)
    }

    fun scheduleMorning(ctx: Context, s: Settings = Prefs.loadSettings(ctx)) {
        val wm = WorkManager.getInstance(ctx)
        if (!s.notifMorning) { wm.cancelUniqueWork(MORNING); return }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(s.morningMinutes / 60, s.morningMinutes % 60))
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val req = OneTimeWorkRequestBuilder<MorningWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork(MORNING, ExistingWorkPolicy.REPLACE, req)
    }
}

/** Ogni 30–180 minuti: aggiorna previsioni, allerte, widget e notifiche. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = Prefs.loadSettings(ctx)
        // posizione aggiornata anche ad app chiusa (serve il permesso "Consenti sempre")
        if (s.useGps && Locator.hasBackground(ctx)) try { Locator.update(ctx) } catch (e: Exception) { }
        val places = (listOfNotNull(Prefs.loadGps(ctx).takeIf { s.useGps }) + Prefs.loadPlaces(ctx))
        val main = places.firstOrNull { it.id == s.mainPlaceId } ?: places.firstOrNull()
        if (main == null) { Widgets.refreshDone(ctx); Widgets.updateAll(ctx); return Result.success() }
        val wanted = (Widgets.placeIds(ctx) + main.id).toSet()
        val source = Sources.byId(s.sourceId)
        for (p in places.filter { it.id in wanted }) {
            try {
                val f = WeatherRepo.forecast(p, source)
                ForecastStore.save(ctx, p.id, f)
                if (p.id == main.id) Notify.statusBar(ctx, p, f, s)
            } catch (e: Exception) { }
        }
        Widgets.refreshDone(ctx)
        Widgets.updateAll(ctx)
        if (s.notifAlerts) try { Notify.alerts(ctx, main, AlertsRepo.forPlace(main, force = true), s) } catch (e: Exception) { }
        if (s.notifRain) try { Notify.rainSoon(ctx, main, s) } catch (e: Exception) { }
        return Result.success()
    }
}

/** Previsione del mattino all'orario scelto (poi si riprogramma per il giorno dopo). */
class MorningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = Prefs.loadSettings(ctx)
        try {
            val places = (listOfNotNull(Prefs.loadGps(ctx).takeIf { s.useGps }) + Prefs.loadPlaces(ctx))
            val main = places.firstOrNull { it.id == s.mainPlaceId } ?: places.firstOrNull()
            if (main != null && s.notifMorning) {
                val f = try { WeatherRepo.forecast(main, Sources.byId(s.sourceId)).also { ForecastStore.save(ctx, main.id, it) } }
                    catch (e: Exception) { ForecastStore.load(ctx, main.id) }
                if (f != null) Notify.morning(ctx, main, f, s)
            }
        } finally {
            Background.scheduleMorning(ctx, s)
        }
        return Result.success()
    }
}

object Notify {
    private const val CH_ALERTS = "allerte"
    private const val CH_RAIN = "pioggia"
    private const val CH_MORNING = "mattino"
    private const val CH_STATUS = "temperatura"
    private const val ID_STATUS = 1
    private const val ID_RAIN = 2
    private const val ID_MORNING = 3

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Allerte meteo", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_RAIN, "Pioggia in arrivo", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_MORNING, "Previsione del mattino", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Temperatura nella barra di stato", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun quiet(s: Settings): Boolean {
        if (!s.quietNight) return false
        val h = LocalTime.now().hour
        return h >= 22 || h < 7
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: android.app.Notification) {
        if (!canPost(ctx)) return
        createChannels(ctx)
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) { }
    }

    /** Notifica le allerte nuove (una volta sola per allerta). */
    fun alerts(ctx: Context, p: Place, list: List<Alert>, s: Settings) {
        val sp = ctx.getSharedPreferences("mlmeteo_notif", Context.MODE_PRIVATE)
        val sent = sp.getStringSet("alerts", emptySet())!!.toMutableSet()
        for (a in list.filter { it.level >= s.alertMinLevel && it.id !in sent }) {
            if (quiet(s) && a.level < 3) continue
            val color = when (a.level) { 3 -> 0xFFE53935.toInt(); 2 -> 0xFFFB8C00.toInt(); else -> 0xFFFDD835.toInt() }
            val whenTxt = a.start?.let { "dal " + it.format(DateTimeFormatter.ofPattern("EEE d HH:mm", Locale.ITALY)) } ?: ""
            val n = NotificationCompat.Builder(ctx, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(color)
                .setContentTitle("${a.title} · ${p.name}")
                .setContentText("${a.area} $whenTxt · ${a.source}")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${a.area} $whenTxt\n${a.detail}\nFonte: ${a.source}"))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            post(ctx, 1000 + (a.id.hashCode() and 0xFFFF), n)
            sent += a.id
        }
        sp.edit().putStringSet("alerts", sent.toList().takeLast(200).toSet()).apply()
    }

    /** "Pioggia tra X minuti" con i dati ogni 15 minuti; al massimo una volta ogni 3 ore. */
    suspend fun rainSoon(ctx: Context, p: Place, s: Settings) {
        if (quiet(s)) return
        val sp = ctx.getSharedPreferences("mlmeteo_notif", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("rainAt", 0) < 3 * 3600_000L) return
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${String.format(Locale.US, "%.4f", p.lat)}" +
            "&longitude=${String.format(Locale.US, "%.4f", p.lon)}&minutely_15=precipitation&forecast_minutely_15=8&timezone=auto"
        val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Net.get(url) }
        val m = JSONObject(body).getJSONObject("minutely_15")
        val times = m.getJSONArray("time")
        val pr = m.getJSONArray("precipitation")
        val now = LocalDateTime.now()
        var rainingNow = false
        var startMin = -1
        var amount = 0.0
        for (i in 0 until times.length()) {
            val t = LocalDateTime.parse(times.getString(i))
            val v = if (pr.isNull(i)) 0.0 else pr.getDouble(i)
            val minutes = Duration.between(now, t).toMinutes()
            if (minutes < -15) continue
            if (minutes <= 0 && v >= 0.1) rainingNow = true
            if (minutes in 1..75 && v >= 0.2 && startMin < 0) startMin = minutes.toInt()
            if (minutes in 0..90) amount += v
        }
        if (rainingNow || startMin < 0) return
        val rounded = ((startMin + 4) / 5 * 5).coerceAtLeast(5)
        val n = NotificationCompat.Builder(ctx, CH_RAIN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Pioggia tra circa $rounded minuti")
            .setContentText("${p.name} · circa ${String.format(Locale.ITALY, "%.1f", amount)} mm nella prossima ora e mezza")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        post(ctx, ID_RAIN, n)
        sp.edit().putLong("rainAt", System.currentTimeMillis()).apply()
    }

    fun morning(ctx: Context, p: Place, f: Forecast, s: Settings) {
        val fmt = Fmt(s)
        val today = f.days.firstOrNull() ?: return
        val rain = fmt.rainShort(today.precipProb, today.precipSum)
        val n = NotificationCompat.Builder(ctx, CH_MORNING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Buongiorno! Oggi a ${p.name}: ${conditionText(today.code)}")
            .setContentText("Max ${fmt.temp(today.tMax)} · Min ${fmt.temp(today.tMin)}" + if (rain.isNotBlank()) " · Pioggia $rain" else "")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        post(ctx, ID_MORNING, n)
    }

    /** Notifica fissa con la temperatura come icona nella barra di stato. */
    fun statusBar(ctx: Context, p: Place, f: Forecast, s: Settings = Prefs.loadSettings(ctx)) {
        if (!s.notifStatus) { cancelStatus(ctx); return }
        val fmt = Fmt(s)
        val t = fmt.tempValue(f.current.temp).let { if (it.isNaN()) 0 else it.roundToInt() }
        val today = f.days.firstOrNull()
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(tempIcon(t))
            .setContentTitle("${fmt.temp(f.current.temp)} · ${conditionText(f.current.code, f.current.isDay)}")
            .setContentText("${p.name} · Max ${fmt.temp(today?.tMax ?: Double.NaN)} Min ${fmt.temp(today?.tMin ?: Double.NaN)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        post(ctx, ID_STATUS, n)
    }

    fun cancelStatus(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_STATUS)

    private fun tempIcon(t: Int): IconCompat {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val text = "$t°"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (text.length <= 3) 72f else 56f
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2
        c.drawText(text, size / 2f, y, paint)
        return IconCompat.createWithBitmap(bmp)
    }
}


/** Posizione GPS letta in background (per widget e notifiche). */
object Locator {
    fun hasBackground(ctx: Context): Boolean {
        val fg = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fg) return false
        return Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /** Legge la posizione e, se ti sei spostato, aggiorna la località GPS salvata. */
    suspend fun update(ctx: Context) {
        val loc = kotlinx.coroutines.withTimeoutOrNull(25_000) { AppState.currentLocation(ctx) } ?: return
        val old = Prefs.loadGps(ctx)
        val probe = Place("gps", "", "", loc.latitude, loc.longitude, gps = true)
        if (old != null && roughKm(old, probe) < 1.0) return
        val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppState.cityName(loc.latitude, loc.longitude, ctx) ?: reverseName(loc.latitude, loc.longitude)
        }
        val p = Place(
            "gps", info?.first ?: old?.name ?: "Posizione attuale",
            info?.second?.takeIf { it.isNotBlank() } ?: old?.region ?: "Posizione attuale",
            loc.latitude, loc.longitude, gps = true, cc = info?.third?.takeIf { it.isNotBlank() } ?: old?.cc ?: "",
        )
        Prefs.saveGps(ctx, p)
    }
}
