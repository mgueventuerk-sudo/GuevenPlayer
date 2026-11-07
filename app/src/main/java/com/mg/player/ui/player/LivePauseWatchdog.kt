package com.mg.player.ui.player

import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PlaybackPauseReason
import com.mg.player.player.engine.PlaybackStatus

/** Erkennt eine unbeabsichtigte Live-Pause nach bereits laufender Wiedergabe. Zeiten kommen aus dem Player-Takt. */
internal class LivePauseWatchdog {
    sealed interface Decision {
        data class Observed(val reason: PlaybackPauseReason) : Decision
        data class Resume(val attempt: Int, val reason: PlaybackPauseReason) : Decision
        data class Unresolved(val reason: PlaybackPauseReason) : Decision
    }

    private var played = false
    private var pausedSince = -1L
    private var lastAttempt = -1L
    private var attempts = 0
    private var reportedUnresolved = false
    private var reportedIgnored = false

    fun resetSession() {
        played = false
        clearPause()
    }

    fun interrupt() = clearPause()

    @Suppress("ReturnCount") // Die Zustandsmaschine beendet jede nicht passende Phase sofort.
    fun check(state: EngineState, now: Long, eligible: Boolean): Decision? {
        if (!eligible) {
            clearPause()
            return null
        }
        if (state.isPlaying) {
            played = true
            clearPause()
            return null
        }
        if (!played || !watchable(state)) {
            clearPause()
            return null
        }
        if (isExternalPause(state.pauseReason)) {
            if (reportedIgnored) return null
            reportedIgnored = true
            return Decision.Observed(state.pauseReason)
        }
        if (pausedSince < 0) {
            pausedSince = now
            return Decision.Observed(state.pauseReason)
        }
        return recover(state.pauseReason, now)
    }

    private fun watchable(state: EngineState): Boolean =
        (state.status == PlaybackStatus.READY || state.status == PlaybackStatus.BUFFERING) && state.playWhenReady != true

    private fun isExternalPause(reason: PlaybackPauseReason): Boolean =
        reason == PlaybackPauseReason.AUDIO_BECOMING_NOISY || reason == PlaybackPauseReason.REMOTE

    private fun recover(reason: PlaybackPauseReason, now: Long): Decision? {
        val waitMs = if (reason == PlaybackPauseReason.AUDIO_FOCUS_LOSS) 8_000L else 3_000L
        if (now - pausedSince < waitMs || (lastAttempt >= 0 && now - lastAttempt < 5_000L)) return null
        if (attempts < 2) {
            attempts++
            lastAttempt = now
            return Decision.Resume(attempts, reason)
        }
        if (reportedUnresolved) return null
        reportedUnresolved = true
        return Decision.Unresolved(reason)
    }

    private fun clearPause() {
        pausedSince = -1L
        lastAttempt = -1L
        attempts = 0
        reportedUnresolved = false
        reportedIgnored = false
    }
}
