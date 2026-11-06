package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.ErrorCause
import com.mg.player.player.engine.ErrorKind
import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ablauf des Wiedergabe-Wächters mit virtueller Uhr und aufgezeichneten Maßnahmen (ohne Gerät, ohne Engine). */
class PlaybackSupervisorTest {

    private class Harness(
        live: Boolean = true,
        alternative: Boolean = false,
        engine: PlayerEngineType = PlayerEngineType.MEDIA3,
        provider: String? = "p1",
        content: String = "c1",
        breaker: ProviderBreaker = ProviderBreaker(),
        val memory: InMemoryRecoveryMemory = InMemoryRecoveryMemory(),
        config: SupervisorConfig = SupervisorConfig(),
        private val refuse: Set<Class<out RecoveryStep>> = emptySet(),
    ) {
        var now = 0L
        val steps = mutableListOf<Pair<Long, RecoveryStep>>()
        val log = mutableListOf<String>()
        var session = SessionInfo(content, provider, live, alternative, engine)
        val supervisor: PlaybackSupervisor = PlaybackSupervisor(
            actions = { step, _ ->
                if (refuse.any { it.isInstance(step) }) {
                    false
                } else {
                    steps += now to step
                    if (PREPARING.any { it.isInstance(step) }) prepared()
                    true
                }
            },
            memory = memory,
            breaker = breaker,
            config = config,
            clock = { now },
            log = { log += it },
        )

        init {
            supervisor.start(session)
            prepared()
        }

        fun prepared(): Unit = supervisor.onPrepared()

        fun counted() = steps.filter { it.second !is RecoveryStep.Hint }

        /** [ms] lang alle 500 ms eine Momentaufnahme aus [snapshot] (Zeit seit Beginn dieses Aufrufs). */
        fun run(ms: Long, snapshot: (Long) -> HealthSnapshot?) {
            val start = now
            while (now - start < ms) {
                now += 500
                supervisor.onTick(snapshot(now - start))
            }
        }

        fun error(kind: ErrorKind, http: Int? = null, cause: ErrorCause = ErrorCause.NONE, retryAfter: Long? = null) = supervisor.onEngineState(
            EngineState(status = PlaybackStatus.ERROR, errorKind = kind, httpCode = http, errorCause = cause, retryAfterSec = retryAfter),
        )
    }

    private companion object {
        /** Maßnahmen, nach denen die Engine neu vorbereitet wird. */
        val PREPARING = listOf(
            RecoveryStep.Reload::class.java,
            RecoveryStep.SwitchVariant::class.java,
            RecoveryStep.SafeAudio::class.java,
            RecoveryStep.SafeDecoder::class.java,
            RecoveryStep.SwitchEngine::class.java,
        )
    }

    private fun buffering(bytes: Long = 0) = HealthSnapshot(PlaybackStatus.BUFFERING, false, 0, bytesLoaded = bytes)

    private fun playing(t: Long, frames: Long?, audio: Long?, video: Boolean? = true, sound: Boolean? = true) = HealthSnapshot(
        PlaybackStatus.READY,
        true,
        t,
        bytesLoaded = 1_000_000,
        renderedFrames = frames,
        audioUnits = audio,
        hasVideoTrack = video,
        hasAudioTrack = sound,
    )

    @Test
    fun startWithoutDataSwitchesVariantAfterThreeSecondsWhenAlternativeExists() {
        val h = Harness(alternative = true)
        h.run(2_500) { buffering() }
        assertTrue(h.steps.isEmpty())
        h.run(1_000) { buffering() }
        assertEquals(RecoveryStep.SwitchVariant, h.steps.single().second)
    }

    @Test
    fun startWithoutDataAndWithoutAlternativeReloadsAfterTwelveSeconds() {
        val h = Harness()
        h.run(11_500) { buffering() }
        assertTrue(h.steps.isEmpty())
        h.run(2_500) { buffering() }
        assertEquals(RecoveryStep.Reload(1_000), h.steps.single().second)
    }

    @Test
    fun authRetriesTwiceThenGivesUpWithClearMessage() {
        val h = Harness()
        h.error(ErrorKind.AUTH, 403)
        h.run(2_000) { null }
        h.error(ErrorKind.AUTH, 403)
        h.run(5_500) { null }
        h.error(ErrorKind.AUTH, 403)
        assertEquals(listOf(RecoveryStep.Reload(1_500), RecoveryStep.Reload(5_000)), h.steps.map { it.second })
        val status = h.supervisor.status.value
        assertEquals(Symptom.AUTH, status.failedSymptom)
        assertTrue(status.failure!!.contains("HTTP 403"))
    }

    @Test
    fun rateLimitWaitsForRetryAfter() {
        val h = Harness()
        h.error(ErrorKind.NETWORK, 429, ErrorCause.RATE_LIMITED, retryAfter = 12)
        h.run(11_500) { null }
        assertTrue(h.steps.isEmpty())
        h.run(1_000) { null }
        assertEquals(RecoveryStep.Reload(5_000), h.steps.single().second)
    }

    @Test
    fun audioWithoutVideoTriesVideoTrackThenDecoder() {
        val h = Harness(live = false)
        h.run(7_000) { t -> playing(t, frames = 0, audio = t / 20) }
        assertEquals(RecoveryStep.RefreshVideo, h.steps.single().second)
        assertTrue(h.log.any { it.contains("S5 Ton ohne Bild") })
    }

    @Test
    fun radioStationOnlyGetsAHintOnce() {
        val h = Harness(live = true)
        h.run(30_000) { t -> playing(t, frames = 0, audio = t / 20, video = false) }
        assertEquals(1, h.steps.size)
        assertTrue(h.steps.single().second is RecoveryStep.Hint)
        assertTrue(h.counted().isEmpty())
    }

    @Test
    fun videoWithoutAudioDecodesSoundItselfFirst() {
        val h = Harness(live = false)
        h.run(6_000) { t -> playing(t, frames = t / 40, audio = 0) }
        assertEquals(RecoveryStep.SafeAudio, h.steps.single().second)
    }

    @Test
    fun videoWithoutAudioOnLibVlcSkipsMedia3OnlyStepsAndGivesUp() {
        val h = Harness(live = false, engine = PlayerEngineType.LIBVLC)
        h.run(6_000) { t -> playing(t, frames = t / 40, audio = 0) }
        assertTrue(h.steps.isEmpty())
        assertEquals(Symptom.VIDEO_WITHOUT_AUDIO, h.supervisor.status.value.failedSymptom)
    }

    @Test
    fun silentChannelWithoutAudioTrackIsOnlyAHint() {
        val h = Harness(live = true)
        h.run(20_000) { t -> playing(t, frames = t / 40, audio = null, sound = false) }
        assertTrue(h.counted().isEmpty())
        assertEquals(1, h.steps.size)
    }

    @Test
    fun frozenPictureWithRunningSoundReselectsVideo() {
        val h = Harness(live = false)
        h.run(5_000) { t -> playing(t, frames = t / 40, audio = t / 20) }
        h.run(9_000) { t -> playing(5_000 + t, frames = 125, audio = (5_000 + t) / 20) }
        assertEquals(RecoveryStep.RefreshVideo, h.steps.single().second)
        assertTrue(h.log.any { it.contains("Ton läuft") })
    }

    @Test
    fun stuckPositionOnEngineWithoutFrameCounterIsAStall() {
        val h = Harness(live = false, engine = PlayerEngineType.LIBVLC)
        h.run(3_000) { t -> HealthSnapshot(PlaybackStatus.READY, true, t) }
        h.run(31_000) { HealthSnapshot(PlaybackStatus.READY, true, 3_000) }
        assertEquals(RecoveryStep.Reload(), h.steps.single().second)
    }

    @Test
    fun cooldownKeepsDetectorsQuietAfterAnAction() {
        val h = Harness(live = false)
        h.run(7_000) { t -> playing(t, frames = 0, audio = t / 20) }
        assertEquals(1, h.steps.size)
        h.run(9_000) { t -> playing(t, frames = 0, audio = t / 20) }
        assertEquals(1, h.steps.size)
        h.run(8_000) { t -> playing(t, frames = 0, audio = t / 20) }
        assertEquals(2, h.steps.size)
        assertEquals(RecoveryStep.SafeDecoder, h.steps[1].second)
    }

    @Test
    fun offlineErrorWaitsForNetworkWithoutUsingTheBudget() {
        val h = Harness()
        h.supervisor.onNetwork(online = false, changed = true)
        h.error(ErrorKind.NETWORK)
        assertTrue(h.supervisor.status.value.waitingForNetwork)
        h.run(60_000) { null }
        assertTrue(h.steps.isEmpty())
        h.supervisor.onNetwork(online = true, changed = true)
        h.run(2_000) { null }
        assertEquals(RecoveryStep.Reload(), h.steps.single().second)
        assertEquals(0, h.supervisor.actionCount)
    }

    @Test
    fun afterGivingUpANetworkChangeTriesAgain() {
        val h = Harness()
        repeat(8) {
            h.error(ErrorKind.NETWORK)
            h.run(70_000) { null }
        }
        assertEquals(Symptom.NETWORK, h.supervisor.status.value.failedSymptom)
        val before = h.steps.size
        h.supervisor.onNetwork(online = true, changed = true)
        h.run(2_000) { null }
        assertEquals(before + 1, h.steps.size)
        assertNull(h.supervisor.status.value.failure)
    }

    @Test
    fun stablePlaybackRemembersWhatHelped() {
        val h = Harness(live = false)
        h.run(6_000) { t -> playing(t, frames = t / 40, audio = 0) }
        h.supervisor.onEngineState(EngineState(status = PlaybackStatus.READY, isPlaying = true))
        h.run(31_000) { t -> playing(t, frames = t / 40, audio = t / 20) }
        assertEquals(StartHint(safeAudio = true), h.memory.hintFor("c1"))
        assertTrue(h.log.any { it.contains("behoben") })
    }

    @Test
    fun stablePlaybackRearmsMonitoringAfterTheSupervisorGaveUp() {
        val h = Harness(config = SupervisorConfig(maxActionsPerSession = 1, stablePlayback = 5_000))
        h.error(ErrorKind.AUTH, 403)
        h.run(2_000) { null }
        h.error(ErrorKind.AUTH, 403)
        assertEquals(Symptom.AUTH, h.supervisor.status.value.failedSymptom)

        // Erste Momentaufnahme ist nur die Basis; Stabilität beginnt mit dem ersten gemessenen Fortschritt.
        h.run(6_000) { t -> playing(t, frames = t / 40, audio = t / 20) }

        assertNull(h.supervisor.status.value.failure)
        assertTrue(h.log.any { it.contains("Überwachung nach stabiler Wiedergabe fortgesetzt") })
        h.error(ErrorKind.AUTH, 403)
        assertTrue("Wächter muss nach stabiler Erholung wieder reagieren", h.supervisor.status.value.reconnecting)
        h.run(2_000) { null }
        assertEquals(2, h.steps.size)
    }

    @Test
    fun frozenVideoDoesNotCountAsStableRecoveryEvenWhenPositionAdvances() {
        val h = Harness(config = SupervisorConfig(maxActionsPerSession = 0, stablePlayback = 5_000, frozenPicture = 2_000))
        h.error(ErrorKind.AUTH, 403)

        h.run(10_000) { t ->
            HealthSnapshot(
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = t,
                renderedFrames = 0,
                audioUnits = t / 20,
                hasVideoTrack = true,
            )
        }

        assertEquals(Symptom.AUTH, h.supervisor.status.value.failedSymptom)
        assertTrue(h.log.none { it.contains("Überwachung nach stabiler Wiedergabe fortgesetzt") })
    }

    @Test
    fun repeatedFailureAfterGivingUpIsLoggedOnceWithoutRetrying() {
        val h = Harness(config = SupervisorConfig(maxActionsPerSession = 0))
        h.error(ErrorKind.AUTH, 403)

        repeat(3) { h.error(ErrorKind.AUTH, 403) }

        assertEquals(0, h.steps.size)
        assertEquals(1, h.log.count { it.contains("erneute Störung") })
    }

    @Test
    fun userInterruptRearmsStallDetectionForTheSameStream() {
        val h = Harness(config = SupervisorConfig(maxActionsPerSession = 0, frozenPicture = 1_000))
        val frozen = HealthSnapshot(PlaybackStatus.READY, true, 1_000, renderedFrames = 0, hasVideoTrack = true)
        h.run(2_000) { frozen }
        assertEquals(Symptom.FROZEN_PICTURE, h.supervisor.status.value.failedSymptom)

        h.supervisor.onUserInterrupt()
        h.run(2_000) { frozen }

        assertEquals(2, h.log.count { it.contains("Wächter: S4 Standbild") })
    }

    @Test
    fun readyStateDoesNotClaimRecoveryUntilPlaybackProgressIsMeasured() {
        val h = Harness(config = SupervisorConfig(recoveryConfirmation = 2_000))
        h.error(ErrorKind.NETWORK)
        h.run(1_500) { null }
        h.supervisor.onEngineState(EngineState(status = PlaybackStatus.READY, isPlaying = true))

        assertTrue(h.log.none { it.contains("behoben") })

        h.run(3_000) { t -> playing(t, frames = t / 40, audio = t / 20) }

        assertTrue(h.log.any { it.contains("behoben") })
    }

    @Test
    fun videoProgressAloneDoesNotConfirmRecoveryWhileAudioCounterIsAvailable() {
        val h = Harness(config = SupervisorConfig(recoveryConfirmation = 1_000))
        h.error(ErrorKind.NETWORK)
        h.run(1_500) { null }
        h.run(2_000) { t -> playing(t, frames = t / 40, audio = 0) }

        assertTrue(h.log.none { it.contains("behoben") })

        h.run(2_000) { t -> playing(t + 2_000, frames = t / 40 + 100, audio = t / 20) }

        assertTrue(h.log.any { it.contains("behoben") })
    }

    @Test
    fun playbackMonitoringCanRestartAfterItsSessionWasStopped() {
        val h = Harness(
            alternative = true,
            config = SupervisorConfig(stallWithAlternative = 1_000, stallWithAlternativeMax = 1_000),
        )
        h.supervisor.stop()
        h.supervisor.start(h.session)
        h.prepared()
        h.run(1_000) { t -> playing(t, frames = t / 40, audio = t / 20) }
        h.run(2_000) { HealthSnapshot(PlaybackStatus.BUFFERING, false, 1_000, bytesLoaded = 1_000) }

        assertEquals(RecoveryStep.SwitchVariant, h.steps.single().second)
    }

    @Test
    fun devicePressureDoesNotSwitchToAnUnmeasuredAlternateQuality() {
        val h = Harness(alternative = true)

        h.supervisor.onDevicePressure()

        assertTrue(h.steps.single().second is RecoveryStep.Hint)
    }

    @Test
    fun providerBreakerStopsAfterThreeChannelsFailWithinTwoMinutes() {
        val breaker = ProviderBreaker()
        listOf("a", "b").forEach { content ->
            Harness(content = content, breaker = breaker).error(ErrorKind.AUTH, 403)
        }
        val third = Harness(content = "c", breaker = breaker)
        third.error(ErrorKind.AUTH, 403)
        assertTrue(third.steps.isEmpty())
        assertTrue(third.supervisor.status.value.failure!!.contains("mehreren Sendern"))
    }

    @Test
    fun userInterruptCancelsPendingAction() {
        val h = Harness()
        h.error(ErrorKind.AUTH, 403)
        h.supervisor.onUserInterrupt()
        h.run(10_000) { null }
        assertTrue(h.steps.isEmpty())
    }

    @Test
    fun unsupportedFormatOnLiveSwitchesSourceAndOnVodSwitchesEngine() {
        val live = Harness(alternative = true)
        live.supervisor.onEngineState(EngineState(status = PlaybackStatus.READY, formatUnsupported = true))
        assertEquals(RecoveryStep.SwitchVariant, live.steps.single().second)
        val vod = Harness(live = false)
        vod.supervisor.onEngineState(EngineState(status = PlaybackStatus.READY, formatUnsupported = true))
        assertEquals(RecoveryStep.SwitchEngine(PlayerEngineType.LIBVLC), vod.steps.single().second)
    }

    @Test
    fun refusedStepFallsThroughToNextStep() {
        val h = Harness(live = false, refuse = setOf(RecoveryStep.RefreshVideo::class.java))
        h.run(7_000) { t -> playing(t, frames = 0, audio = t / 20) }
        assertEquals(RecoveryStep.SafeDecoder, h.steps.single().second)
    }

    @Test
    fun decoderErrorStartsWithSafeDecoder() {
        val h = Harness(live = false)
        h.error(ErrorKind.FORMAT, cause = ErrorCause.DECODER)
        assertEquals(RecoveryStep.SafeDecoder, h.steps.single().second)
    }

    /**
     * Eigenschaft: Egal in welcher Reihenfolge Fehler, Hänger und Zustände kommen – nie mehr als das Budget je
     * Inhalt, nie mehr als 3 Maßnahmen in einer Minute.
     */
    @Test
    fun randomEventStormsNeverExceedTheBudget() {
        repeat(300) { seed ->
            val random = Random(seed)
            val h = Harness(live = random.nextBoolean(), alternative = random.nextBoolean())
            var t = 0L
            repeat(600) {
                when (random.nextInt(10)) {
                    0 -> h.error(ErrorKind.entries.random(random), listOf(null, 403, 404, 429, 500).random(random))
                    1 -> h.supervisor.onEngineState(EngineState(status = PlaybackStatus.BUFFERING))
                    2 -> h.supervisor.onEngineState(EngineState(status = PlaybackStatus.READY, formatUnsupported = random.nextBoolean()))
                    3 -> h.supervisor.onNetwork(random.nextBoolean(), random.nextBoolean())
                    else -> Unit
                }
                t += 500
                h.now += 500
                h.supervisor.onTick(
                    when (random.nextInt(4)) {
                        0 -> buffering(random.nextLong(0, 10))
                        1 -> playing(t, frames = random.nextLong(0, 3), audio = random.nextLong(0, 3))
                        2 -> HealthSnapshot(PlaybackStatus.READY, true, 0)
                        else -> null
                    },
                )
            }
            val counted = h.counted().map { it.first }
            counted.forEach { at -> assertTrue("seed $seed", counted.count { it in at until at + 60_000 } <= 3) }
            assertNotNull(h.supervisor.status.value)
        }
    }
}
