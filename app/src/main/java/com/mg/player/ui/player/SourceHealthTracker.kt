package com.mg.player.ui.player

import com.mg.player.core.util.SourceHealth
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.ErrorCause
import com.mg.player.player.engine.ErrorKind
import com.mg.player.player.engine.PlaybackStatus

/**
 * Beobachtet die laufende Quelle und meldet jede Auffälligkeit sofort an [SourceHealth] (ab 3.50): Hänger ab 1 s
 * (Live), langsamer Start, Fehler, und eine Quelle, die verlassen wird, während sie hängt oder nicht startet.
 * Nicht gezählt: ohne Internet (liegt nicht an der Quelle), Zugang belegt/gedrosselt (liegt am Zugang), Puffern bei
 * Filmen (Spulen), und ein Wechsel von Hand, solange die Quelle einwandfrei läuft. Nur bei mehreren Quellen – sonst
 * gibt es nichts zu sortieren.
 */
internal class SourceHealthTracker(
    private val health: SourceHealth,
    private val online: () -> Boolean,
    private val clock: () -> Long,
) {
    private var sourceId: String? = null
    private var live = false
    private var preparedAt = 0L
    private var played = false
    private var failed = false
    private var bufferingSince = -1L
    private var playingSince = -1L
    private var goodRecorded = false

    /** Seit dem Start bzw. dem Beginn des laufenden Hängers war das Internet weg: Das liegt nicht an der Quelle. */
    private var sawOffline = false

    /** Start unterbrochen (App im Hintergrund, Engine gewechselt): ein langsamer oder fehlender Start zählt nicht. */
    private var startInterrupted = false

    /** Quelle wird (neu) gestartet – auch bei jedem Neu-Verbinden. */
    fun onPrepared(playable: Playable) {
        // Vorige Quelle noch offen: Neu-Verbinden derselben Quelle (Wächter, wegen eines Problems) zählt wie Verlassen;
        // beim Zappen zu einem anderen Sender zählt nur ein laufender Hänger, nicht der abgebrochene Start.
        sourceId?.let { previous -> onLeaving(startCounts = previous == playable.activeItemId) }
        sourceId = playable.activeItemId.takeIf { playable.sourceCount > 1 }
        live = playable.isLive
        preparedAt = clock()
        played = false
        failed = false
        bufferingSince = -1L
        playingSince = -1L
        goodRecorded = false
        sawOffline = !online()
        startInterrupted = false
    }

    /**
     * Wiedergabe unterbrochen, ohne dass es an der Quelle liegt (App im Hintergrund, anderer Player): Ein laufender
     * Hänger und ein noch nicht gelungener Start zählen nicht.
     */
    fun onInterrupted() {
        if (sourceId == null) return
        if (!played) startInterrupted = true
        bufferingSince = -1L
        playingSince = -1L
    }

    fun onState(state: EngineState) {
        val id = sourceId ?: return
        val now = clock()
        noteOffline()
        when {
            state.status == PlaybackStatus.ERROR -> {
                if (!failed && sourceFault(state)) record(id, SourceHealth.FAILED)
                failed = true
                bufferingSince = -1L
                playingSince = -1L
            }

            state.status == PlaybackStatus.READY && state.isPlaying -> onPlaying(id, now)

            state.status == PlaybackStatus.BUFFERING -> {
                if (played && live && bufferingSince < 0) {
                    bufferingSince = now
                    sawOffline = !online()
                }
                playingSince = -1L
            }

            // Pause, Ende: kein Hänger.
            else -> {
                bufferingSince = -1L
                playingSince = -1L
            }
        }
    }

    private fun onPlaying(id: String, now: Long) {
        if (!played) {
            played = true
            if (live && !startInterrupted && now - preparedAt >= SLOW_START_MS) record(id, SourceHealth.SHORT_STALL)
            sawOffline = false
        }
        if (bufferingSince >= 0) {
            val stalled = now - bufferingSince
            if (stalled >= LONG_STALL_MS) {
                record(id, SourceHealth.LONG_STALL)
            } else if (stalled >= MIN_STALL_MS) {
                record(id, SourceHealth.SHORT_STALL)
            }
            bufferingSince = -1L
            sawOffline = false
        }
        if (playingSince < 0) playingSince = now
    }

    /** Takt des Players: Läuft die Quelle lange störungsfrei, steigt sie wieder auf. */
    fun onTick() {
        val id = sourceId ?: return
        noteOffline()
        if (goodRecorded || playingSince < 0 || clock() - playingSince < STABLE_MS) return
        goodRecorded = true
        health.recordGood(id)
    }

    /** Es wird zu einer anderen Quelle gewechselt (Wächter oder von Hand). [startCounts]: ein nicht gelungener Start zählt. */
    fun onLeaving(startCounts: Boolean = true) {
        val id = sourceId ?: return
        val now = clock()
        when {
            failed -> Unit
            startCounts && startFailed(now) -> record(id, SourceHealth.FAILED)
            bufferingSince >= 0 && now - bufferingSince >= MIN_STALL_MS -> record(id, SourceHealth.LONG_STALL)
        }
        sourceId = null
    }

    /** Start kam nicht zustande, lange genug versucht und nicht unterbrochen (App im Hintergrund, anderer Player). */
    private fun startFailed(now: Long): Boolean = !played && !startInterrupted && now - preparedAt >= MIN_START_FAIL_MS

    /** Während des Starts oder eines Hängers ohne Internet: Was davon übrig bleibt, zählt nicht für die Quelle. */
    private fun noteOffline() {
        if ((!played || bufferingSince >= 0) && !online()) sawOffline = true
    }

    private fun record(id: String, points: Double) {
        if (online() && !sawOffline) health.record(id, points)
    }

    /**
     * Liegt der Fehler an der Quelle? Nicht bei belegtem/gesperrtem Zugang oder Drosselung (liegt am Zugang) und nicht
     * bei Decoder-Fehlern: Die hängen an Engine und Gerät – der Wächter wechselt dann den Player, und derselbe Stream
     * läuft oft einwandfrei.
     */
    private fun sourceFault(state: EngineState): Boolean =
        state.errorKind != ErrorKind.AUTH && state.errorCause != ErrorCause.RATE_LIMITED && state.errorCause != ErrorCause.DECODER

    companion object {
        const val MIN_STALL_MS = 1_000L
        const val LONG_STALL_MS = 3_000L
        const val SLOW_START_MS = 4_000L

        /** Kürzer als das ist ein Wechsel kein gescheiterter Start (Zappen, gleich weiter). */
        const val MIN_START_FAIL_MS = 1_500L
        const val STABLE_MS = 120_000L
    }
}
