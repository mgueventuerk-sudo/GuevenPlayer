package com.mg.player.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.os.SystemClock

/**
 * Liest den Speicherzustand des Geräts und liefert daraus das aktuelle Vorlade-Budget ([PreloadPolicyRules]).
 * Die Messung ist ein einzelner Binder-Aufruf; sie läuft je Vorladen einmal, nicht je Bild.
 */
object PreloadPolicy {
    /** Aus den Einstellungen („Vorladen“); PlayerApp hält den Wert aktuell. */
    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var trimmedAtMs = NEVER

    /** Android meldet wenig Speicher (PlayerApp.onTrimMemory): Eine Weile nichts vorladen. */
    fun onTrim() {
        trimmedAtMs = SystemClock.elapsedRealtime()
    }

    fun current(context: Context): PreloadBudget {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return PreloadBudget.OFF
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val reading = MemoryReading(
            availMb = info.availMem / MB,
            thresholdMb = info.threshold / MB,
            lowMemory = info.lowMemory,
            heapFreeMb = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / MB,
            trimmedRecently = trimmedAtMs != NEVER && SystemClock.elapsedRealtime() - trimmedAtMs < TRIM_QUIET_MS,
            storageFreeMb = storageFreeMb(context.cacheDir),
        )
        return PreloadPolicyRules.decide(DeviceTier.of(context), reading, enabled).also { lastReading = reading }
    }

    /**
     * Freier Platz im internen Speicher dort, wo der Bild-Cache liegt (MB). Liest vom Dateisystem – nicht im Hauptthread
     * aufrufen. Nicht lesbar: ein vorsichtiger Mittelwert statt „unendlich“.
     */
    fun storageFreeMb(dir: java.io.File): Long =
        runCatching { StatFs(dir.path).availableBytes / MB }.getOrDefault(UNKNOWN_STORAGE_MB)

    /** Letzte Messung – fürs Protokoll. */
    @Volatile
    var lastReading: MemoryReading? = null
        private set

    private const val NEVER = Long.MIN_VALUE
    private const val UNKNOWN_STORAGE_MB = 1_024L
    private const val MB = 1024L * 1024L
    private const val TRIM_QUIET_MS = 2L * 60_000L
}
