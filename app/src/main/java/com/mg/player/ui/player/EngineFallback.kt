package com.mg.player.ui.player

import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState

/**
 * Filme und Serien starten mit ExoPlayer (am schnellsten). Meldet ExoPlayer ein Format, das es auf diesem
 * Gerät nicht abspielen kann (z. B. DTS-Ton, exotische AVI/MKV), übernimmt einmalig LibVLC an derselben Stelle.
 * Im Live-TV erst als letzte Möglichkeit, wenn keine andere Stream-Variante mehr übrig ist – beim nächsten
 * Senderwechsel geht es wieder mit der eingestellten Engine weiter.
 */
class EngineFallback {
    private var used = false

    /** Bei neuem Inhalt (z. B. nächste Folge) darf erneut gewechselt werden. */
    fun reset() {
        used = false
    }

    /** true = jetzt auf LibVLC wechseln. */
    fun shouldSwitch(state: EngineState, playable: Playable?, engine: PlayerEngineType): Boolean {
        if (used || !state.formatUnsupported || engine != PlayerEngineType.MEDIA3) return false
        if (playable == null) return false
        if (!canSwitchTo(TARGET, playable)) return false
        used = true
        return true
    }

    companion object {
        val TARGET = PlayerEngineType.LIBVLC

        /** Engine laut Einstellung bzw. Auswahl; eigene Aufnahmen kann ein externer Player nicht öffnen. */
        fun initialEngine(request: PlayRequest, playable: Playable, settings: AppSettings): PlayerEngineType {
            val liveLike = playable.kind == PlayableKind.LIVE || playable.kind == PlayableKind.CATCHUP
            val preferred = request.engine ?: if (liveLike) settings.liveEngine else settings.vodEngine
            val recordingCannotUseExternal = playable.kind == PlayableKind.RECORDING && preferred == PlayerEngineType.EXTERNAL
            return engineFor(preferred, playable).let { if (recordingCannotUseExternal) PlayerEngineType.MEDIA3 else it }
        }

        /** HTTPS-Streams brauchen Media3, damit auch HLS-Untersegmente auf HTTP geprüft werden. */
        fun engineFor(preferred: PlayerEngineType, playable: Playable, preview: Boolean = false): PlayerEngineType {
            val httpsNeedsGate = playable.url.startsWith("https://", ignoreCase = true) && !playable.allowInsecureHttp
            val secured = if (httpsNeedsGate) PlayerEngineType.MEDIA3 else preferred
            return if (preview && secured == PlayerEngineType.EXTERNAL) PlayerEngineType.MEDIA3 else secured
        }

        fun canSwitchTo(type: PlayerEngineType, playable: Playable): Boolean = engineFor(type, playable) == type
        const val MESSAGE = "Format wird von ExoPlayer nicht unterstützt – wechsle automatisch zu LibVLC …"
    }
}
