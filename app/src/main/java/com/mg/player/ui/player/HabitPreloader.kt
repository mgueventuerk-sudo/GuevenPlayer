package com.mg.player.ui.player

import android.os.SystemClock
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.device.PreloadBudget
import com.mg.player.core.device.PreloadPolicy
import com.mg.player.core.log.AppLog
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
 * Stream-Vorrat auf einem **anderen** Zugang: Läuft ein Sender ruhig, lädt diese Klasse leise den Stream, zu dem man
 * wahrscheinlich als Nächstes wechselt – erst aus den Sehgewohnheiten (nach `ChannelHabits`, zu dieser Tageszeit am
 * häufigsten als Nächstes), danach die Zapping-Nachbarn (±1) –, damit das Umschalten sich praktisch sofort anfühlt, statt
 * zu puffern.
 *
 * Warum nur auf einem anderen Zugang: Jeder Zugang erlaubt nur **eine** Verbindung. Ein zweiter Stream auf dem Zugang des
 * laufenden Senders würde abgelehnt oder würfe die laufende Wiedergabe raus. Auf einem anderen Zugang mit nachweislich
 * freiem Platz ([StandbyPreloader.hasFreeSlot], frisch per `checkNow` erfragt) ist es dagegen eine eigene Verbindung.
 * Gewählt wird die **Variante** des Senders, die auf einem anderen Zugang liegt – das gibt es nur in „Alle Zugänge“, wo ein
 * Sender mehrere Quellen aus mehreren Zugängen hat. (Bis 3.60 wurde „Alle Zugänge“ ganz ausgenommen und es lief praktisch
 * nie etwas vor: Eine Einzel-Playlist hat nur Sender auf demselben Zugang.)
 *
 * Auf mittleren Geräten (Fire TV) nur, solange der Arbeitsspeicher ausreicht ([PreloadBudget.streamsOk]); schwache Geräte nie.
 * Läuft schon eine [StandbyPreloader]-Vorladung (Wächter-Ersatz bei einem Hänger), wird hier nicht zusätzlich vorgeladen –
 * ein dritter gleichzeitiger Decoder ist keinem Gerät zuzumuten.
 */
internal class HabitPreloader(private val vm: PlayerViewModel) {

    private val resolver = PlayableResolver(vm.container)
    private var engine: VideoPlayerEngine? = null
    private var forChannelId: String? = null
    private var forItemId: String? = null
    private var forPlaylistId: Long? = null
    private var job: Job? = null

    /** Wann der Verbindungsstatus eines Zugangs zuletzt frisch erfragt wurde – nicht bei jedem Sender neu abfragen. */
    private val checkedAtMs = HashMap<Long, Long>()

    /** Schwache Geräte laden nie einen zusätzlichen Stream; sonst entscheidet der freie Arbeitsspeicher ([PreloadBudget.streamsOk]). */
    private val tierAllowed = DeviceTier.of(vm.container.appContext) != DeviceTier.LOW

    /**
     * Ein Sender auf einem **anderen** Zugang wird vorgeladen: zuerst aus den Sehgewohnheiten ([includeHabits], erst nach
     * ruhiger Wiedergabe), danach die Nachbarsender [neighbourIds] zum Hinzappen. Es gilt immer: nie auf dem Zugang des
     * laufenden Senders, nur mit nachweislich freiem Platz auf dem anderen. Ist der Kandidat schon geladen, bleibt er.
     */
    fun predict(current: Channel, neighbourIds: List<String> = emptyList(), includeHabits: Boolean = true) {
        job?.cancel()
        job = null
        if (!tierAllowed || vm.standby.isActive) {
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
                AppLog.error("Player", "Stream auf anderem Zugang nicht vorladbar", e)
            }
        }
    }

    private suspend fun loadCandidate(current: Channel, neighbourIds: List<String>, includeHabits: Boolean) {
        // Misst auch den freien Platz im Dateisystem: nicht im Hauptthread.
        val budget = withContext(Dispatchers.IO) { PreloadPolicy.current(vm.container.appContext) }
        val active = vm.uiState.value.playable?.activePlaylistId
        if (!budget.streamsOk || active == null) {
            dropEngine()
            return
        }
        val habits = if (includeHabits) withContext(Dispatchers.IO) { vm.container.channelHabits.top() } else emptyList()
        val target = PreloadTargets.pick(
            candidateIds = (habits + neighbourIds).distinct().filter { it != current.id }.take(MAX_CANDIDATES),
            activePlaylistId = active,
            variantsOf = ::variantsOf,
            hasFreeSlot = ::accountIsFree,
        )
        if (target == null) {
            dropEngine()
            return
        }
        if (target.channelId == forChannelId && target.itemId == forItemId && engine != null) return
        dropEngine()
        val base = resolver.channel(target.channelId) ?: return
        val index = base.variants.indexOfFirst { it.id == target.itemId }.takeIf { it >= 0 } ?: return
        val playable = resolver.variant(base, index) ?: return
        val preload = vm.container.playback.engineFactory.create(PlayerEngineType.MEDIA3)
        preload.setMuted(true)
        preload.prepare(vm.streamRequest(playable, 0, autoPlay = false))
        engine = preload
        forChannelId = target.channelId
        forItemId = target.itemId
        forPlaylistId = target.playlistId
        AppLog.log("Player", "Vorgeladen auf einem anderen Zugang: ${playable.title} (Zugang ${target.playlistId})")
    }

    /** (Zugang, Original-ID) jeder Variante des Senders, beste zuerst; Fehler zählen als „keine Varianten“. */
    private suspend fun variantsOf(channelId: String): List<Pair<Long, String>> = try {
        resolver.channel(channelId)?.variants?.map { it.playlistId to it.id }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.error("Player", "Varianten von $channelId nicht auflösbar", e)
        emptyList()
    }

    /**
     * Hat der Zugang einen freien Platz? Der Zugang, auf dem wir selbst gerade vorgeladen halten, zeigt „voll“ (unsere eigene
     * Verbindung): bleibt erlaubt. Sonst frisch erfragt (höchstens alle [RECHECK_MS]), nicht aus dem alten Stand.
     */
    private suspend fun accountIsFree(playlistId: Long): Boolean {
        if (playlistId == forPlaylistId && engine != null) return true
        val monitor = vm.container.connectionMonitor
        val now = SystemClock.elapsedRealtime()
        val known = monitor.status.value[playlistId]
        val fresh = if (known == null || now - (checkedAtMs[playlistId] ?: Long.MIN_VALUE) > RECHECK_MS) {
            checkedAtMs[playlistId] = now
            monitor.checkNow(playlistId)
        } else {
            known
        }
        return StandbyPreloader.hasFreeSlot(fresh)
    }

    /** Welche Quelle des Senders vorgeladen ist (Original-ID) – damit der Player dieselbe Variante startet, sonst null. */
    fun preloadedItemFor(target: Playable): String? =
        forItemId.takeIf { engine != null && target.kind == PlayableKind.LIVE && forChannelId == target.channel?.id }

    /** Vorgeladene Engine für genau diesen Sender in genau dieser Variante (danach gehört sie dem Player), sonst null. */
    fun take(target: Playable): VideoPlayerEngine? {
        val ready = engine?.takeIf {
            target.kind == PlayableKind.LIVE && forChannelId == target.channel?.id && forItemId == target.activeItemId
        } ?: return null
        engine = null
        forChannelId = null
        forItemId = null
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
        forItemId = null
        forPlaylistId = null
    }

    /** Der laufende Sender spielt gerade ruhig: Sonst würde ein zweiter Decoder seinen Start stören. */
    private fun playingSteadily(): Boolean {
        val state = vm.engineHolder.value?.state?.value ?: return false
        return state.status == PlaybackStatus.READY && state.isPlaying
    }

    fun releaseForDevicePressure() {
        if (engine != null || job?.isActive == true) AppLog.log("Player", "Vorgeladener Stream bei Gerätebelastung freigegeben")
        release()
    }

    private companion object {
        const val MAX_CANDIDATES = 6
        const val RECHECK_MS = 20_000L
    }
}
