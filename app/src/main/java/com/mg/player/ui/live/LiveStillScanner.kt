package com.mg.player.ui.live

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.domain.model.forAutomaticOptimization
import com.mg.player.domain.model.playlistIdOf
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.StreamRequest
import com.mg.player.player.engine.VideoPlayerEngine
import com.mg.player.ui.player.EngineFallback
import com.mg.player.ui.player.PlayableResolver
import com.mg.player.ui.player.VideoFrames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Ein Sender mit zuletzt gesichertem Standbild ([failed] = bisher kein Bild bekommen). */
@Immutable
data class MosaicTile(val channel: Channel, val still: ImageBitmap? = null, val failed: Boolean = false)

/**
 * Standbild-Abtaster für Live-Mosaik und die Startseiten-Zeile „Läuft gerade“. Ein einziger, stummer Player öffnet
 * die Sender nacheinander in ihrer Kachel ([scanning]) und sichert das Bild. So belegt er nie mehr als eine
 * Verbindung beim Anbieter und dekodiert nie mehr als ein Video; zwischen den Runden ist die Verbindung geschlossen.
 *
 * Die UI zeigt [engine] in der Kachel [scanning] (EngineSurface mit [onSurface]); alle Aufrufe auf dem Main-Thread.
 */
class LiveStillScanner(private val container: AppContainer, private val scope: CoroutineScope, private val logTag: String) {

    private val resolver = PlayableResolver(container)
    val lowTier = DeviceTier.of(container.appContext) == DeviceTier.LOW

    private val _tiles = MutableStateFlow<List<MosaicTile>?>(null)

    /** null = lädt noch, leer = keine Sender. */
    val tiles: StateFlow<List<MosaicTile>?> = _tiles.asStateFlow()

    private val _scanning = MutableStateFlow<Int?>(null)

    /** Kachel, in der der Abtaster gerade läuft (dort ist kurz das Live-Bild zu sehen). */
    val scanning: StateFlow<Int?> = _scanning.asStateFlow()

    private val _engine = MutableStateFlow<VideoPlayerEngine?>(null)
    val engine: StateFlow<VideoPlayerEngine?> = _engine.asStateFlow()
    private var engineType: PlayerEngineType? = null

    /** Fläche der abgetasteten Kachel; nur gesetzt, solange sie angezeigt wird (kein Festhalten der View). */
    private var surface: FrameLayout? = null

    /** Zugang des zuletzt abgetasteten Senders (eigene Nutzung beim Freigeben vermerken). */
    private var account: Long? = null
    private var job: Job? = null

    fun onSurface(view: FrameLayout, attached: Boolean) {
        if (attached) surface = view else if (surface === view) surface = null
    }

    /**
     * Startet (neu): [load] liefert die Sender, falls noch keine geladen sind ([reload] erzwingt neues Laden).
     * Eine Runde dauert mindestens [roundMs]; nach [maxRounds] Runden hört der Abtaster auf (die Bilder bleiben).
     */
    fun start(roundMs: Long, maxRounds: Int = Int.MAX_VALUE, reload: Boolean = false, load: suspend () -> List<Channel>) {
        job?.cancel()
        if (reload) _tiles.value = null
        job = scope.launch {
            if (_tiles.value == null) _tiles.value = loadSafely(load).map { MosaicTile(it) }
            var round = 0
            while (isActive && round < maxRounds) {
                val roundStart = SystemClock.elapsedRealtime()
                val count = _tiles.value?.size ?: 0
                for (index in 0 until count) scan(index)
                // Pause zwischen den Runden ohne offene Verbindung: Anbieter zählen sonst einen Dauerstream.
                _scanning.value = null
                releaseEngine()
                if (count == 0) break
                round++
                if (round < maxRounds) delay(MosaicPlan.restAfterRound(roundStart, SystemClock.elapsedRealtime(), roundMs))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _scanning.value = null
        releaseEngine()
    }

    /** Endgültig beenden (ViewModel wird verworfen). */
    fun release() {
        stop()
        surface = null
    }

    private suspend fun loadSafely(load: suspend () -> List<Channel>): List<Channel> = try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.error(logTag, "Sender nicht ladbar", e)
        emptyList()
    }

    private suspend fun scan(index: Int) {
        val tile = _tiles.value?.getOrNull(index) ?: return
        // Erst die alte Fläche abbauen lassen, dann die neue zeigen: Eine Engine hängt nie an zwei Flächen zugleich.
        _scanning.value = null
        delay(SURFACE_SWITCH_MS)
        _scanning.value = index
        val still = try {
            capture(tile.channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(logTag, "Standbild von ${tile.channel.name} fehlgeschlagen", e)
            null
        }
        _tiles.update { list ->
            list?.mapIndexed { i, t ->
                if (i != index || t.channel.id != tile.channel.id) t else t.copy(still = still ?: t.still, failed = still == null && t.still == null)
            }
        }
    }

    private suspend fun capture(channel: Channel): ImageBitmap? {
        if (!startStream(channel) || !awaitFirstFrame()) return null
        delay(MosaicPlan.SETTLE_MS)
        val view = surface?.let(VideoFrames::findVideoView) ?: return null
        val (width, height) = if (lowTier) 256 to 144 else 384 to 216
        val bitmap = createBitmap(width, height)
        return if (VideoFrames.copy(view, bitmap)) bitmap.asImageBitmap() else null
    }

    /** Öffnet [channel] stumm in der Fläche der abgetasteten Kachel; false = nicht abspielbar oder keine Fläche. */
    private suspend fun startStream(channel: Channel): Boolean {
        val settings = container.settingsRepository.settings.first().forAutomaticOptimization(DeviceTier.of(container.appContext))
        val playable = resolver.channel(channel.id) ?: return false
        // Eigene Verbindung (Mosaik/„Läuft gerade“) – sonst hielte die App sie beim nächsten Start für ein fremdes Gerät.
        account = playable.activePlaylistId ?: playlistIdOf(playable.id)
        account?.let(container.connectionMonitor::markOwnUse)
        val engine = engineFor(EngineFallback.engineFor(settings.liveEngine, playable, preview = true))
        engine.setMuted(true)
        // Erst die Fläche, dann der Stream: Das Bild wird von genau dieser Fläche gelesen.
        withTimeoutOrNull(SURFACE_TIMEOUT_MS) { while (surface == null) delay(POLL_MS) } ?: return false
        engine.prepare(
            StreamRequest(
                url = playable.url,
                title = channel.name,
                startPositionMs = 0,
                isLive = true,
                bufferProfile = BufferProfile.FAST,
                hwDecoding = settings.hwDecoding,
                allowInsecureHttp = playable.allowInsecureHttp,
            ),
        )
        return true
    }

    /** true, sobald die Engine spielt und ein Bild gezeigt hat; false bei Fehler oder nach [MosaicPlan.FIRST_FRAME_TIMEOUT_MS]. */
    private suspend fun awaitFirstFrame(): Boolean = withTimeoutOrNull(MosaicPlan.FIRST_FRAME_TIMEOUT_MS) {
        var result: Boolean? = null
        while (result == null) {
            val engine = _engine.value ?: return@withTimeoutOrNull false
            val state = engine.state.value
            result = when {
                state.status == PlaybackStatus.ERROR -> false
                state.status == PlaybackStatus.READY && state.isPlaying && (engine.renderedFrames ?: 1L) > 0L -> true
                else -> null
            }
            if (result == null) delay(POLL_MS)
        }
        result
    } == true

    private fun engineFor(type: PlayerEngineType): VideoPlayerEngine {
        _engine.value?.takeIf { engineType == type }?.let { return it }
        releaseEngine()
        return container.playback.engineFactory.create(type).also {
            _engine.value = it
            engineType = type
        }
    }

    private fun releaseEngine() {
        if (_engine.value != null) account?.let(container.connectionMonitor::markOwnUse)
        _engine.value?.release()
        _engine.value = null
        engineType = null
    }

    private companion object {
        const val POLL_MS = 100L
        const val SURFACE_SWITCH_MS = 60L
        const val SURFACE_TIMEOUT_MS = 1_500L
    }
}
