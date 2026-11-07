package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus

/** Datenschutzarme periodische Momentaufnahme: keine Stream-URL, Sendernamen oder Zugangsdaten. */
internal class HealthDiagnostics(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastLoggedAt = Long.MIN_VALUE
    private var lastLogged: HealthSnapshot? = null
    private var lastEngine: PlayerEngineType? = null

    fun sample(engine: PlayerEngineType, current: HealthSnapshot, now: Long): String? {
        resetForEngine(engine)
        if (lastLoggedAt != Long.MIN_VALUE && now - lastLoggedAt < intervalMs) return null
        val previous = lastLogged
        lastLoggedAt = now
        lastLogged = current
        return diagnosticLine(engine, current, previous)
    }

    private fun resetForEngine(engine: PlayerEngineType) {
        if (lastEngine == engine) return
        lastLoggedAt = Long.MIN_VALUE
        lastLogged = null
        lastEngine = engine
    }

    private fun diagnosticLine(engine: PlayerEngineType, current: HealthSnapshot, previous: HealthSnapshot?): String {
        val position = previous?.let { current.positionMs - it.positionMs }?.signedMs() ?: "Δ n/a"
        val frames = delta(current.renderedFrames, previous?.renderedFrames)
        val audio = delta(current.audioUnits, previous?.audioUnits)
        val bytes = delta(current.bytesLoaded, previous?.bytesLoaded)
        val pictureMonitoring = when {
            current.hasVideoTrack == false -> "keine Videospur"
            current.renderedFrames == null -> "Standbildprüfung eingeschränkt"
            else -> "Standbildprüfung aktiv"
        }
        val activity = activityOf(current)
        return "Player-Zustand $engine: ${current.status}/$activity; " +
            "Position ${current.positionMs} ms ($position); " +
            "Videobilder ${current.renderedFrames?.toString() ?: "n/a"} (${frames ?: "Δ n/a"}, $pictureMonitoring); " +
            "Tonblöcke ${current.audioUnits?.toString() ?: "n/a"} (${audio ?: "Δ n/a"}); " +
            "Bytes ${current.bytesLoaded?.toString() ?: "n/a"} (${bytes ?: "Δ n/a"}); " +
            "Videodecoder-Warnungen ${current.videoLagEvents?.toString() ?: "n/a"}"
    }

    private fun activityOf(current: HealthSnapshot): String = when {
        current.isPlaying -> "spielt"
        current.status == PlaybackStatus.BUFFERING -> "puffert"
        current.status == PlaybackStatus.READY -> "pausiert"
        else -> "steht"
    }

    fun reset() {
        lastLoggedAt = Long.MIN_VALUE
        lastLogged = null
        lastEngine = null
    }

    private fun delta(value: Long?, previous: Long?): String? =
        if (value == null || previous == null) null else (value - previous).signed()

    private fun Long.signed(): String = if (this >= 0) "+$this" else this.toString()
    private fun Long.signedMs(): String = "${signed()} ms"

    private companion object {
        const val DEFAULT_INTERVAL_MS = 30_000L
    }
}
