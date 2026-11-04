package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.ErrorCause
import com.mg.player.player.engine.ErrorKind

/** Führt eine Maßnahme aus. false = hier nicht möglich (der Wächter nimmt dann die nächste Stufe). */
fun interface SupervisorActions {
    /** [symptom]: weshalb – z. B. um eine Ersatzquelle nur bei echten Quellenfehlern dauerhaft zu bevorzugen. */
    fun execute(step: RecoveryStep, symptom: Symptom): Boolean
}

/** Was der Wächter gerade tut – für Statuszeile, Spinner und Fehlermeldung. */
data class SupervisorStatus(
    val reconnecting: Boolean = false,
    val waitingForNetwork: Boolean = false,
    /** Kurzer Text, was gerade passiert (z. B. „Bild ohne Ton – Ton selbst dekodieren (2/6)“). */
    val text: String? = null,
    /** Endgültige Meldung nach dem Aufgeben. */
    val failure: String? = null,
    val failedSymptom: Symptom? = null,
)

/** Reine Entscheidungsregeln des Wächters (ohne Zustand). */
internal object SupervisorRules {
    fun symptomOf(state: EngineState): Symptom = when (state.errorKind) {
        ErrorKind.AUTH -> Symptom.AUTH

        ErrorKind.NOT_FOUND -> Symptom.NOT_FOUND

        ErrorKind.FORMAT -> if (state.errorCause == ErrorCause.DECODER) Symptom.DECODER else Symptom.FORMAT

        ErrorKind.NETWORK, ErrorKind.OTHER, ErrorKind.NONE ->
            if (state.errorCause == ErrorCause.RATE_LIMITED) Symptom.RATE_LIMITED else Symptom.NETWORK
    }

    /** Ist die Stufe für diesen Inhalt überhaupt möglich? [activeFix]: schon angewendete Einstellungen. */
    fun applicable(step: RecoveryStep, current: SessionInfo, activeFix: StartHint): Boolean = when (step) {
        RecoveryStep.SwitchVariant -> current.canSwitchVariant
        RecoveryStep.SeekToLive -> current.isLive
        RecoveryStep.SafeDecoder -> !activeFix.safeDecoder && current.engine == PlayerEngineType.MEDIA3
        RecoveryStep.SafeAudio -> !activeFix.safeAudio && current.engine == PlayerEngineType.MEDIA3
        RecoveryStep.RefreshVideo, RecoveryStep.RefreshAudio -> current.engine == PlayerEngineType.MEDIA3
        is RecoveryStep.SwitchEngine -> current.engine != step.target && current.engine != PlayerEngineType.EXTERNAL
        else -> true
    }

    fun httpSuffix(httpCode: Int?): String = httpCode?.let { " (HTTP $it)" }.orEmpty()

    fun isNetworkSymptom(symptom: Symptom) = symptom == Symptom.NETWORK || symptom == Symptom.START_NO_DATA || symptom == Symptom.STALL

    fun describe(state: EngineState): String = buildString {
        append(state.errorKind.name)
        if (state.errorCause != ErrorCause.NONE) append('/').append(state.errorCause.name)
        state.httpCode?.let { append(" HTTP ").append(it) }
    }
}

/** Gleitendes Minutenfenster: höchstens [maxPerMinute] Maßnahmen je Minute (auch Neuverbindungen nach Netzwechsel). */
internal class MinuteBudget(private val maxPerMinute: Int) {
    private val times = ArrayDeque<Long>()

    fun record(now: Long) = times.addLast(now)

    fun clear() = times.clear()

    /** Wartezeit, bis wieder eine Maßnahme erlaubt ist. */
    fun waitMs(now: Long): Long {
        while (times.isNotEmpty() && now - times.first() >= MINUTE) times.removeFirst()
        return if (times.size >= maxPerMinute) MINUTE - (now - times.first()) else 0L
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
