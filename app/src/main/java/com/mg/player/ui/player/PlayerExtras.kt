package com.mg.player.ui.player

import com.mg.player.core.log.AppLog
import com.mg.player.player.TrackMemory
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.TrackOption
import com.mg.player.player.engine.VideoPlayerEngine

/** Zurück zur Senderliste mit Vorschau: Der laufende Sender spielt dort weiter, statt neu zu laden. */
internal fun PlayerViewModel.handBackToPreview(engine: VideoPlayerEngine): Boolean {
    val handoff = container.playback.previewHandoff
    val expected = handoff.expectReturn.also { handoff.expectReturn = false }
    val playable = uiState.value.playable ?: return false
    val channel = playable.channel?.takeIf { playable.kind == PlayableKind.LIVE } ?: return false
    val type = uiState.value.engineType
    val healthy = engine.state.value.let { (it.status == PlaybackStatus.READY && it.isPlaying) || it.status == PlaybackStatus.BUFFERING }
    if (!expected || !healthy || stoppedByLifecycle || live.zapPending || autoFallbackActive) return false
    if (type != EngineFallback.engineFor(settings.liveEngine, playable, preview = true)) return false
    handoff.offer(channel.id, playable.url, type, engine)
    return true
}

/** Schlüssel für die gemerkte Tonspur: Sender (alle Qualitäten gemeinsam), Serie oder Film. */
internal fun Playable.trackKey(): String? = when (kind) {
    PlayableKind.LIVE, PlayableKind.CATCHUP -> channel?.let { "ch:${it.playlistId}:${it.groupKey.ifBlank { it.id }}" }
    PlayableKind.EPISODE -> seriesId?.let { "se:$it" }
    PlayableKind.MOVIE -> "mo:$id"
    PlayableKind.RECORDING -> null
}

/** Wählt die gemerkte Tonspur einmal je Start, sobald die Engine ihre Spuren kennt. */
internal class AudioTrackRestorer(private val memory: TrackMemory) {
    private var doneFor: String? = null
    private var seenTracks = -1
    private var singleLoggedFor: String? = null

    /** Neuer Start (Sender, Folge, Neuversuch): Die Engine beginnt wieder mit ihrer Standardspur. */
    fun reset() {
        doneFor = null
        seenTracks = -1
        singleLoggedFor = null
    }

    fun onTracks(playable: Playable?, engine: VideoPlayerEngine, tracksVersion: Int) {
        if (tracksVersion == seenTracks) return
        seenTracks = tracksVersion
        val key = playable?.trackKey()
        if (key == null || key == doneFor) return
        val options = engine.audioTracks().ifEmpty { return }
        // LibVLC meldet die Spuren nacheinander: Bei nur einer bekannten noch nicht abschließen, sonst wurde die zweite
        // (etwa Deutsch) nie mehr gewählt.
        if (options.size < 2) {
            if (singleLoggedFor != key) AppLog.log("Player", "Tonspur: ${options.first().label} (bisher einzige)")
            singleLoggedFor = key
            return
        }
        doneFor = key
        val restored = memory.restore(key, options)?.also { engine.selectAudioTrack(it.id) }
        val playing = restored ?: options.firstOrNull { it.selected }
        val how = if (restored != null) " (gemerkt)" else ""
        AppLog.log("Player", "Tonspur: ${playing?.label ?: "unbekannt"}$how · zur Wahl: ${options.joinToString(", ") { it.label }}")
    }

    fun remember(playable: Playable?, engine: VideoPlayerEngine, option: TrackOption) {
        val key = playable?.trackKey() ?: return
        memory.save(key, engine.audioTracks(), option)
        doneFor = key
    }
}

