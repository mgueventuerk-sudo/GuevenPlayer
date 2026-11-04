package com.mg.player.core.log

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import com.mg.player.BuildConfig
import java.util.Locale
import kotlin.math.ceil

/**
 * Ruckel-Messung im Alltag (Baseline für „Janky Frames < 5 %“): Bildzeiten je Bildschirm über FrameMetrics – dieselbe
 * Quelle wie JankStats, ohne Zusatzbibliothek und ohne Daten nach außen. Beim Wechsel des Bildschirms und beim
 * Verlassen der App steht eine Zeile im Fehlerprotokoll, z. B.
 * „Bildzeiten live: 1 840 Bilder, 3,2 % verpasst (> 16 ms), 0,9 % ruckelig (> 33 ms), p50 9 ms, p90 14 ms, p99 41 ms“.
 */
object FrameStats {

    private val lock = Any()
    private var label = "start"
    private var counter: FrameCounter? = null
    private var thread: HandlerThread? = null

    private val listener = Window.OnFrameMetricsAvailableListener { window, metrics, _ ->
        // Das allererste Bild eines Fensters enthält Layout und Aufbau – kein Scroll-/Bedienruckler.
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        record(window, metrics.getMetric(FrameMetrics.TOTAL_DURATION))
    }

    /** In Activity.onCreate aufrufen; die Messung läuft auf einem eigenen Thread, nie auf dem Hauptthread. */
    fun attach(activity: Activity) {
        val handlerThread = synchronized(lock) { thread ?: HandlerThread("FrameStats").also { it.start(); thread = it } }
        // Ohne Hardware-Beschleunigung wirft Android hier; dann gibt es eben keine Messung.
        runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper)) }
    }

    /** Aktueller Bildschirm; ein Wechsel schreibt die Zeile des vorherigen. */
    fun screen(name: String) = synchronized(lock) {
        if (name == label) return@synchronized
        flushLocked()
        label = name
    }

    /** App geht in den Hintergrund: bisherige Werte schreiben. */
    fun flush() = synchronized(lock) { flushLocked() }

    private fun record(window: Window, durationNs: Long) = synchronized(lock) {
        val current = counter ?: FrameCounter(budgetNs(window)).also { counter = it }
        current.add(durationNs)
    }

    private fun flushLocked() {
        counter?.summary(label, BuildConfig.DEBUG)?.let { AppLog.log("Messung", it) }
        counter = null
    }

    /** Ein Bild darf 1/Bildfrequenz dauern (60 Hz: 16,7 ms; nach Bildfrequenz-Anpassung z. B. 50 Hz: 20 ms). */
    private fun budgetNs(window: Window): Long {
        val hz = runCatching { window.decorView.display?.refreshRate }.getOrNull()?.takeIf { it >= MIN_HZ } ?: DEFAULT_HZ
        return (NS_PER_S / hz).toLong()
    }

    private const val NS_PER_S = 1_000_000_000.0
    private const val DEFAULT_HZ = 60f
    private const val MIN_HZ = 20f
}

/** Zählt Bildzeiten eines Bildschirms (reine Logik, siehe FrameCounterTest). */
internal class FrameCounter(private val budgetNs: Long) {

    private val histogram = IntArray(MAX_MS + 1)
    var frames = 0
        private set
    var missed = 0
        private set
    var janky = 0
        private set
    var frozen = 0
        private set

    fun add(durationNs: Long) {
        frames++
        // Verpasst = mindestens ein Bildwechsel zu spät (wie „Janky frames“ in Perfetto/Macrobenchmark).
        if (durationNs > budgetNs) missed++
        // Ruckelig = mehr als doppelt so lang (Voreinstellung von JankStats) – das sieht man wirklich.
        if (durationNs > budgetNs * JANK_FACTOR) janky++
        if (durationNs > FROZEN_NS) frozen++
        histogram[(durationNs / NS_PER_MS).toInt().coerceIn(0, MAX_MS)]++
    }

    /** Perzentil in ganzen Millisekunden (Obergrenze [MAX_MS]); 0 ohne Bilder. */
    fun percentileMs(percent: Int): Int {
        if (frames == 0) return 0
        val rank = ceil(frames * percent / PERCENT).toInt().coerceAtLeast(1)
        var seen = 0
        histogram.forEachIndexed { ms, count ->
            seen += count
            if (seen >= rank) return ms
        }
        return MAX_MS
    }

    /** Protokollzeile; null bei zu wenigen Bildern (ein Standbild sagt nichts über Ruckeln). */
    fun summary(label: String, debugBuild: Boolean): String? {
        if (frames < MIN_FRAMES) return null
        val budgetMs = budgetNs / NS_PER_MS
        val build = if (debugBuild) " [Debug-Build: langsamer als Release]" else ""
        return "Bildzeiten $label: $frames Bilder, ${percent(missed)} % verpasst (> $budgetMs ms), " +
            "${percent(janky)} % ruckelig (> ${budgetNs * JANK_FACTOR / NS_PER_MS} ms), p50 ${percentileMs(P50)} ms, " +
            "p90 ${percentileMs(P90)} ms, p99 ${percentileMs(P99)} ms, $frozen eingefroren (> 700 ms)$build"
    }

    private fun percent(count: Int) = String.format(Locale.GERMANY, "%.1f", count * PERCENT / frames)

    companion object {
        const val MIN_FRAMES = 120
        private const val MAX_MS = 1_000
        private const val JANK_FACTOR = 2
        private const val NS_PER_MS = 1_000_000L
        private const val FROZEN_NS = 700 * NS_PER_MS
        private const val PERCENT = 100.0
        private const val P50 = 50
        private const val P90 = 90
        private const val P99 = 99
    }
}
