package com.mg.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.EpgProgram
import com.mg.player.player.engine.TrackOption
import kotlinx.coroutines.delay

/** Sleep-Timer im Player: Wiedergabe endet nach 30/60/90 Minuten oder mit der Sendung bzw. dem Film. */
@Stable
class SleepTimerState(private val clock: () -> Long = System::currentTimeMillis) {
    /** Zeitpunkt, zu dem die Wiedergabe endet; null = aus. */
    var endsAtMs by mutableStateOf<Long?>(null)
        private set

    /** Text für den Knopf im Player-Menü. */
    val buttonLabel: String get() = endsAtMs?.let { "Aus um ${TimeUtils.formatClock(it)}" } ?: "Sleep-Timer"

    fun options(nowProgram: EpgProgram?, position: PlaybackPosition, isLive: Boolean): List<TrackOption> {
        val now = clock()
        val end = endOfContent(nowProgram, position, isLive, now)
        val endLabel = when {
            end == null -> null
            isLive -> "Bis Sendungsende (${TimeUtils.formatClock(end)})"
            else -> "Bis zum Ende (${TimeUtils.formatClock(end)})"
        }
        return listOf(TrackOption(OFF, "Aus", endsAtMs == null)) +
            MINUTES.map { TrackOption(it.toString(), "$it Minuten", false) } +
            listOfNotNull(endLabel?.let { TrackOption(UNTIL_END, it, false) })
    }

    /** Übernimmt die Auswahl und liefert den Hinweis für den Nutzer. */
    fun select(option: TrackOption, nowProgram: EpgProgram?, position: PlaybackPosition, isLive: Boolean): String {
        val now = clock()
        endsAtMs = when (option.id) {
            OFF -> null
            UNTIL_END -> endOfContent(nowProgram, position, isLive, now)
            else -> option.id.toLongOrNull()?.let { now + it * 60_000L }
        }
        return endsAtMs?.let { "Wiedergabe endet um ${TimeUtils.formatClock(it)}" } ?: "Sleep-Timer aus"
    }

    fun cancel() {
        endsAtMs = null
    }

    private fun endOfContent(nowProgram: EpgProgram?, position: PlaybackPosition, isLive: Boolean, now: Long): Long? = when {
        isLive -> nowProgram?.endMs?.takeIf { it > now }
        position.durationMs > position.positionMs -> now + (position.durationMs - position.positionMs)
        else -> null
    }

    companion object {
        const val OFF = "off"
        const val UNTIL_END = "end"
        val MINUTES = listOf(30, 60, 90)
    }
}

/** Timer mit Ablauf-Aktion; läuft auch, solange der Player im Bild-im-Bild-Fenster weiterläuft. */
@Composable
fun rememberSleepTimer(onExpired: () -> Unit): SleepTimerState {
    val state = remember { SleepTimerState() }
    val expired by rememberUpdatedState(onExpired)
    val end = state.endsAtMs
    LaunchedEffect(end) {
        if (end == null) return@LaunchedEffect
        delay((end - System.currentTimeMillis()).coerceAtLeast(0))
        state.cancel()
        expired()
    }
    return state
}
