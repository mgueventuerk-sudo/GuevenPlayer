package com.mg.player.ui.player

import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlayRequest
import kotlinx.coroutines.launch

// Bedienung, die nur an LiveSession bzw. BingeSession weiterreicht – ausgelagert, damit das
// PlayerViewModel übersichtlich bleibt.

fun PlayerViewModel.zap(delta: Int) = live.zap(delta)

fun PlayerViewModel.jumpToNumber(number: Int) {
    if (!live.jumpToNumber(number)) toast("Sender $number gibt es in dieser Liste nicht")
}

fun PlayerViewModel.previousChannel() {
    if (!live.switchToPrevious()) toast("Noch kein vorheriger Sender")
}

fun PlayerViewModel.restartProgram() {
    if (!live.restartProgram()) toast("Für diese Sendung ist kein Archiv verfügbar")
}

fun PlayerViewModel.goLive() = live.goLive()

/** Live bzw. Archiv 10 Minuten zurück (nur Sender mit Archiv). */
fun PlayerViewModel.rewindLive() {
    if (!live.rewind(10)) toast("Für diesen Sender ist kein Archiv verfügbar")
}

/** Schnell-Zapping aus der eingeblendeten Senderliste. */
fun PlayerViewModel.zapTo(channelId: String) = live.zapTo(channelId)

/** Zugang und Kategorie der aktuellen Senderliste (für die Schnell-Zapping-Liste im Player). */
fun PlayerViewModel.channelListSource(): Pair<Long, String>? {
    val playlistId = live.listPlaylistId ?: settings.activePlaylistId ?: return null
    if (request !is PlayRequest.Live && request !is PlayRequest.Catchup) return null
    val category = live.listCategoryId ?: return null
    return playlistId to category
}

val PlayerViewModel.hasPreviousChannel: Boolean get() = live.hasPreviousChannel

fun PlayerViewModel.skipIntro() {
    val engine = engine.value ?: return
    bingeSession.skipIntro(engine.positionMs)?.let { engine.seekTo(it) }
}

/** "Intro endet hier": speichert das Zeitfenster für alle weiteren Folgen der Serie. */
fun PlayerViewModel.markIntroEnd() {
    val engine = engine.value ?: return
    bingeSession.markIntroEnd(engine.positionMs, settings.introSkipSeconds)
    toast("Intro für diese Serie gespeichert")
}

fun PlayerViewModel.dismissIntro() = bingeSession.dismissIntro()

fun PlayerViewModel.cancelNext() = bingeSession.cancelNext()

fun PlayerViewModel.playNextNow() {
    bingeSession.onPlayNextNow()
    playNextEpisode()
}

fun PlayerViewModel.togglePlayPause() {
    val engine = engine.value ?: return
    if (engine.state.value.isPlaying) engine.pause() else engine.play()
}

/** Spulen (nur Filme, Serien, Catch-up), begrenzt auf den Anfang bzw. eine Sekunde vor dem Ende. */
fun PlayerViewModel.seekBy(deltaMs: Long) {
    val engine = engine.value ?: return
    if (ui.value.playable?.isLive != false) return
    val dur = engine.durationMs
    val target = (engine.positionMs + deltaMs).coerceAtLeast(0).let { if (dur > 0) it.coerceAtMost(dur - 1_000) else it }
    engine.seekTo(target)
    showPosition(target)
}

/** Zeitleiste am Handy: an eine Stelle (0…1) springen. */
fun PlayerViewModel.seekToFraction(fraction: Float) {
    val engine = engine.value ?: return
    val dur = engine.durationMs
    if (dur <= 0) return
    seekBy((dur * fraction.coerceIn(0f, 1f)).toLong() - engine.positionMs)
}

/** Zeitleiste am Fernseher: genau ein Sprung an die gewählte Stelle. */
fun PlayerViewModel.seekTo(targetMs: Long) {
    val engine = engine.value ?: return
    seekBy(targetMs - engine.positionMs)
}

fun PlayerViewModel.toggleFavorite() {
    val channel = ui.value.playable?.channel ?: return
    scope.launch { container.playbackRepository.toggleFavorite(ContentType.LIVE, channel.id) }
}

/** Manueller Quellen-Wechsel ("Quelle 2/3 · FHD"). */
fun PlayerViewModel.selectVariant(index: Int) {
    val playable = ui.value.playable ?: return
    if (index == playable.variantIndex) return
    guard?.onUserInterrupt()
    scope.launch { switchVariant(playable, index) }
}
