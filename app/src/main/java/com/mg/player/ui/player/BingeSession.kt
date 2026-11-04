package com.mg.player.ui.player

import android.os.SystemClock
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.binge.BingeAction
import com.mg.player.domain.binge.BingeConfig
import com.mg.player.domain.binge.BingeController
import com.mg.player.domain.binge.BingeState
import com.mg.player.domain.binge.ChapterMatcher
import com.mg.player.domain.binge.IntroResolver
import com.mg.player.domain.binge.IntroSource
import com.mg.player.domain.binge.IntroWindow
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.IntroProfile
import com.mg.player.player.engine.VideoPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Verbindet die reine Binge-Logik (BingeController) mit Player und Datenbank:
 * Intro-Erkennung in 3 Stufen, Serien-Gedächtnis und Auto-Play der nächsten Folge.
 */
class BingeSession(private val container: AppContainer, private val scope: CoroutineScope) {

    private val playback = container.playbackRepository
    private var controller: BingeController? = null
    private var seriesId: String? = null
    private var chaptersJob: Job? = null
    private var chaptersLoaded = false

    private val _state = MutableStateFlow(BingeState())
    val state: StateFlow<BingeState> = _state.asStateFlow()

    fun reset() {
        controller = null
        seriesId = null
        chaptersLoaded = false
        chaptersJob?.cancel()
        _state.value = BingeState()
    }

    /** Richtet die Logik für eine Episode ein und liefert die nächste Folge (falls vorhanden). */
    suspend fun setup(playable: Playable, settings: AppSettings): Episode? {
        val episode = playable.episode ?: return null
        val next = container.resolveNextEpisode(episode)
        val newController = BingeController(
            BingeConfig(
                isEpisode = true,
                hasNextEpisode = next != null,
                introMode = settings.introSkipMode,
                introSkipSeconds = settings.introSkipSeconds,
                autoPlayNext = settings.autoPlayNext,
                nextTriggerSeconds = settings.nextEpisodeTriggerSeconds,
            ),
        )
        // Stufe 2: gelerntes Intro-Zeitfenster der Serie.
        newController.knownWindow = IntroResolver.resolve(emptyList(), playback.introProfile(episode.seriesId))
        controller = newController
        seriesId = episode.seriesId
        return next
    }

    /** Stufe 1: Kapitel aus dem Stream, sobald die Engine sie kennt. */
    fun onReady(engine: VideoPlayerEngine) {
        if (chaptersLoaded || controller == null) return
        chaptersLoaded = true
        chaptersJob?.cancel()
        chaptersJob = scope.launch {
            delay(1_500)
            val chapters = engine.chapters()
            val current = controller ?: return@launch
            if (chapters.isEmpty()) return@launch
            val profile = seriesId?.let { playback.introProfile(it) }
            IntroResolver.resolve(chapters, profile)?.let { current.knownWindow = it }
            current.outroStartMs = ChapterMatcher.findOutroStart(chapters, engine.durationMs)
        }
    }

    fun onEngineChanged() {
        chaptersLoaded = false
    }

    fun tick(positionMs: Long, durationMs: Long): BingeAction? {
        val current = controller ?: return null
        if (durationMs <= 0) return null
        val (newState, action) = current.tick(positionMs, durationMs, SystemClock.elapsedRealtime())
        _state.value = newState
        return action
    }

    /** Liefert die Sprungposition; beim Fallback wird das Fenster für die ganze Serie gelernt. */
    fun skipIntro(positionMs: Long): Long? {
        val current = controller ?: return null
        val result = current.onSkipIntro(positionMs)
        _state.update { it.copy(skipIntroVisible = false) }
        val learned = result.learnedWindow
        if (learned != null && learned.source == IntroSource.PROFILE) save(learned)
        return result.seekToMs
    }

    fun markIntroEnd(positionMs: Long, skipSeconds: Int) {
        val window = IntroWindow((positionMs - skipSeconds * 1_000L).coerceAtLeast(0), positionMs, IntroSource.PROFILE)
        controller?.knownWindow = window
        save(window)
    }

    private fun save(window: IntroWindow) {
        val id = seriesId ?: return
        scope.launch { playback.saveIntroProfile(IntroProfile(id, window.startMs, window.endMs)) }
    }

    fun dismissIntro() {
        controller?.dismissIntro()
        _state.update { it.copy(skipIntroVisible = false) }
    }

    fun cancelNext() {
        controller?.onCancelNext()
        _state.update { it.copy(nextVisible = false) }
    }

    fun onPlayNextNow() {
        controller?.onPlayNextNow()
        _state.update { it.copy(nextVisible = false) }
    }

    fun shouldPlayNextOnEnd(): Boolean = controller?.shouldPlayNextOnEnd() == true
}
