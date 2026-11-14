package com.mg.player.player.engine

import android.content.Context
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.log.AppLog
import org.videolan.libvlc.LibVLC
import java.util.concurrent.Executors

/**
 * App-weite LibVLC-Instanz (Modul-Cache bleibt warm, der nächste Film startet schneller). Wird keine Engine mehr
 * benutzt und wird der Speicher knapp, gibt [trim] sie frei (native, je nach Gerät mehrere 10 MB).
 */
internal object SharedLibVlc {
    @Volatile
    private var instance: LibVLC? = null
    private var users = 0

    @Synchronized
    fun acquire(context: Context): LibVLC {
        val vlc = instance ?: LibVLC(context.applicationContext, options(DeviceTier.of(context))).also { instance = it }
        users++
        return vlc
    }

    @Synchronized
    fun releaseUse() {
        if (users > 0) users--
    }

    /** Bei Speichermangel: freigeben, wenn gerade keine Engine sie benutzt. */
    @Synchronized
    fun trim(): Boolean {
        val vlc = instance ?: return false
        if (users > 0) return false
        instance = null
        Thread({ runCatching { vlc.release() } }, "libvlc-trim").apply { isDaemon = true }.start()
        return true
    }

    /**
     * Entblockungsfilter nur auf schwachen Geräten auslassen (spart dort Rechenzeit beim Software-Decoding); sonst
     * gab er auf allen Geräten Blockartefakte.
     */
    internal fun options(tier: DeviceTier): ArrayList<String> = arrayListOf(
        "--file-caching=300",
        "--http-reconnect",
        "--audio-time-stretch",
        // Keine Suche nach externen Untertitel-Dateien neben der Stream-Adresse.
        "--no-sub-autodetect-file",
        // Kein Log-Spam ins Systemprotokoll: kostet auf schwachen Boxen spürbar CPU.
        "--quiet",
        // Deutsche Tonspur bevorzugen wie bei ExoPlayer (Media3Build) – sonst nahm LibVLC die Standardspur der Datei.
        "--audio-language=de,deu,ger",
    ).apply { if (tier == DeviceTier.LOW) add("--avcodec-skiploopfilter=1") }
}

/**
 * Ein Hintergrund-Thread je Engine für blockierende LibVLC-Aufrufe; Aufträge laufen in Reihenfolge ab.
 * Bis 3.12.1 teilten sich alle Engines einen Thread: Hing stop() bei einem toten Stream, warteten alle späteren
 * Freigaben dahinter – Decoder und Anbieter-Verbindungen blieben belegt. Jetzt hängt höchstens die eine Engine,
 * und ein Wächter protokolliert das nach [WATCHDOG_MS].
 */
internal class VlcWorker(private val log: (String) -> Unit = { AppLog.log("Player", it) }) {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "libvlc-worker").apply { isDaemon = true } }

    fun run(block: () -> Unit) {
        try {
            executor.execute { runCatching(block) }
        } catch (ignored: java.util.concurrent.RejectedExecutionException) {
            // Engine schon freigegeben: nichts mehr ausführen.
        }
    }

    /** Letzter Auftrag (Freigabe); danach endet der Thread. Dauert er zu lange, steht es im Protokoll. */
    fun finish(block: () -> Unit) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        run {
            try {
                block()
            } finally {
                done.set(true)
            }
        }
        executor.shutdown()
        WATCHDOG.schedule({
            if (!done.get()) log("LibVLC hängt beim Beenden (> ${WATCHDOG_MS / 1000} s) – andere Wiedergaben laufen weiter")
        }, WATCHDOG_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Für Tests: wartet, bis alle Aufträge erledigt sind. */
    internal fun awaitIdle(timeoutMs: Long): Boolean = executor.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    companion object {
        const val WATCHDOG_MS = 3_000L
        private val WATCHDOG = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "libvlc-watchdog").apply { isDaemon = true } }
    }
}
