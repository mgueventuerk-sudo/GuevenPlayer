package com.mg.player.ui.player

import android.os.SystemClock
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.log.AppLog
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.core.vpn.tunnelUps
import com.mg.player.domain.binge.BingeAction
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.domain.model.forAutomaticOptimization
import com.mg.player.domain.model.playlistIdOf
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.supervisor.StartHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Start, Inhaltswechsel und Takt des Players (Teil von PlayerViewModel).

private const val ACTIVE_TICK_MS = 500L
private const val STEADY_TICK_MS = 1_000L
private const val OWN_USE_HEARTBEAT_MS = 30_000L
private const val SLOW_TICK_DIAGNOSTIC_MS = 2_000L
private const val TICK_LOG_INTERVAL_MS = 30_000L

/** Einstellungen laden, Inhalt auflösen, Engine wählen (ggf. aus der Vorschau übernehmen), starten; danach Takt. */
/**
 * Eigene Nutzung alle 30 s auffrischen: Auch nach langer Wiedergabe (oder Absturz) weiß die App, dass die belegte
 * Verbindung dieses Gerät ist. Liefert den Zeitpunkt der letzten Meldung.
 */
private fun PlayerViewModel.refreshOwnUse(lastMarkedAt: Long): Long {
    val now = System.currentTimeMillis()
    if (now - lastMarkedAt < OWN_USE_HEARTBEAT_MS) return lastMarkedAt
    uiState.value.playable?.let { it.activePlaylistId ?: playlistIdOf(it.id) }?.let(container.connectionMonitor::markOwnUse)
    return now
}

internal fun PlayerViewModel.startPlayer() {
    scope.launch {
        try {
            settings = container.settingsRepository.current().forAutomaticOptimization(DeviceTier.of(container.appContext))
            guard = if (settings.playbackSupervisor) SupervisorGuard(this@startPlayer) else LegacyGuard(this@startPlayer)
            val resolved = resolver.resolve(request)
            if (resolved == null) {
                uiState.update { it.copy(loadError = request.notFoundMessage()) }
                return@launch
            }
            val playable = confirmStart(resolved) ?: return@launch
            startEngineFor(playable)
            loadChannelList(playable, sameAccount = playable === resolved)
            startPlayable(playable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unerwarteter Fehler beim Auflösen: Meldung statt App-Absturz.
            AppLog.error("Player", "Start fehlgeschlagen", e)
            uiState.update { it.copy(loadError = "Wiedergabe konnte nicht gestartet werden (${e.javaClass.simpleName})") }
            return@launch
        }
        var ownUseMarkedAt = 0L
        var lastTickAt = SystemClock.elapsedRealtime()
        var lastTickDelayLoggedAt = -TICK_LOG_INTERVAL_MS
        var lastTickErrorLoggedAt = -TICK_LOG_INTERVAL_MS
        while (true) {
            try {
                ownUseMarkedAt = refreshOwnUse(ownUseMarkedAt)
                delay(tickDelayMs())
                val now = SystemClock.elapsedRealtime()
                val tickDelay = now - lastTickAt
                if (tickDelay >= SLOW_TICK_DIAGNOSTIC_MS && now - lastTickDelayLoggedAt >= TICK_LOG_INTERVAL_MS) {
                    AppLog.log("Player", "Prüftakt verspätet: ${tickDelay} ms")
                    lastTickDelayLoggedAt = now
                }
                lastTickAt = now
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTickErrorLoggedAt >= TICK_LOG_INTERVAL_MS) {
                    AppLog.error("Player", "Prüftakt fehlgeschlagen – Überwachung versucht es erneut", e)
                    lastTickErrorLoggedAt = now
                }
                lastTickAt = now
                delay(STEADY_TICK_MS)
            }
        }
    }
    // VPN steht (wieder): Stream neu verbinden, damit er durch den Tunnel läuft statt über die normale Leitung.
    scope.launch {
        container.vpn.tunnelUps().collect {
            if (stoppedByLifecycle) return@collect
            // Neu verbinden wegen des Tunnels, nicht wegen der Quelle: kein Strafpunkt für Start oder Hänger.
            sourceHealth.onInterrupted()
            retryCurrent()
        }
    }
}

/** Derselbe Sender lief eben in der Vorschau: Engine übernehmen, damit der Stream nicht neu lädt. */
private suspend fun PlayerViewModel.startEngineFor(playable: Playable) {
    preferredEngine = EngineFallback.initialEngine(request, playable, settings)
    val handed = playable.channel?.takeIf { playable.kind == PlayableKind.LIVE }
        ?.let { container.playback.previewHandoff.take(it.id, playable.url, preferredEngine) }
    if (handed != null) adoptEngine(handed, preferredEngine) else createEngine(preferredEngine)
}

/** Senderliste aus dem Zugang des Senders – er kann aus der Suche über alle Zugänge stammen. */
private suspend fun PlayerViewModel.loadChannelList(playable: Playable, sameAccount: Boolean) {
    val listPlaylist = playable.channel?.playlistId?.takeIf { it != 0L } ?: settings.activePlaylistId
    when (val r = request) {
        // Anderer Zugang gewählt: dessen Liste „Alle“ (die Kategorie-ID gehört zum ersten Zugang).
        is PlayRequest.Live -> live.loadChannelList(listPlaylist, if (sameAccount) r.categoryId else Category.ALL)

        is PlayRequest.Catchup -> live.loadChannelList(listPlaylist, r.categoryId)

        else -> Unit
    }
}

internal suspend fun PlayerViewModel.startPlayable(playable: Playable) {
    if (!switchingAccount) accounts.resetOffers()
    switchingAccount = false
    (playable.activePlaylistId ?: playlistIdOf(playable.id))?.let { playlistId ->
        container.connectionMonitor.markOwnUse(playlistId)
        // Vorfahrt fürs Zuschauen: Prüft das Prüfgerät gerade über diesen Zugang, gibt es ihn sofort frei (Spiel-Finder O).
        scope.launch { runCatchingCancellable { container.sport.releaseForViewing(playlistId) } }
    }
    bingeSession.reset()
    frameRate.cancelPending()
    // War für den letzten Inhalt eine andere Engine als Notlösung aktiv: der neue startet wieder mit der eingestellten.
    restorePreferredEngine()
    decoderOverride = StartHint()
    guard?.onContentStarted(playable)
    lastKnownPosition = playable.startPositionMs
    positionState.value = PlaybackPosition(playable.startPositionMs, 0)
    uiState.update { it.copy(playable = playable, nextEpisode = null) }
    beginPlayback(playable, playable.startPositionMs)
    when (playable.kind) {
        PlayableKind.LIVE -> live.onChannelStarted(playable, (request as? PlayRequest.Live)?.categoryId)

        PlayableKind.EPISODE -> {
            val next = bingeSession.setup(playable, settings)
            uiState.update { it.copy(nextEpisode = next) }
            engineHolder.value?.let { if (it.state.value.status == PlaybackStatus.READY) bingeSession.onReady(it) }
        }

        PlayableKind.CATCHUP -> playable.channel?.let { live.observeChannel(it) }

        PlayableKind.MOVIE, PlayableKind.RECORDING -> Unit
    }
}

/** Film/Folge zu Ende: nächste Folge oder schließen (Live behandelt der Wächter). */
internal suspend fun PlayerViewModel.onEnded() {
    val playable = uiState.value.playable ?: return
    if (playable.kind == PlayableKind.EPISODE && bingeSession.shouldPlayNextOnEnd() && uiState.value.nextEpisode != null) {
        playNextEpisode()
        return
    }
    progress.markWatched(playable, durationMs())
    eventSink.emit(PlayerEvent.Close)
}

/**
 * Takt des Players: 1 s bei ruhiger Wiedergabe, sonst 500 ms. Live mit Ersatz-Variante immer 500 ms – sonst käme ein
 * Hänger bis zu 1 s zu spät beim Wächter an (3-s-Grenze, ab 3.51).
 */
private fun PlayerViewModel.tickDelayMs(): Long {
    val state = engineHolder.value?.state?.value
    val stablePlayback = state?.let { it.status == PlaybackStatus.READY && it.isPlaying } == true &&
        !uiState.value.reconnecting && !uiState.value.waitingForNetwork
    val quickWatch = uiState.value.playable?.let { it.isLive && it.canSwitchSource } == true
    return if (stablePlayback && !quickWatch) STEADY_TICK_MS else ACTIVE_TICK_MS
}

private suspend fun PlayerViewModel.tick() {
    val engine = engineHolder.value ?: return
    val playable = uiState.value.playable ?: return
    if (stoppedByLifecycle) return
    if (!live.zapPending && uiState.value.engineType != PlayerEngineType.EXTERNAL) {
        guard?.onTick(engine)
        sourceHealth.onTick()
        watchProof.onTick(playable.activeVariant.takeIf { playable.isLive }, engine.state.value.isPlaying)
    }
    if (playable.isLive || uiState.value.engineType == PlayerEngineType.EXTERNAL) return
    val pos = engine.positionMs
    val dur = engine.durationMs
    if (engine.state.value.status == PlaybackStatus.READY && pos > 0) lastKnownPosition = pos
    positionState.value = PlaybackPosition(pos, dur)
    when (val action = bingeSession.tick(pos, dur)) {
        is BingeAction.SeekTo -> engine.seekTo(action.positionMs)
        BingeAction.PlayNext -> playNextEpisode()
        null -> Unit
    }
    progress.maybeSave(playable, pos, dur, engine.state.value.isPlaying)
}

internal fun PlayerViewModel.durationMs(): Long = engineHolder.value?.durationMs?.takeIf { it > 0 } ?: positionState.value.durationMs

/** Nahtloser Übergang zur nächsten Folge (inkl. Staffelwechsel), ohne Rückkehr ins Menü. */
fun PlayerViewModel.playNextEpisode() {
    if (switchingEpisode) return
    val current = uiState.value.playable ?: return
    val next = uiState.value.nextEpisode ?: return
    switchingEpisode = true
    scope.launch {
        progress.markWatched(current, durationMs())
        startPlayable(resolver.episode(next, 0))
        switchingEpisode = false
    }
}

/** Nach einem Sprung sofort die neue Position zeigen (nicht erst beim nächsten Takt). */
internal fun PlayerViewModel.showPosition(positionMs: Long) = positionState.update { it.copy(positionMs = positionMs) }
