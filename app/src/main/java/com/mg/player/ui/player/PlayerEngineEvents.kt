package com.mg.player.ui.player

import android.os.SystemClock
import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.VideoPlayerEngine
import kotlinx.coroutines.flow.update

// Zustände der Engine verarbeiten (Teil von PlayerViewModel). Über Störungen entscheidet der Guard.

internal suspend fun PlayerViewModel.onEngineState(engine: VideoPlayerEngine, state: EngineState) {
    uiState.update { it.copy(engine = state) }
    audioRestorer.onTracks(uiState.value.playable, engine, state.tracksVersion)
    // Während ein Senderwechsel ansteht, gehören Meldungen noch zum alten Stream – nicht darauf reagieren.
    if (live.zapPending || stoppedByLifecycle) return
    val playable = uiState.value.playable
    measureStart(state)
    if (state.status == PlaybackStatus.READY) bingeSession.onReady(engine)
    if (uiState.value.engineType == PlayerEngineType.EXTERNAL) {
        onExternalState(state)
    } else {
        onBuiltInState(engine, state, playable)
    }
    if (state.status == PlaybackStatus.HANDED_OFF) eventSink.emit(PlayerEvent.Close)
    val fps = state.videoFrameRate
    if (settings.autoFrameRate && fps != null) frameRate.onFrameRate(fps, playable?.isLive == true)
}

/** Messpunkt fürs Fehlerprotokoll: Start/Senderwechsel bis die Wiedergabe läuft. */
private fun PlayerViewModel.measureStart(state: EngineState) {
    if (state.status != PlaybackStatus.READY || !state.isPlaying || preparedAt <= 0) return
    val took = SystemClock.elapsedRealtime() - preparedAt
    preparedAt = 0
    // Pausiert der Nutzer vor dem ersten Bild, kommt „spielt“ erst beim Fortsetzen: Im Protokoll standen so
    // 2 505 928 ms als „Startzeit“. Über PLAUSIBLE_START_MS ist das keine Startmessung mehr.
    if (took <= PLAUSIBLE_START_MS) AppLog.log("Messung", "Start bis Wiedergabe: $took ms")
}

/** Länger als eine Minute bis zum ersten Bild ist keine Startzeit, sondern eine Pause des Nutzers. */
private const val PLAUSIBLE_START_MS = 60_000L

private suspend fun PlayerViewModel.onExternalState(state: EngineState) {
    if (state.status == PlaybackStatus.ERROR) closeAfterExternalError(state)
    if (state.status == PlaybackStatus.ENDED) onEnded()
}

/** Eingebaute Engines: Filme/Folgen-Ende selbst, alles zu Störungen entscheidet der Guard. */
private suspend fun PlayerViewModel.onBuiltInState(engine: VideoPlayerEngine, state: EngineState, playable: Playable?) {
    sourceHealth.onState(state)
    if (state.status == PlaybackStatus.ERROR) {
        AppLog.log("Player", "${uiState.value.engineType.label}: ${state.errorMessage ?: "Fehler"} · ${playable?.title}")
    }
    if (state.status == PlaybackStatus.ENDED && playable?.isLive != true) onEnded()
    guard?.onEngineState(engine, state, playable)
}

private suspend fun PlayerViewModel.closeAfterExternalError(state: EngineState) {
    AppLog.log("Player", "Externer Player: ${state.errorMessage}")
    eventSink.emit(PlayerEvent.Toast(state.errorMessage ?: "Fehler"))
    eventSink.emit(PlayerEvent.Close)
}
