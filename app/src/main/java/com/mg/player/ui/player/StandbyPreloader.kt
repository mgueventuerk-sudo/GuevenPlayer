package com.mg.player.ui.player

import com.mg.player.core.device.DeviceTier
import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.VideoPlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Ersatz-Variante auf Vorrat (ab 3.51): Beginnt ein Live-Sender nach dem Start zu hängen, lädt eine zweite Media3-Engine
 * die nächste Variante – ohne abzuspielen (kein Ton, kein Audio-Fokus). Wechselt der Wächter dann, übernimmt der Player
 * diese Engine samt Verbindung und Puffer, statt bei null anzufangen. Fängt sich der Sender, wird sie sofort freigegeben.
 *
 * Nur, wenn der Zugang der Ziel-Variante nachweislich einen freien Platz hat ([hasFreeSlot]): Die meisten Zugänge
 * erlauben 1–2 Verbindungen – eine zweite auf Vorrat würde sonst „Zugang belegt“ auslösen (siehe 3.45).
 */
internal class StandbyPreloader(private val vm: PlayerViewModel) {

    private var engine: VideoPlayerEngine? = null
    private var forItemId: String? = null
    private var loading: Job? = null
    private var played = false

    /** Schwache Geräte (≈1 GB RAM): zwei Player-Puffer gleichzeitig wären ein Speicherrisiko – dort kein Vorrat. */
    private val allowed = DeviceTier.of(vm.container.appContext) != DeviceTier.LOW

    /** Neue Quelle startet: Vorrat der alten verwerfen. */
    fun onPrepared() {
        played = false
        release()
    }

    fun onState(state: EngineState, playable: Playable?) {
        when {
            state.status == PlaybackStatus.READY && state.isPlaying -> {
                // Sender läuft (wieder): Vorrat freigeben – die Verbindung gehört nicht blockiert.
                // Auch ein Vorladen, das noch läuft – sonst stünde die Engine danach ohne Hänger als zweite Verbindung da.
                if (played && (engine != null || loading?.isActive == true)) release()
                played = true
            }

            state.status == PlaybackStatus.BUFFERING && played && playable != null -> preload(playable)

            else -> Unit
        }
    }

    /** Vorgeladene Engine für genau diese Variante (danach gehört sie dem Player), sonst null. */
    fun take(target: Playable): VideoPlayerEngine? {
        val ready = engine?.takeIf { forItemId == target.activeItemId } ?: return null
        engine = null
        forItemId = null
        loading?.cancel()
        loading = null
        return ready
    }

    fun release() {
        loading?.cancel()
        loading = null
        engine?.release()
        engine = null
        forItemId = null
    }

    /** Index der Variante, die sich vorladen lässt – oder null (schon geladen, kein Live, anderer Player, kein Platz). */
    private fun candidate(playable: Playable): Int? {
        val idle = allowed && engine == null && loading?.isActive != true && !vm.stoppedByLifecycle
        if (!idle || !playable.isLive || vm.uiState.value.engineType != PlayerEngineType.MEDIA3) return null
        val next = playable.nextSourceIndex() ?: return null
        val target = playable.variants.getOrNull(next) ?: return null
        return next.takeIf { hasFreeSlot(vm.container.connectionMonitor.status.value[target.playlistId]) }
    }

    private fun preload(playable: Playable) {
        val next = candidate(playable) ?: return
        loading = vm.scope.launch {
            try {
                val resolved = vm.resolver.variant(playable, next) ?: return@launch
                val standby = vm.container.playback.engineFactory.create(PlayerEngineType.MEDIA3)
                standby.setMuted(true)
                standby.prepare(vm.streamRequest(resolved, 0, autoPlay = false))
                engine = standby
                forItemId = resolved.activeItemId
                AppLog.log("Player", "Hänger: lade ${playable.sourceName(next)} auf Vorrat")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("Player", "Ersatz-Variante nicht vorladbar", e)
            }
        }
    }

    companion object {
        /** Nur mit bekannter Grenze und mindestens einem freien Platz (die laufende Verbindung zählt schon mit). */
        fun hasFreeSlot(status: ConnectionStatus?): Boolean = status != null && status.max > 0 && status.max - status.active >= 1
    }
}
