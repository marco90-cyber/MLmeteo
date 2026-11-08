package com.marco.mlmeteo

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Registro dei log (come nella versione Windows): file mlmeteo.log nella memoria dell'app,
 * massimo 1 MB poi passa a mlmeteo.1.log. Registra avvio, aggiornamenti, errori e crash.
 * Si vede e si condivide da Impostazioni → Informazioni → Registro.
 */
object AppLog {
    private var dir: File? = null
    @Volatile var verbose = false
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var started = false

    /** Da chiamare all'avvio dell'app e dei lavori in background (più volte non fa danni). */
    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.applicationContext.filesDir, "log").apply { mkdirs() }
        verbose = ctx.getSharedPreferences("mllog", Context.MODE_PRIVATE).getBoolean("verbose", false)
        if (!started) {
            started = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { th, ex ->
                e("Crash nel thread ${th.name}", ex)
                prev?.uncaughtException(th, ex)
            }
            val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (x: Exception) { "?" }
            i("Avvio MLMeteo $ver · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    fun setVerbose(ctx: Context, on: Boolean) {
        verbose = on
        ctx.getSharedPreferences("mllog", Context.MODE_PRIVATE).edit().putBoolean("verbose", on).apply()
        i("Log dettagliato ${if (on) "attivato" else "disattivato"}")
    }

    val file: File? get() = dir?.let { File(it, "mlmeteo.log") }

    @Synchronized
    private fun write(level: String, msg: String) {
        val d = dir ?: return
        try {
            val f = File(d, "mlmeteo.log")
            if (f.length() > 1_000_000) {
                val old = File(d, "mlmeteo.1.log")
                old.delete(); f.renameTo(old)
            }
            f.appendText("${LocalDateTime.now().format(fmt)} $level [${Thread.currentThread().name}] $msg\n")
        } catch (x: Exception) { }
    }

    fun i(msg: String) = write("INFO ", msg)
    fun d(msg: String) { if (verbose) write("DEBUG", msg) }
    fun w(msg: String) = write("WARN ", msg)
    fun e(msg: String, t: Throwable? = null) {
        val st = t?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() } ?: ""
        write("ERROR", if (st.isEmpty()) msg else "$msg\n$st")
    }

    fun tail(lines: Int = 300): String = try { file?.readLines()?.takeLast(lines)?.joinToString("\n") ?: "" } catch (x: Exception) { "" }

    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
        i("Registro svuotato")
    }
}
