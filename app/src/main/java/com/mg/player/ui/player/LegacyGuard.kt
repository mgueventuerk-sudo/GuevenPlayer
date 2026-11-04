package com.mg.player.ui.player

import com.mg.player.core.log.AppLog
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.ErrorKind
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.VideoPlayerEngine
import kotlinx.coroutines.flow.update

/**
 * Bisherige Fehlerbehandlung (bis 3.14) als Rückweg: Neuversuche ([PlaybackRecovery]), Quellenwechsel bei Live
 * ([LiveFailover]), Standbild ([FreezeWatchdog]) und einmaliger Wechsel zu LibVLC bei nicht abspielbarem Format
 * ([EngineFallback]). Verhalten unverändert, nur aus dem ViewModel herausgelöst.
 */
internal class LegacyGuard(private val vm: PlayerViewModel) : PlaybackGuard {
    private val network = vm.container.networkMonitor.status
    private val failover = LiveFailover(vm.scope, vm.container.playback.streamMemory, vm::switchVariant, vm::toast) { network.value.online }
    private val engineFallback = EngineFallback()
    private val freeze = FreezeWatchdog()
    private val recovery = PlaybackRecovery(vm.scope, network, vm::retryCurrent) { state ->
        vm.uiState.update { it.copy(reconnecting = state.reconnecting, waitingForNetwork = state.waitingForNetwork, failure = state.failure) }
        if (state.failure != null) vm.offerOtherAccount()
    }

    override fun onContentStarted(playable: Playable) {
        engineFallback.reset()
        recovery.reset()
    }

    override fun onPlaybackBegun(playable: Playable) {
        if (playable.isLive || playable.vodSources.size > 1) failover.onPrepared(playable) else failover.stop()
    }

    override fun onEnginePrepared() = Unit

    override suspend fun onEngineState(engine: VideoPlayerEngine, state: EngineState, playable: Playable?) {
        val isLive = playable?.isLive == true
        if (state.formatUnsupported && handleUnsupported(playable, state)) return
        when (state.status) {
            PlaybackStatus.READY -> if (state.isPlaying) {
                recovery.onPlaying()
                failover.onPlaying()
            } else {
                recovery.onIdle()
                failover.onIdle()
            }

            PlaybackStatus.BUFFERING -> {
                recovery.onBuffering(isLive)
                if (isLive || playable?.vodSources.orEmpty().size > 1) failover.onBuffering()
            }

            PlaybackStatus.ERROR -> onError(state)

            // Ein Live-Stream "endet" nur, wenn der Server die Verbindung schließt.
            PlaybackStatus.ENDED -> if (isLive) recovery.onError(ErrorKind.NETWORK, null)

            PlaybackStatus.IDLE -> recovery.onIdle()

            PlaybackStatus.HANDED_OFF -> Unit
        }
    }

    private fun onError(state: EngineState) {
        // Nächste Quelle nur, wenn es an der Quelle liegt – bei Netzproblemen dieselbe Quelle erneut versuchen.
        val streamProblem = FailoverPolicy.isStreamProblem(state.errorKind, state.httpCode, network.value.online, recovery.attemptCount)
        val connectionOnly = state.errorKind == ErrorKind.NETWORK && state.httpCode == null
        if (!(streamProblem && failover.onStreamProblem(remember = !connectionOnly))) recovery.onError(state.errorKind, state.httpCode)
    }

    /**
     * Bild oder Ton dieser Quelle kann das Gerät nicht abspielen (z. B. 4K/HEVC ohne passenden Decoder):
     * erst die nächste Stream-Variante, als letzte Möglichkeit LibVLC. true = wird behandelt.
     */
    private fun handleUnsupported(playable: Playable?, state: EngineState): Boolean {
        if (playable?.canSwitchSource == true && (failover.busy || failover.onStreamProblem())) return true
        if (!engineFallback.shouldSwitch(state, playable, vm.uiState.value.engineType)) return false
        AppLog.log("Player", "ExoPlayer: Format nicht unterstützt · ${playable?.title} -> LibVLC")
        vm.toast(EngineFallback.MESSAGE)
        vm.switchEngine(EngineFallback.TARGET, byUser = false)
        return true
    }

    override fun onTick(engine: VideoPlayerEngine) {
        if (freeze.isFrozen(engine.state.value.isPlaying, engine.renderedFrames)) {
            AppLog.log("Player", "Bild steht seit 12 s – verbinde neu")
            vm.retryCurrent()
        }
    }

    override fun onVariantSwitched(playable: Playable) = recovery.reset()

    override fun onEngineSwitched(type: PlayerEngineType) = recovery.reset()

    override fun onUserInterrupt() = recovery.reset()

    override fun stop() {
        recovery.release()
        failover.stop()
    }

    override fun release() = recovery.release()
}
