package com.marco.mlmeteo.desktop

import com.marco.mlmeteo.AppState
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Registro dei log: %APPDATA%\MLMeteo\log\mlmeteo.log (massimo 2 MB, poi passa a mlmeteo.1.log).
 * Registra avvio, aggiornamenti, ricerche, errori e crash.
 */
object Log {
    val dir: File get() = File(AppState.dir, "log").apply { mkdirs() }
    val file: File get() = File(dir, "mlmeteo.log")
    @Volatile var verbose = false
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Synchronized
    private fun write(level: String, msg: String) {
        try {
            val f = file
            if (f.length() > 2_000_000) {
                val old = File(dir, "mlmeteo.1.log")
                old.delete(); f.renameTo(old)
            }
            f.appendText("${LocalDateTime.now().format(fmt)} $level [${Thread.currentThread().name}] $msg\n")
        } catch (e: Exception) { }
    }

    fun i(msg: String) = write("INFO ", msg)
    fun d(msg: String) { if (verbose) write("DEBUG", msg) }
    fun w(msg: String) = write("WARN ", msg)
    fun e(msg: String, t: Throwable? = null) {
        val st = t?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() } ?: ""
        write("ERROR", if (st.isEmpty()) msg else "$msg\n$st")
    }

    fun start() {
        Thread.setDefaultUncaughtExceptionHandler { th, ex -> e("Crash nel thread ${th.name}", ex) }
        val rt = Runtime.getRuntime()
        i("Avvio MLMeteo ${System.getProperty("jpackage.app-version") ?: "sviluppo"} · Java ${System.getProperty("java.version")} · " +
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} · memoria max ${rt.maxMemory() / 1_000_000} MB")
    }

    fun tail(lines: Int = 200): String = try { file.readLines().takeLast(lines).joinToString("\n") } catch (e: Exception) { "" }

    fun memory(): String {
        val rt = Runtime.getRuntime()
        return "${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB usati su ${rt.maxMemory() / 1_000_000} MB"
    }
}
