package com.mg.player.ui.player

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import com.mg.player.core.log.AppLog
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.HwDecoding
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.domain.model.playlistIdOf
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.VideoPlayerEngine
import com.mg.player.player.supervisor.EngineHealthSampler
import com.mg.player.player.supervisor.HealthDiagnostics
import com.mg.player.player.supervisor.PlaybackSupervisor
import com.mg.player.player.supervisor.RecoveryStep
import com.mg.player.player.supervisor.SessionInfo
import com.mg.player.player.supervisor.StartHint
import com.mg.player.player.supervisor.Symptom
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Verbindet den [PlaybackSupervisor] mit dem Player: übersetzt seine Maßnahmen in Aufrufe am [PlayerViewModel],
 * zeigt seinen Status an und liefert ihm Netz-, Speicher- und Temperatur-Ereignisse.
 */
internal class SupervisorGuard(private val vm: PlayerViewModel) : PlaybackGuard {

    private val container = vm.container
    private val supervisor = PlaybackSupervisor(
        actions = ::execute,
        memory = container.playback.recoveryMemory,
        breaker = container.playback.providerBreaker,
        clock = System::currentTimeMillis,
        log = { AppLog.log("Player", it) },
    )
    private val healthDiagnostics = HealthDiagnostics()
    private val healthSampler = EngineHealthSampler { AppLog.log("Player", it) }

    /** Nach einem Quellenwechsel durch den Wächter: diese Variante merken, sobald sie läuft (nur bei echten Quellenfehlern). */
    private var rememberVariantOnPlay = false
    private var released = false

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                vm.scope.launch {
                    vm.standby.releaseForDevicePressure()
                    vm.habitPreload.releaseForDevicePressure()
                    supervisor.onDevicePressure()
                }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Ersetzt durch onTrimMemory")
        override fun onLowMemory() = Unit
    }

    private val thermalListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                vm.scope.launch {
                    vm.standby.releaseForDevicePressure()
                    vm.habitPreload.releaseForDevicePressure()
                    supervisor.onDevicePressure()
                }
            }
        }
    } else {
        null
    }

    init {
        container.appContext.registerComponentCallbacks(memoryCallbacks)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.appContext.getSystemService(PowerManager::class.java)
                ?.addThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
        }
        vm.scope.launch {
            supervisor.status.collect { status ->
                vm.uiState.update {
                    it.copy(
                        reconnecting = status.reconnecting,
                        waitingForNetwork = status.waitingForNetwork,
                        failure = status.failure,
                        recoveryText = status.text,
                    )
                }
                if (status.failure != null) vm.offerOtherAccount()
            }
        }
        vm.scope.launch {
            var first = true
            // Nur echte Wechsel zählen (online, WLAN/LAN, VPN) – nicht jede Schwankung der WLAN-Geschwindigkeit.
            container.networkMonitor.status.map { Triple(it.online, it.transport, it.vpnForApp) }.distinctUntilChanged().collect { (online) ->
                supervisor.onNetwork(online, changed = !first)
                first = false
            }
        }
    }

    override fun onContentStarted(playable: Playable) {
        rememberVariantOnPlay = false
        healthDiagnostics.reset()
        val key = contentKey(playable)
        val hint = supervisor.hintFor(key)?.takeUnless { it.isEmpty } ?: StartHint()
        vm.decoderOverride = StartHint(safeDecoder = hint.safeDecoder, safeAudio = hint.safeAudio)
        val engine = vm.uiState.value.engineType
        if (hint.engine != null && hint.engine != engine && engine != PlayerEngineType.EXTERNAL) {
            vm.useFallbackEngine(hint.engine)
        }
        if (!hint.isEmpty) AppLog.log("Player", "Wächter: startet mit dem, was zuletzt geholfen hat (${describe(hint)})")
        supervisor.start(session(playable), hint)
    }

    override fun onPlaybackSessionRestored(playable: Playable) {
        rememberVariantOnPlay = false
        healthDiagnostics.reset()
        supervisor.start(session(playable), vm.decoderOverride)
    }

    override fun onPlaybackBegun(playable: Playable) = Unit

    override fun onEnginePrepared() {
        healthDiagnostics.reset()
        supervisor.onPrepared()
    }

    override suspend fun onEngineState(engine: VideoPlayerEngine, state: EngineState, playable: Playable?) {
        if (rememberVariantOnPlay && state.isPlaying && playable != null) {
            rememberVariantOnPlay = false
            val channel = playable.channel
            val variant = playable.activeVariant
            val source = playable.activeVodSource
            when {
                source != null -> container.playback.streamMemory.remember(Combined.ID, playable.id, source.itemId)
                channel != null && variant != null -> container.playback.streamMemory.remember(channel.playlistId, channel.groupKey, variant.id)
            }
        }
        supervisor.onEngineState(state)
    }

    override fun onTick(engine: VideoPlayerEngine) {
        val health = healthSampler.sample(
            readHealth = { engine.health() },
            readFallback = {
                val state = runCatching { engine.state.value }.getOrNull()
                val position = runCatching { engine.positionMs }.getOrNull()
                if (state == null || position == null) null else HealthSnapshot(
                    status = state.status,
                    isPlaying = state.isPlaying,
                    positionMs = position.coerceAtLeast(0),
                )
            },
        )
        health?.let { snapshot ->
            healthDiagnostics.sample(vm.uiState.value.engineType, snapshot, System.currentTimeMillis())
                ?.let { AppLog.log("Messung", it) }
        }
        supervisor.onTick(health)
    }

    override fun onVariantSwitched(playable: Playable) {
        healthDiagnostics.reset()
        supervisor.updateSession(session(playable))
    }

    override fun onEngineSwitched(type: PlayerEngineType) {
        healthDiagnostics.reset()
        vm.uiState.value.playable?.let { supervisor.updateSession(session(it)) }
    }

    override fun onUserInterrupt() = supervisor.onUserInterrupt()

    override fun stop() = supervisor.stop()

    override fun release() {
        if (released) return
        released = true
        supervisor.stop()
        container.appContext.unregisterComponentCallbacks(memoryCallbacks)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.appContext.getSystemService(PowerManager::class.java)
                ?.removeThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
        }
    }

    /** Eine Maßnahme des Wächters ausführen. false = hier nicht möglich, der Wächter nimmt die nächste Stufe. */
    private fun execute(step: RecoveryStep, symptom: Symptom): Boolean {
        val playable = vm.uiState.value.playable ?: return false
        val engine = vm.engineHolder.value
        return when (step) {
            is RecoveryStep.Reload -> vm.retryCurrent()

            RecoveryStep.SeekToLive -> engine?.seekToLiveEdge() == true

            RecoveryStep.RefreshVideo -> engine?.refreshTrack(video = true) == true

            RecoveryStep.RefreshAudio -> engine?.refreshTrack(video = false) == true

            RecoveryStep.SwitchVariant -> switchVariant(playable, symptom)

            RecoveryStep.SafeDecoder -> applyOverride(vm.decoderOverride.copy(safeDecoder = true)) {
                vm.settings.hwDecoding != HwDecoding.OFF || vm.settings.tunneling
            }

            RecoveryStep.SafeAudio -> applyOverride(vm.decoderOverride.copy(safeAudio = true)) { vm.settings.audioPassthrough }

            is RecoveryStep.SwitchEngine -> {
                if (vm.uiState.value.engineType == step.target) return false
                vm.toast("${symptom.label} – wechsle automatisch zu ${step.target.label} …")
                vm.switchEngine(step.target, byUser = false)
                true
            }

            is RecoveryStep.Hint -> {
                vm.toast(step.text)
                true
            }

            RecoveryStep.GiveUp -> false
        }
    }

    private fun switchVariant(playable: Playable, symptom: Symptom): Boolean {
        val next = playable.nextSourceIndex() ?: return false
        vm.toast("${symptom.label} – wechsle automatisch zu ${playable.sourceName(next)} …")
        // Bei reinen Verbindungsstörungen nicht dauerhaft auf die schlechtere Quelle wechseln.
        rememberVariantOnPlay = symptom !in TRANSIENT
        vm.scope.launch { vm.switchVariant(playable, next) }
        return true
    }

    /** Neue Decoder-Einstellung nur, wenn sie etwas ändert ([changes]); dann neu verbinden. */
    private inline fun applyOverride(override: StartHint, changes: () -> Boolean): Boolean {
        if (!changes() || override == vm.decoderOverride) return false
        vm.decoderOverride = override
        // Decoder-Problem liegt an Engine/Gerät, nicht an der Quelle: der Neustart zählt nicht als gescheiterter Start.
        vm.sourceHealth.onInterrupted()
        return vm.retryCurrent()
    }

    private fun session(playable: Playable) = SessionInfo(
        contentKey = contentKey(playable),
        providerKey = (playable.activePlaylistId ?: playlistIdOf(playable.id))?.toString(),
        isLive = playable.isLive,
        canSwitchVariant = playable.canSwitchSource,
        engine = vm.uiState.value.engineType,
    )

    private fun contentKey(playable: Playable): String = playable.trackKey() ?: playable.id

    private fun describe(hint: StartHint): String = listOfNotNull(
        hint.engine?.label,
        "sicherer Decoder".takeIf { hint.safeDecoder },
        "Ton selbst dekodieren".takeIf { hint.safeAudio },
    ).joinToString(", ")

    private companion object {
        val TRANSIENT = setOf(Symptom.NETWORK, Symptom.START_NO_DATA, Symptom.STALL, Symptom.LIVE_ENDED, Symptom.DEVICE_PRESSURE, Symptom.RATE_LIMITED)
    }
}
