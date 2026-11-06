package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType

/** Eine Gegenmaßnahme. Die Reihenfolge je Symptom steht in [Ladders]. */
sealed interface RecoveryStep {
    val label: String

    /** Gleiche Quelle neu laden ([delayMs] warten, Filme an der Position). */
    data class Reload(val delayMs: Long = 0) : RecoveryStep {
        override val label get() = if (delayMs > 0) "neu verbinden nach ${formatSeconds(delayMs)} s" else "neu verbinden"
    }

    data object SeekToLive : RecoveryStep {
        override val label = "zum Live-Rand springen"
    }

    data object RefreshVideo : RecoveryStep {
        override val label = "Videospur neu wählen"
    }

    data object RefreshAudio : RecoveryStep {
        override val label = "Tonspur neu wählen"
    }

    data object SwitchVariant : RecoveryStep {
        override val label = "andere Quelle"
    }

    /** Hardware-Decoder und Tunneling aus (Software-Decoder). */
    data object SafeDecoder : RecoveryStep {
        override val label = "sicherer Decoder"
    }

    /** Ton-Durchreichen (Passthrough) aus: Gerät dekodiert selbst. */
    data object SafeAudio : RecoveryStep {
        override val label = "Ton selbst dekodieren"
    }

    data class SwitchEngine(val target: PlayerEngineType) : RecoveryStep {
        override val label get() = "Player ${target.label}"
    }

    /** Nur Hinweis an den Nutzer, keine Änderung an der Wiedergabe. */
    data class Hint(val text: String) : RecoveryStep {
        override val label = "Hinweis"
    }

    data object GiveUp : RecoveryStep {
        override val label = "aufgeben"
    }
}

/**
 * Maßnahmen-Leitern: von sanft nach hart. Nicht anwendbare Stufen (keine Ersatzquelle, schon dieselbe Engine,
 * kein Live) überspringt der Wächter. Am Ende steht immer [RecoveryStep.GiveUp] mit klarer Meldung.
 */
object Ladders {
    private const val SHORT = 1_000L
    private const val MEDIUM = 2_000L
    private const val LONG = 4_000L
    private const val LONGER = 8_000L

    /** Beim schnellen Zappen zählt der Anbieter die alte Verbindung oft noch kurz mit. */
    private const val AUTH_FIRST = 1_500L
    private const val AUTH_SECOND = 5_000L
    private const val THROTTLED_FIRST = 5_000L
    private const val THROTTLED_SECOND = 15_000L

    private val LIBVLC = RecoveryStep.SwitchEngine(PlayerEngineType.LIBVLC)
    private val VARIANT = RecoveryStep.SwitchVariant
    private val RELOAD = RecoveryStep.Reload()

    private val BY_SYMPTOM: Map<Symptom, List<RecoveryStep>> = mapOf(
        Symptom.START_NO_DATA to listOf(VARIANT, RecoveryStep.Reload(SHORT), VARIANT, RecoveryStep.Reload(LONG)),
        Symptom.START_NO_PICTURE to listOf(VARIANT, RELOAD, RecoveryStep.SafeDecoder, LIBVLC),
        Symptom.STALL to listOf(VARIANT, RecoveryStep.SeekToLive, RELOAD, RecoveryStep.Reload(MEDIUM)),
        Symptom.FROZEN_PICTURE to listOf(RecoveryStep.RefreshVideo, RELOAD, RecoveryStep.SafeDecoder, LIBVLC),
        Symptom.AUDIO_WITHOUT_VIDEO to listOf(RecoveryStep.RefreshVideo, RecoveryStep.SafeDecoder, VARIANT, LIBVLC),
        Symptom.VIDEO_WITHOUT_AUDIO to listOf(RecoveryStep.SafeAudio, RecoveryStep.RefreshAudio, VARIANT, LIBVLC),
        Symptom.AV_DESYNC to listOf(RELOAD, RecoveryStep.Hint("Bild und Ton laufen auseinander – im Player-Menü den Ton-Versatz anpassen.")),
        Symptom.JUDDER to listOf(
            RecoveryStep.Hint("Das Bild ruckelt – das Gerät ist überlastet. Eine niedrigere Qualität oder ein anderer Player kann helfen."),
        ),
        Symptom.LIVE_DRIFT to listOf(RecoveryStep.SeekToLive, RELOAD),
        Symptom.LIVE_ENDED to listOf(RecoveryStep.Reload(SHORT), VARIANT, RecoveryStep.Reload(LONG)),
        Symptom.AUTH to listOf(RecoveryStep.Reload(AUTH_FIRST), RecoveryStep.Reload(AUTH_SECOND)),
        Symptom.NOT_FOUND to listOf(VARIANT),
        Symptom.RATE_LIMITED to listOf(RecoveryStep.Reload(THROTTLED_FIRST), VARIANT, RecoveryStep.Reload(THROTTLED_SECOND)),
        Symptom.NETWORK to listOf(RecoveryStep.Reload(SHORT), RecoveryStep.Reload(MEDIUM), VARIANT, RecoveryStep.Reload(LONG), RecoveryStep.Reload(LONGER)),
        Symptom.DECODER to listOf(RecoveryStep.SafeDecoder, VARIANT, LIBVLC),
        Symptom.FORMAT to listOf(VARIANT, LIBVLC),
        Symptom.DEVICE_PRESSURE to listOf(RecoveryStep.Hint("Das Gerät ist ausgelastet – Vorschau oder andere Apps schließen hilft.")),
        Symptom.RADIO_ONLY to listOf(RecoveryStep.Hint("Nur Ton – dieser Sender sendet kein Bild.")),
        Symptom.NO_AUDIO_TRACK to listOf(RecoveryStep.Hint("Dieser Sender sendet keinen Ton.")),
    )

    fun forSymptom(symptom: Symptom): List<RecoveryStep> = BY_SYMPTOM[symptom].orEmpty() + RecoveryStep.GiveUp

    /** Endgültige Meldung, wenn die Leiter ausgeschöpft ist. */
    fun failureMessage(symptom: Symptom, httpCode: Int?): String {
        val code = httpCode?.let { " (HTTP $it)" }.orEmpty()
        return when (symptom) {
            Symptom.AUTH -> "Zugriff verweigert$code – Abo abgelaufen, gesperrt oder zu viele gleichzeitige Verbindungen."

            Symptom.NOT_FOUND -> "Dieser Inhalt ist beim Anbieter nicht (mehr) verfügbar$code."

            Symptom.FORMAT, Symptom.DECODER ->
                "Dieses Format kann das Gerät nicht abspielen – im Player-Menü eine andere Engine oder Quelle wählen."

            Symptom.AUDIO_WITHOUT_VIDEO -> "Der Sender liefert Ton, aber kein Bild – im Player-Menü eine andere Quelle oder Engine wählen."

            Symptom.VIDEO_WITHOUT_AUDIO -> "Der Sender liefert kein Tonsignal – im Player-Menü eine andere Tonspur, Quelle oder Engine wählen."

            Symptom.FROZEN_PICTURE -> "Das Bild bleibt stehen – im Player-Menü eine andere Quelle oder Engine wählen."

            Symptom.RATE_LIMITED -> "Der Anbieter ist gerade überlastet$code. Bitte gleich noch einmal versuchen."

            else -> "Keine Verbindung zum Stream$code. Die Wiedergabe startet automatisch, sobald das Netzwerk wieder stabil ist."
        }
    }

    /** Nach dem Aufgeben beim nächsten Netzwechsel von selbst neu versuchen? */
    fun retryOnNetworkChange(symptom: Symptom): Boolean = symptom in setOf(
        Symptom.NETWORK,
        Symptom.START_NO_DATA,
        Symptom.STALL,
        Symptom.LIVE_ENDED,
        Symptom.RATE_LIMITED,
    )
}

private const val MS_PER_SECOND = 1_000.0

/** 1234 ms -> "1.2" (eine Nachkommastelle, fürs Protokoll). */
internal fun formatSeconds(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1f", ms / MS_PER_SECOND)
