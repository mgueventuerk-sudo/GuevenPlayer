package com.mg.player.ui.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.domain.binge.BingeState
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.HwDecoding
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.domain.model.playlistIdOf
import com.mg.player.player.engine.StreamRequest
import com.mg.player.player.engine.VideoPlayerEngine
import com.mg.player.player.supervisor.StartHint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Zustand und Engine des Vollbild-Players. Start und Inhaltswechsel stehen in PlayerContent.kt, Bedienung in
 * PlayerControls.kt, Lebenszyklus in PlayerLifecycle.kt. Über Störungen entscheidet allein der [guard].
 */
class PlayerViewModel(internal val container: AppContainer, internal val request: PlayRequest) : ViewModel() {

    internal val uiState = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = uiState.asStateFlow()

    /** Position/Dauer getrennt vom übrigen Zustand: Nur die Zeitleiste zeichnet sich zweimal pro Sekunde neu. */
    internal val positionState = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = positionState.asStateFlow()
    internal val engineHolder = MutableStateFlow<VideoPlayerEngine?>(null)
    val engine: StateFlow<VideoPlayerEngine?> = engineHolder.asStateFlow()
    internal val eventSink = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerEvent> = eventSink

    internal val scope = guardedScope("Player")
    internal val resolver = PlayableResolver(container)
    internal val accounts = AccountGate(container)
    internal var switchingAccount = false
    internal val progress = PlayerProgress(container)
    internal val live = LiveSession(container, scope, uiState) { playable -> startPlayable(playable) }
    internal val bingeSession = BingeSession(container, scope)
    val binge: StateFlow<BingeState> = bingeSession.state
    internal val audioRestorer = AudioTrackRestorer(container.playback.trackMemory)
    internal val frameRate = FrameRateSwitcher(scope) { fps -> eventSink.emit(PlayerEvent.ApplyFrameRate(fps)) }
    internal val infoSampler = StreamInfoSampler(scope, container.networkMonitor, { engineHolder.value }) { uiState.value.engineType.label }
    val streamInfo: StateFlow<StreamInfo?> = infoSampler.info

    /** Merkt sich Hänger und Fehler je Quelle (gute Quellen zuerst beim nächsten Aufruf). */
    internal val sourceHealth = SourceHealthTracker(
        container.playback.sourceHealth,
        online = { container.networkMonitor.status.value.online },
        clock = SystemClock::elapsedRealtime,
    )

    /** Entscheidet bei Störungen; bis die Einstellungen geladen sind, passiert noch nichts (siehe [startPlayer]). */
    internal var guard: PlaybackGuard? = null

    internal var settings = AppSettings()
    internal var preferredEngine = PlayerEngineType.MEDIA3

    /** Für diesen Inhalt wurde automatisch auf eine andere Engine gewechselt; der nächste startet wieder mit [preferredEngine]. */
    internal var autoFallbackActive = false
        private set

    /** Sicherere Decoder-/Ton-Einstellungen, die der Wächter für diesen Inhalt gewählt hat. */
    internal var decoderOverride = StartHint()
    internal var engineStateJob: Job? = null
    internal var switchingEpisode = false
    internal var pausedByLifecycle = false

    /** App nicht sichtbar (ON_STOP): Es darf nichts abgespielt werden – auch nicht, wenn der Start gerade noch läuft. */
    internal var stoppedByLifecycle = false
    internal var engineReleasedByStop = false

    /** Freigabe nach ON_STOP läuft (Haltezeit, siehe [RELEASE_DELAY_MS]). */
    internal var pendingRelease: Job? = null
    internal var resumeFromHold = false
    internal var lastKnownPosition = 0L
    internal var preparedAt = 0L

    /** Die übernommene Vorschau-Engine spielt schon: den ersten Start nicht erneut laden. */
    private var skipNextPrepare = false

    init {
        startPlayer()
    }

    internal fun createEngine(type: PlayerEngineType) = adoptEngine(container.playback.engineFactory.create(type), type, alreadyPlaying = false)

    /** [engine] verwenden; [alreadyPlaying]: spielt den Stream schon (Übernahme aus der Vorschau) – nicht neu laden. */
    internal fun adoptEngine(engine: VideoPlayerEngine, type: PlayerEngineType, alreadyPlaying: Boolean = true) {
        engineStateJob?.cancel()
        engineHolder.value?.release()
        skipNextPrepare = alreadyPlaying
        engineHolder.value = engine
        engineReleasedByStop = false
        frameRate.reset()
        bingeSession.onEngineChanged()
        uiState.update { it.copy(engineType = type) }
        engineStateJob = scope.launch { engine.state.collect { onEngineState(engine, it) } }
    }

    /** Für diesen Inhalt auf [type] wechseln (Wächter, Gedächtnis); der nächste Inhalt startet wieder normal. */
    internal fun useFallbackEngine(type: PlayerEngineType) {
        val playable = uiState.value.playable
        if (playable != null && !EngineFallback.canSwitchTo(type, playable)) return
        createEngine(type)
        autoFallbackActive = true
    }

    /** Zurück zur eingestellten Engine, falls für den letzten Inhalt eine Notlösung aktiv war. */
    internal fun restorePreferredEngine() {
        if (!autoFallbackActive) return
        autoFallbackActive = false
        createEngine(preferredEngine)
    }

    /** Neuversuch für den aktuellen Inhalt (Live von vorn, Filme an der letzten Position). false = nichts zu tun. */
    internal fun retryCurrent(): Boolean {
        if (live.zapPending || stoppedByLifecycle) return false
        val playable = uiState.value.playable ?: return false
        prepare(playable, if (playable.isLive) 0 else lastKnownPosition)
        return true
    }

    /** Stream starten (inkl. Wächter) – aber nie, solange die App im Hintergrund ist. */
    internal fun beginPlayback(playable: Playable, positionMs: Long) {
        if (stoppedByLifecycle) return
        audioRestorer.reset()
        prepare(playable, positionMs)
        guard?.onPlaybackBegun(playable)
    }

    internal fun prepare(playable: Playable, positionMs: Long) {
        if (stoppedByLifecycle) return
        preparedAt = SystemClock.elapsedRealtime()
        sourceHealth.onPrepared(playable)
        guard?.onEnginePrepared()
        if (skipNextPrepare) {
            skipNextPrepare = false
            AppLog.log("Player", "Vorschau übernommen · ${playable.title}")
            return
        }
        val fix = decoderOverride
        engineHolder.value?.prepare(
            StreamRequest(
                playable.url,
                playable.title,
                positionMs,
                playable.isLive,
                settings.bufferProfile,
                hwDecoding = if (fix.safeDecoder) HwDecoding.OFF else settings.hwDecoding,
                tunneling = settings.tunneling && !fix.safeDecoder,
                audioPassthrough = settings.audioPassthrough && !fix.safeAudio,
                allowInsecureHttp = playable.allowInsecureHttp,
            ),
        )
    }

    /** Variante wechseln – nur wenn noch genau dieser Sender in dieser Variante läuft (sonst veraltet, verwerfen). */
    internal suspend fun switchVariant(playable: Playable, index: Int) {
        if (!isCurrent(playable)) return
        // Film/Folge („Alle Zugänge“): an derselben Stelle in der anderen Quelle weiter.
        val position = if (playable.isLive) {
            0L
        } else {
            val now = engineHolder.value?.positionMs?.takeIf { it > 0 } ?: lastKnownPosition
            CombinedPlayables.mapPosition(now, playable.activeVodSource?.durationSec, playable.vodSources.getOrNull(index)?.durationSec)
        }
        sourceHealth.onLeaving()
        val next = resolver.variant(playable, index) ?: return
        if (!isCurrent(playable) || live.zapPending) return
        uiState.update { it.copy(playable = next) }
        guard?.onVariantSwitched(next)
        beginPlayback(next, position)
    }

    private fun isCurrent(playable: Playable): Boolean = uiState.value.playable?.isSameStream(playable) == true

    internal fun toast(message: String) {
        scope.launch { eventSink.emit(PlayerEvent.Toast(message)) }
    }

    /** Engine wechseln. [byUser] = Auswahl im Menü (bricht laufende Maßnahmen ab); sonst automatisch für diesen Inhalt. */
    fun switchEngine(type: PlayerEngineType, byUser: Boolean = true) {
        val playable = uiState.value.playable ?: return
        if (type == uiState.value.engineType) return
        if (!EngineFallback.canSwitchTo(type, playable)) {
            toast("Media3 bleibt für diesen HTTPS-Zugang aktiv, damit HTTP-Unterstreams gesperrt bleiben")
            return
        }
        if (type == PlayerEngineType.EXTERNAL && playable.kind == PlayableKind.RECORDING) {
            toast("Aufnahmen lassen sich nur mit den eingebauten Playern abspielen")
            return
        }
        val position = if (playable.isLive) 0 else (engineHolder.value?.positionMs?.takeIf { it > 0 } ?: lastKnownPosition)
        if (byUser) guard?.onUserInterrupt() else autoFallbackActive = true
        createEngine(type)
        guard?.onEngineSwitched(type)
        prepare(playable, position)
    }

    /** Stream-Info im Player-Menü: misst nur, solange sie sichtbar ist. */
    fun setStreamInfoVisible(visible: Boolean) = if (visible) infoSampler.start() else infoSampler.stop()

    override fun onCleared() {
        uiState.value.playable?.let { it.activePlaylistId ?: playlistIdOf(it.id) }?.let(container.connectionMonitor::markOwnUse)
        accounts.answer(AccountChoice.Cancel)
        guard?.release()
        infoSampler.stop()
        engineHolder.value?.let { engine ->
            if (uiState.value.engineType != PlayerEngineType.EXTERNAL && !stoppedByLifecycle) progress.saveNow(uiState.value.playable, engine)
            if (!handBackToPreview(engine)) engine.release()
        }
        engineHolder.value = null
    }
}
