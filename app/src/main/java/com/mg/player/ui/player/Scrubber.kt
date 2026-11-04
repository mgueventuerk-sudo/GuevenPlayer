package com.mg.player.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Fokus-Schlüssel der Zeitleiste im Overlay. */
internal const val TIMELINE_KEY = "TIMELINE"

/**
 * Manuelles Spulen auf der Zeitleiste: Links/Rechts verschiebt nur eine Markierung, das Video läuft weiter.
 * Schnelles Tippen (innerhalb [TAP_WINDOW_MS]) oder Gedrückthalten vergrößert den Schritt entlang [STEPS], höchstens
 * auf 1/[MAX_STEP_DIVISOR] der Länge; eine Pause über [RESET_AFTER_MS] oder ein Richtungswechsel beginnt wieder bei 10 s.
 * [commit] liefert das Ziel genau einmal (ein einziger Sprung), [cancel] verwirft es.
 */
@Stable
class Scrubber(private val clock: () -> Long = SystemClock::uptimeMillis) {
    var targetMs by mutableStateOf<Long?>(null)
        private set
    var stepMs by mutableLongStateOf(STEPS.first())
        private set
    var startMs by mutableLongStateOf(0L)
        private set

    private var level = 0
    private var direction = 0
    private var lastPressMs = 0L
    private var heldRepeats = 0

    val active: Boolean get() = targetMs != null

    fun press(dir: Int, repeat: Boolean, positionMs: Long, durationMs: Long): Long? {
        if (durationMs <= 0 || dir == 0) return null
        val now = clock()
        val current = targetMs ?: positionMs.also {
            startMs = it
            direction = 0
        }
        val since = now - lastPressMs
        level = when {
            dir != direction -> 0
            repeat -> if (++heldRepeats % HOLD_REPEATS_PER_LEVEL == 0) level + 1 else level
            since <= TAP_WINDOW_MS -> level + 1
            since <= RESET_AFTER_MS -> level
            else -> 0
        }.coerceAtMost(STEPS.lastIndex)
        if (!repeat) heldRepeats = 0
        direction = dir
        lastPressMs = now
        stepMs = minOf(STEPS[level], maxOf(STEPS.first(), durationMs / MAX_STEP_DIVISOR))
        val next = (current + dir * stepMs).coerceIn(0L, maxOf(0L, durationMs - END_GUARD_MS))
        targetMs = next
        return next
    }

    fun commit(): Long? = targetMs.also { reset() }

    fun cancel() = reset()

    private fun reset() {
        targetMs = null
        level = 0
        direction = 0
        heldRepeats = 0
    }

    companion object {
        val STEPS = listOf(10_000L, 10_000L, 30_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
        const val TAP_WINDOW_MS = 700L
        const val RESET_AFTER_MS = 1_000L
        const val HOLD_REPEATS_PER_LEVEL = 8
        const val MAX_STEP_DIVISOR = 20
        const val END_GUARD_MS = 1_000L
    }
}
