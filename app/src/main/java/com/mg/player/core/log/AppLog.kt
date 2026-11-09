package com.mg.player.core.log

import android.content.Context
import android.os.Build
import android.util.Log
import com.mg.player.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Kleines Protokoll in einer Datei: Wiedergabe- und Importfehler sowie Abstürze.
 * Es lässt sich in den Einstellungen anzeigen und per QR-Code aufs Handy holen.
 */
object AppLog {

    private const val MAX_BYTES = 256 * 1024
    private const val MINUTE_MS = 60_000L
    private val writer = Executors.newSingleThreadExecutor()

    /** Nur auf dem Schreib-Thread benutzt: SimpleDateFormat ist nicht threadsicher (verdrehte Zeitstempel). */
    private val timeFormat = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)

    @Volatile
    private var file: File? = null

    /** Merker "beim letzten Mal abgestürzt" – beim nächsten Start wird ein Bericht angeboten. */
    @Volatile
    private var crashMarker: File? = null

    fun init(context: Context) {
        val target = File(context.filesDir, "logs/app.log")
        // mkdirs() ist ein Datentraegerzugriff und lief bisher im Hauptthread in Application.onCreate. Der Schreiber
        // ist ein Single-Thread-Executor, seine Reihenfolge ist also garantiert: Das Verzeichnis existiert, bevor der
        // erste append() laeuft.
        writer.execute { target.parentFile?.mkdirs() }
        file = target
        crashMarker = File(context.filesDir, "logs/last_crash.txt")
        installCrashHandler()
        log("App", "Start · Version ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    /**
     * „Ohne Verlauf“: keine Aktivitäten (gespielte Sender, Importe …) protokollieren. Fehler, Abstürze und
     * VPN-Abbrüche werden weiter erfasst – sie sind für die Sicherheit und die Fehlersuche nötig.
     */
    @Volatile
    var quiet: Boolean = false

    fun log(tag: String, message: String) {
        if (quiet && tag != "VPN") return
        write(tag, message)
    }

    /** Nur im Debug-Build ins Systemprotokoll (z. B. erwartbare Fehler einzelner Abfragen); im Release nichts. */
    fun debug(tag: String, message: String) {
        if (com.mg.player.BuildConfig.DEBUG) Log.d("GuevenPlayer/$tag", LogRedactor.redact(message))
    }

    /** Schon beim Schreiben schwärzen: Fehlertexte enthalten oft Adressen mit Zugangsdaten (Audit A-09). */
    private fun write(tag: String, raw: String) {
        val message = LogRedactor.redact(raw)
        Log.i("GuevenPlayer/$tag", message)
        val target = file ?: return
        val atMs = System.currentTimeMillis()
        val repeats = Repeats.countFor(tag, message, atMs) ?: return
        val suffix = if (repeats > 0) " (${repeats + 1}× in ${Repeats.WINDOW_MS / MINUTE_MS} min)" else ""
        writer.execute { append(target, "${timeFormat.format(Date(atMs))}  [$tag]  $message$suffix\n") }
    }

    /**
     * Gleiche Meldung in Folge nur einmal je Zeitfenster. Im Fire-TV-Protokoll vom 27.10.–02.11. waren 1 410 von
     * 2 415 Zeilen dieselben VPN-Meldungen und 404 derselbe Sport-Satz – die eigentlichen Hinweise (Abstürze,
     * StrictMode) fielen dadurch aus dem begrenzten Protokoll heraus.
     */
    internal object Repeats {
        const val WINDOW_MS = 10 * MINUTE_MS
        private const val MAX_KEYS = 200
        private val lastAt = HashMap<String, Long>()
        private val skipped = HashMap<String, Int>()

        @Synchronized
        fun reset() {
            lastAt.clear()
            skipped.clear()
        }

        /** null = unterdrücken; sonst die Zahl der seither unterdrückten Wiederholungen. */
        @Synchronized
        fun countFor(tag: String, message: String, atMs: Long): Int? {
            val key = "$tag|$message"
            val last = lastAt[key]
            if (last != null && atMs - last < WINDOW_MS) {
                skipped[key] = (skipped[key] ?: 0) + 1
                return null
            }
            if (lastAt.size >= MAX_KEYS) {
                lastAt.clear()
                skipped.clear()
            }
            lastAt[key] = atMs
            return skipped.remove(key) ?: 0
        }
    }

    fun error(tag: String, message: String, throwable: Throwable) {
        write(tag, "$message: ${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText().orEmpty()

    /** Zum Anzeigen und Weitergeben: Zugangsdaten, Schlüssel und E-Mail-Adressen geschwärzt. */
    fun readRedacted(): String = LogRedactor.redact(read())

    /** Absturz beim letzten Lauf (Fehler und Stelle), oder null. Wird dabei gelöscht – der Hinweis kommt nur einmal. */
    fun takeLastCrash(): String? {
        val marker = crashMarker ?: return null
        if (!marker.exists()) return null
        return runCatching { marker.readText() }.getOrNull().also { marker.delete() }?.takeIf { it.isNotBlank() }
    }

    /** Bericht zum Weiterschicken: Gerät, Version und das Ende des Protokolls (mit dem Absturz). */
    fun report(): String {
        val log = read()
        return "GuevenPlayer ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}\n\n" + LogRedactor.redact(log.takeLast(REPORT_CHARS))
    }

    private const val REPORT_CHARS = 30_000

    /** Wartet, bis alles geschrieben ist (nur für Tests und den Protokoll-Export). */
    fun awaitWritten() {
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun clear() {
        Repeats.reset()
        val target = file ?: return
        writer.execute { target.writeText("") }
    }

    private fun append(target: File, line: String) {
        try {
            // Das Verzeichnis legt init() im Schreib-Thread an. Der Absturzbehandler schreibt aber synchron auf dem
            // abstuerzenden Thread - trifft ihn ein Absturz in den ersten Millisekunden, war der Ordner sonst noch
            // nicht da und der Bericht ging verloren. mkdirs() ist billig und tut nichts, wenn es ihn schon gibt.
            if (!target.exists()) target.parentFile?.mkdirs()
            if (target.exists() && target.length() > MAX_BYTES) {
                // Ältere Hälfte verwerfen, damit die Datei klein bleibt.
                val text = target.readText()
                target.writeText(text.substring(text.length / 2))
            }
            target.appendText(line)
        } catch (e: Exception) {
            Log.w("AppLog", "Protokoll nicht schreibbar", e)
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val target = file
            if (target != null) {
                val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
                // Synchron schreiben – der Prozess endet gleich.
                // Eigener Formatierer: Der Schreib-Thread könnte gerade den gemeinsamen benutzen.
                val stamp = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
                append(target, "$stamp  [ABSTURZ]  Thread ${thread.name}\n$trace\n")
                runCatching { crashMarker?.writeText("$stamp · ${throwable.javaClass.simpleName}: ${throwable.message}\n$trace") }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
