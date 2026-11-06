package com.mg.player.player.supervisor

import com.mg.player.player.engine.HealthSnapshot

/** Keeps a missing or throwing engine health API from silently terminating the player's watchdog loop. */
internal class EngineHealthSampler(private val log: (String) -> Unit) {
    private var degraded = false

    fun sample(
        readHealth: () -> HealthSnapshot?,
        readFallback: () -> HealthSnapshot?,
    ): HealthSnapshot? {
        val measured = runCatching(readHealth)
        measured.getOrNull()?.let { snapshot ->
            if (degraded) log("Wächter: Engine-Messwerte wieder verfügbar")
            degraded = false
            return snapshot
        }

        val fallback = runCatching(readFallback)
        if (!degraded) {
            val measurementFailure = measured.exceptionOrNull()
            val fallbackFailure = fallback.exceptionOrNull()
            val detail = when {
                measurementFailure != null -> "Engine-Messung fehlgeschlagen (${measurementFailure.javaClass.simpleName})"
                fallbackFailure != null -> "Engine-Messwerte fehlen; Status/Position nicht lesbar (${fallbackFailure.javaClass.simpleName})"
                else -> "Engine-Messwerte fehlen"
            }
            log("Wächter: $detail; überwache Status und Position als Rückfall")
            degraded = true
        }
        return fallback.getOrNull()
    }
}
