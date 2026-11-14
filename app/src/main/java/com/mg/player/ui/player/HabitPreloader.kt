package com.mg.player.ui.player

import com.mg.player.core.device.DeviceTier
import com.mg.player.core.log.AppLog
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.VideoPlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stream-Vorrat auf einem **anderen** Zugang: Läuft ein Sender ruhig, lädt diese Klasse leise den Sender vor, zu dem man
 * wahrscheinlich als Nächstes wechselt – erst aus den Sehgewohnheiten (nach `ChannelHabits`, zu dieser Tageszeit am
 * häufigsten als Nächstes), danach die Zapping-Nachbarn (±1) –, damit das Umschalten sich praktisch sofort anfühlt, statt
 * zu puffern.
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
    private var forPlaylistId: Long? = null
    private var job: Job? = null

    /** Wie bei der Ersatz-Variante auf Vorrat: ein zusätzlicher Decoder bleibt den leistungsstärksten Geräten vorbehalten. */
    private val allowed = StandbyPreloader.supportsStandbyPreload(DeviceTier.of(vm.container.appContext))

    /**
     * Ein Sender auf einem **anderen** Zugang wird vorgeladen: zuerst aus den Sehgewohnheiten ([includeHabits], erst nach
     * ruhiger Wiedergabe), danach die Nachbarsender [neighbourIds] zum Hinzappen. Es gilt immer: nie auf dem Zugang des
     * laufenden Senders, nur mit nachweislich freiem Platz auf dem anderen. Ist der Kandidat schon geladen, bleibt er.
     */
    fun predict(current: Channel, neighbourIds: List<String> = emptyList(), includeHabits: Boolean = true) {
        job?.cancel()
        job = null
        if (!allowed || vm.standby.isActive || current.playlistId == Combined.ID) {
            release()
            return
        }
        if (!playingSteadily()) return
        job = vm.scope.launch {
            try {
                loadCandidate(current, neighbourIds, includeHabits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("Player", "Sehgewohnheits-Sender nicht vorladbar", e)
            }
        }
    }

    private suspend fun loadCandidate(current: Channel, neighbourIds: List<String>, includeHabits: Boolean) {
        val top = if (includeHabits) withContext(Dispatchers.IO) { vm.container.channelHabits.top() } else emptyList()
        val candidateId = pickCandidate(
            habitChannelIds = (top + neighbourIds).distinct(),
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
            // Der Zugang, auf dem wir selbst gerade vorgeladen halten, zeigt „voll“ (unsere eigene Verbindung): bleibt erlaubt.
            hasFreeSlot = { playlistId ->
                (playlistId == forPlaylistId && engine != null) ||
                    StandbyPreloader.hasFreeSlot(vm.container.connectionMonitor.status.value[playlistId])
            },
        )
        if (candidateId == null) {
            dropEngine()
            return
        }
        if (candidateId == forChannelId && engine != null) return
        dropEngine()
        val playable = resolver.channel(candidateId) ?: return
        val preload = vm.container.playback.engineFactory.create(PlayerEngineType.MEDIA3)
        preload.setMuted(true)
        preload.prepare(vm.streamRequest(playable, 0, autoPlay = false))
        engine = preload
        forChannelId = candidateId
        forPlaylistId = playable.channel?.playlistId
        AppLog.log("Player", "Vorgeladen auf einem anderen Zugang: ${playable.title}")
    }

    /** Vorgeladene Engine für genau diesen Sender (danach gehört sie dem Player), sonst null. */
    fun take(target: Playable): VideoPlayerEngine? {
        val ready = engine?.takeIf { target.kind == PlayableKind.LIVE && forChannelId == target.channel?.id } ?: return null
        engine = null
        forChannelId = null
        forPlaylistId = null
        job?.cancel()
        job = null
        return ready
    }

    fun release() {
        job?.cancel()
        job = null
        dropEngine()
    }

    private fun dropEngine() {
        engine?.release()
        engine = null
        forChannelId = null
        forPlaylistId = null
    }

    /** Der laufende Sender spielt gerade ruhig: Sonst würde ein zweiter Decoder seinen Start stören. */
    private fun playingSteadily(): Boolean {
        val state = vm.engineHolder.value?.state?.value ?: return false
        return state.status == PlaybackStatus.READY && state.isPlaying
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
