package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.HealthSnapshot

/** Datenschutzarme periodische Momentaufnahme: keine Stream-URL, Sendernamen oder Zugangsdaten. */
internal class HealthDiagnostics(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastLoggedAt = Long.MIN_VALUE
    private var lastLogged: HealthSnapshot? = null

    fun sample(engine: PlayerEngineType, current: HealthSnapshot, now: Long): String? {
        if (lastLoggedAt != Long.MIN_VALUE && now - lastLoggedAt < intervalMs) return null
        val previous = lastLogged
        val positionDelta = previous?.let { current.positionMs - it.positionMs }
        val frameDelta = delta(current.renderedFrames, previous?.renderedFrames)
        val audioDelta = delta(current.audioUnits, previous?.audioUnits)
        val byteDelta = delta(current.bytesLoaded, previous?.bytesLoaded)
        lastLoggedAt = now
        lastLogged = current

        return "Player-Zustand $engine: ${current.status}/${if (current.isPlaying) "spielt" else "pausiert"}; " +
            "Position ${current.positionMs} ms (${positionDelta?.signedMs() ?: "Δ n/a"}); " +
            "Videobilder ${current.renderedFrames?.toString() ?: "n/a"} (${frameDelta ?: "Δ n/a"}); " +
            "Tonblöcke ${current.audioUnits?.toString() ?: "n/a"} (${audioDelta ?: "Δ n/a"}); " +
            "Bytes ${current.bytesLoaded?.toString() ?: "n/a"} (${byteDelta ?: "Δ n/a"})"
    }

    private fun delta(value: Long?, previous: Long?): String? =
        if (value == null || previous == null) null else (value - previous).signed()

    private fun Long.signed(): String = if (this >= 0) "+$this" else this.toString()
    private fun Long.signedMs(): String = "${signed()} ms"

    private companion object {
        const val DEFAULT_INTERVAL_MS = 30_000L
    }
}
