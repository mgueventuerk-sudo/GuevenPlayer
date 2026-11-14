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

    /** Ein vorgeladener Stream: die stumme Engine, der Sender und die Variante (Original-ID), die sie lädt. */
    private class Slot(val engine: VideoPlayerEngine, val channelId: String, val itemId: String, val loadedAtMs: Long)

    /** Je anderem Zugang höchstens ein Stream auf Vorrat (jeder Zugang erlaubt nur eine Verbindung). */
    private val slots = HashMap<Long, Slot>()
    private var job: Job? = null

    /** Wann der Verbindungsstatus eines Zugangs zuletzt frisch erfragt wurde – nicht bei jedem Sender neu abfragen. */
    private val checkedAtMs = HashMap<Long, Long>()

    /**
     * Wie viele Streams zugleich auf Vorrat liegen dürfen. Jeder ist ein zusätzlicher Decoder samt Puffer neben dem laufenden;
     * TV-Sticks haben nur wenige Hardware-Decoder. Mittlere Geräte (Fire TV): einer, starke: zwei, schwache: keiner. Dazu
     * entscheidet der freie Arbeitsspeicher ([PreloadBudget.streamsOk]).
     */
    private val maxSlots = when (DeviceTier.of(vm.container.appContext)) {
        DeviceTier.LOW -> 0
        DeviceTier.NORMAL -> 1
        DeviceTier.HIGH -> 2
    }

    /**
     * Ein Sender auf einem **anderen** Zugang wird vorgeladen: zuerst aus den Sehgewohnheiten ([includeHabits], erst nach
     * ruhiger Wiedergabe), danach die Nachbarsender [neighbourIds] zum Hinzappen. Es gilt immer: nie auf dem Zugang des
     * laufenden Senders, nur mit nachweislich freiem Platz auf dem anderen. Ist der Kandidat schon geladen, bleibt er.
     */
    fun predict(current: Channel, neighbourIds: List<String> = emptyList(), includeHabits: Boolean = true) {
        job?.cancel()
        job = null
        if (maxSlots == 0 || vm.standby.isActive) {
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
            dropAll()
            return
        }
        val habits = if (includeHabits) withContext(Dispatchers.IO) { vm.container.channelHabits.top() } else emptyList()
        val targets = PreloadTargets.pick(
            candidateIds = (habits + neighbourIds).distinct().filter { it != current.id }.take(MAX_CANDIDATES),
            activePlaylistId = active,
            maxTargets = maxSlots,
            variantsOf = ::variantsOf,
            hasFreeSlot = ::accountIsFree,
        )
        // Was nicht mehr gewollt ist (anderer Sender, anderer Zugang), wird freigegeben; passende Streams bleiben.
        val wanted = targets.associateBy { it.playlistId }
        val now = SystemClock.elapsedRealtime()
        for ((playlistId, slot) in slots.entries.toList()) {
            val target = wanted[playlistId]
            // Ein alter, pausierter Live-Vorrat hängt hinter der Live-Kante oder hat die Verbindung verloren: neu laden.
            val keep = target != null && target.channelId == slot.channelId && target.itemId == slot.itemId &&
                !PreloadTiming.isOlderThan(slot.loadedAtMs, now, MAX_SLOT_AGE_MS)
            if (!keep) {
                slot.engine.release()
                slots.remove(playlistId)
            }
        }
        for (target in targets) {
            if (target.playlistId !in slots) preload(target)
        }
    }

    private suspend fun preload(target: PreloadTarget) {
        val base = resolver.channel(target.channelId) ?: return
        val index = base.variants.indexOfFirst { it.id == target.itemId }.takeIf { it >= 0 } ?: return
        val playable = resolver.variant(base, index) ?: return
        val engine = vm.container.playback.engineFactory.create(PlayerEngineType.MEDIA3)
        try {
            engine.setMuted(true)
            engine.prepare(vm.streamRequest(playable, 0, autoPlay = false))
        } catch (e: Exception) {
            // Sonst bliebe die Engine samt Decoder offen, ohne dass sie jemand kennt.
            engine.release()
            throw e
        }
        // Eigene Nutzung: Ein späterer Wechsel auf diesen Zugang soll nicht „Zugang belegt“ (fremdes Gerät) melden.
        vm.container.connectionMonitor.markOwnUse(target.playlistId)
        slots[target.playlistId] = Slot(engine, target.channelId, target.itemId, SystemClock.elapsedRealtime())
        AppLog.log("Player", "Vorgeladen auf einem anderen Zugang: ${playable.title} (Zugang ${target.playlistId}, ${slots.size} von $maxSlots)")
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
        if (playlistId in slots) return true
        val monitor = vm.container.connectionMonitor
        val now = SystemClock.elapsedRealtime()
        val known = monitor.status.value[playlistId]
        val fresh = if (known == null || PreloadTiming.isOlderThan(checkedAtMs[playlistId], now, RECHECK_MS)) {
            checkedAtMs[playlistId] = now
            monitor.checkNow(playlistId)
        } else {
            known
        }
        return StandbyPreloader.hasFreeSlot(fresh)
    }

    /** Welche Quelle des Senders vorgeladen ist (Original-ID) – damit der Player dieselbe Variante startet, sonst null. */
    fun preloadedItemFor(target: Playable): String? {
        if (target.kind != PlayableKind.LIVE) return null
        return slots.values.firstOrNull { it.channelId == target.channel?.id }?.itemId
    }

    /** Vorgeladene Engine für genau diesen Sender in genau dieser Variante (danach gehört sie dem Player), sonst null. */
    fun take(target: Playable): VideoPlayerEngine? {
        if (target.kind != PlayableKind.LIVE) return null
        val entry = slots.entries.firstOrNull { it.value.channelId == target.channel?.id && it.value.itemId == target.activeItemId } ?: return null
        slots.remove(entry.key)
        job?.cancel()
        job = null
        val engine = entry.value.engine
        // Ein Vorrat, der inzwischen gescheitert ist (z. B. Anbieter zählte unsere alte Verbindung noch mit), darf nicht
        // übernommen werden – der Player startete sonst mit einem Fehler statt frisch.
        if (engine.state.value.status == PlaybackStatus.ERROR) {
            AppLog.log("Player", "Vorrat auf anderem Zugang war gescheitert – frisch starten")
            engine.release()
            return null
        }
        return engine
    }

    fun release() {
        job?.cancel()
        job = null
        dropAll()
    }

    private fun dropAll() {
        slots.values.forEach { it.engine.release() }
        slots.clear()
    }

    /** Der laufende Sender spielt gerade ruhig: Sonst würde ein zweiter Decoder seinen Start stören. */
    private fun playingSteadily(): Boolean {
        val state = vm.engineHolder.value?.state?.value ?: return false
        return state.status == PlaybackStatus.READY && state.isPlaying
    }

    fun releaseForDevicePressure() {
        if (slots.isNotEmpty() || job?.isActive == true) AppLog.log("Player", "Vorgeladener Stream bei Gerätebelastung freigegeben")
        release()
    }

    private companion object {
        const val MAX_CANDIDATES = 6
        const val RECHECK_MS = 20_000L
        const val MAX_SLOT_AGE_MS = 90_000L
    }
}
