package com.mg.player.ui.player

import com.mg.player.core.device.DeviceTier
import com.mg.player.core.log.AppLog
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.VideoPlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sehgewohnheits-Vorrat (nach `ChannelHabits`): Läuft ein Sender schon eine Weile ruhig, lädt diese Klasse leise den
 * Sender vor, der zu dieser Tageszeit sonst am häufigsten als Nächstes lief – damit das Umschalten sich praktisch
 * sofort anfühlt, statt zu puffern.
 *
 * Striktere Bedingung als bei [StandbyPreloader]: Der vorgeladene Sender muss auf einem **anderen** Zugang liegen als
 * der gerade laufende, und zwar mit nachweislich freiem Platz ([StandbyPreloader.hasFreeSlot]). Die meisten Zugänge
 * erlauben nur eine einzige Verbindung – ein zweiter Stream auf demselben Zugang würde entweder abgelehnt oder würfe
 * die laufende Wiedergabe raus (siehe `LiveSession.prepareNeighbours`, die deshalb aus demselben Grund nie einen
 * echten zweiten Stream öffnet). Läuft schon eine [StandbyPreloader]-Vorladung (Wächter-Ersatz bei einem Hänger), wird
 * hier nicht zusätzlich vorgeladen – ein dritter gleichzeitiger Decoder ist keinem Gerät zuzumuten.
 */
internal class HabitPreloader(private val vm: PlayerViewModel) {

    private val resolver = PlayableResolver(vm.container)
    private var engine: VideoPlayerEngine? = null
    private var forChannelId: String? = null
    private var job: Job? = null

    /** Wie bei der Ersatz-Variante auf Vorrat: ein zusätzlicher Decoder bleibt den leistungsstärksten Geräten vorbehalten. */
    private val allowed = StandbyPreloader.supportsStandbyPreload(DeviceTier.of(vm.container.appContext))

    /** Nach ruhiger Wiedergabe aufgerufen (siehe `LiveSession`, derselbe Zeitpunkt wie `channelHabits.record`). */
    fun predict(current: Channel) {
        release()
        if (!allowed || vm.standby.isActive || current.playlistId == Combined.ID) return
        job = vm.scope.launch {
            try {
                val top = withContext(Dispatchers.IO) { vm.container.channelHabits.top() }
                val candidateId = pickCandidate(
                    habitChannelIds = top,
                    currentChannelId = current.id,
                    currentPlaylistId = current.playlistId,
                    playlistIdOf = { id ->
                        try {
                            vm.container.liveRepository.channel(id)?.playlistId
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.error("Player", "Sehgewohnheits-Sender $id nicht auflösbar", e)
                            null
                        }
                    },
                    hasFreeSlot = { playlistId -> StandbyPreloader.hasFreeSlot(vm.container.connectionMonitor.status.value[playlistId]) },
                ) ?: return@launch
                val playable = resolver.channel(candidateId) ?: return@launch
                val preload = vm.container.playback.engineFactory.create(PlayerEngineType.MEDIA3)
                preload.setMuted(true)
                preload.prepare(vm.streamRequest(playable, 0, autoPlay = false))
                engine = preload
                forChannelId = candidateId
                AppLog.log("Player", "Sehgewohnheit: ${playable.title} auf einem anderen Zugang vorgeladen")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("Player", "Sehgewohnheits-Sender nicht vorladbar", e)
            }
        }
    }

    /** Vorgeladene Engine für genau diesen Sender (danach gehört sie dem Player), sonst null. */
    fun take(target: Playable): VideoPlayerEngine? {
        val ready = engine?.takeIf { target.kind == PlayableKind.LIVE && forChannelId == target.channel?.id } ?: return null
        engine = null
        forChannelId = null
        job?.cancel()
        job = null
        return ready
    }

    fun release() {
        job?.cancel()
        job = null
        engine?.release()
        engine = null
        forChannelId = null
    }

    fun releaseForDevicePressure() {
        if (engine != null || job?.isActive == true) AppLog.log("Player", "Sehgewohnheits-Vorrat bei Gerätebelastung freigegeben")
        release()
    }

    companion object {
        /**
         * Erster Sender aus den Sehgewohnheiten, der weder der laufende noch auf dessen Zugang ist und dort
         * nachweislich einen freien Platz hat. Rein, ohne Datenbank/Netz - direkt testbar.
         */
        internal suspend fun pickCandidate(
            habitChannelIds: List<String>,
            currentChannelId: String,
            currentPlaylistId: Long,
            playlistIdOf: suspend (String) -> Long?,
            hasFreeSlot: (Long) -> Boolean,
        ): String? {
            if (currentPlaylistId == Combined.ID) return null
            for (id in habitChannelIds) {
                val playlistId = if (id == currentChannelId) null else playlistIdOf(id)
                if (playlistId == null || playlistId == Combined.ID || playlistId == currentPlaylistId) continue
                if (hasFreeSlot(playlistId)) return id
            }
            return null
        }
    }
}
