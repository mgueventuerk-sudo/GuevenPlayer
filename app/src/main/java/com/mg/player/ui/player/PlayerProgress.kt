package com.mg.player.ui.player

import android.os.SystemClock
import com.mg.player.core.di.AppContainer
import com.mg.player.player.engine.VideoPlayerEngine
import kotlinx.coroutines.launch

/**
 * VOD-Resume: speichert Position und Dauer von Filmen und Folgen regelmäßig lokal ab
 * (für "Weiterschauen" und den Fortschrittsbalken auf den Postern).
 */
internal class PlayerProgress(private val container: AppContainer) {

    private val playback = container.playbackRepository
    private var lastSavedAt = 0L

    /** Wird jede halbe Sekunde aufgerufen; gespeichert wird höchstens alle 10 Sekunden. */
    suspend fun maybeSave(playable: Playable, positionMs: Long, durationMs: Long, playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSavedAt <= SAVE_INTERVAL_MS || durationMs <= 0 || !playing) return
        lastSavedAt = now
        playable.progressKey?.let { playback.saveProgress(it, positionMs, durationMs, playable.seriesId) }
    }

    /** Sofort sichern (Pause durch Home-Taste, Verlassen des Players) – läuft im App-Scope weiter. */
    fun saveNow(playable: Playable?, engine: VideoPlayerEngine) {
        val key = playable?.progressKey ?: return
        val pos = engine.positionMs
        val dur = engine.durationMs
        if (dur <= 0 || pos <= 0) return
        container.appScope.launch { playback.saveProgress(key, pos, dur, playable.seriesId) }
    }

    suspend fun markWatched(playable: Playable, durationMs: Long) {
        val key = playable.progressKey ?: return
        playback.markWatched(key, durationMs, playable.seriesId)
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
