package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.ErrorCause
import com.mg.player.player.engine.ErrorKind
import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-Attrappe mit Störungs-Drehbüchern (W5): Ein simulierter Stream reagiert auf die Maßnahmen des Wächters
 * wie eine echte Engine (Variante, Engine, Decoder, Ton). Geprüft wird der ganze Weg „Störung → Maßnahme →
 * läuft wieder stabil“ – ohne Gerät. Was nur am Gerät geht (echte Decoder, echte Anbieter), steht in der Prüfliste.
 */
class RecoveryScenarioTest {

    /** Wie sich der simulierte Stream unter einer Einstellung verhält. */
    private data class Behaviour(
        val data: Boolean = true,
        val ready: Boolean = true,
        val picture: Boolean = true,
        val sound: Boolean = true,
        val error: EngineState? = null,
        val freezesAfterMs: Long? = null,
        val stallsAfterMs: Long? = null,
        val endsAfterMs: Long? = null,
        val driftsAfterMs: Long? = null,
    )

    private data class Config(
        val variant: Int = 0,
        val engine: PlayerEngineType = PlayerEngineType.MEDIA3,
        val safeDecoder: Boolean = false,
        val safeAudio: Boolean = false,
    )

    /** Simulierter Player: [behaviour] liefert je Einstellung das Verhalten, [variants] Anzahl Quellen. */
    private class FakeStream(val live: Boolean, val variants: Int = 1, val behaviour: (Config, attempt: Int) -> Behaviour) {
        var now = 0L
        var config = Config()
        var attempt = 0
        private var startedAt = 0L
        private var current = Behaviour()
        private var refreshed = false
        val steps = mutableListOf<RecoveryStep>()
        val memory = InMemoryRecoveryMemory()
        lateinit var supervisor: PlaybackSupervisor
        var online = true

        fun start(hint: StartHint = StartHint()) {
            config = Config(engine = hint.engine ?: PlayerEngineType.MEDIA3, safeDecoder = hint.safeDecoder, safeAudio = hint.safeAudio)
            supervisor = PlaybackSupervisor(::execute, memory, ProviderBreaker(), clock = { now })
            supervisor.start(session(), hint)
            prepare()
        }

        private fun session() = SessionInfo("sender", "anbieter", live, config.variant < variants - 1, config.engine)

        private fun prepare() {
            attempt++
            startedAt = now
            refreshed = false
            current = behaviour(config, attempt)
            supervisor.onPrepared()
            current.error?.let { supervisor.onEngineState(it) }
        }

        val symptoms = mutableListOf<Symptom>()

        private fun execute(step: RecoveryStep, symptom: Symptom): Boolean {
            steps += step
            symptoms += symptom
            when (step) {
                is RecoveryStep.Reload -> prepare()

                RecoveryStep.SwitchVariant -> {
                    if (config.variant >= variants - 1) return false
                    config = config.copy(variant = config.variant + 1)
                    supervisor.updateSession(session())
                    prepare()
                }

                RecoveryStep.SafeDecoder -> {
                    config = config.copy(safeDecoder = true)
                    prepare()
                }

                RecoveryStep.SafeAudio -> {
                    config = config.copy(safeAudio = true)
                    prepare()
                }

                is RecoveryStep.SwitchEngine -> {
                    config = config.copy(engine = step.target)
                    supervisor.updateSession(session())
                    prepare()
                }

                RecoveryStep.SeekToLive -> startedAt = now

                RecoveryStep.RefreshVideo -> refreshed = true

                RecoveryStep.RefreshAudio -> Unit

                is RecoveryStep.Hint, RecoveryStep.GiveUp -> Unit
            }
            return true
        }

        /** Momentaufnahme der simulierten Engine (Drehbuch: bewusst eine Fallunterscheidung an einer Stelle). */
        @Suppress("CyclomaticComplexMethod")
        private fun snapshot(): HealthSnapshot {
            val t = now - startedAt
            val b = current
            val frames = config.engine == PlayerEngineType.MEDIA3
            return when {
                b.error != null -> HealthSnapshot(PlaybackStatus.ERROR, false, 0)

                !b.data -> HealthSnapshot(PlaybackStatus.BUFFERING, false, 0, bytesLoaded = 0)

                !b.ready || t < 800 -> HealthSnapshot(PlaybackStatus.BUFFERING, false, 0, bytesLoaded = t * 100)

                b.stallsAfterMs != null && t > b.stallsAfterMs -> HealthSnapshot(PlaybackStatus.BUFFERING, false, b.stallsAfterMs, bytesLoaded = t * 100)

                else -> {
                    val frozen = b.freezesAfterMs != null && t > b.freezesAfterMs && !refreshed
                    val pictureT = if (frozen) b.freezesAfterMs!! else t
                    HealthSnapshot(
                        status = PlaybackStatus.READY,
                        isPlaying = true,
                        positionMs = t,
                        bytesLoaded = t * 100,
                        renderedFrames = if (!frames) {
                            null
                        } else if (b.picture) {
                            pictureT / 40
                        } else {
                            0
                        },
                        audioUnits = if (b.sound) t / 20 else 0,
                        hasVideoTrack = true,
                        hasAudioTrack = true,
                        liveOffsetMs = if (live && b.driftsAfterMs != null && t > b.driftsAfterMs) 60_000 else 5_000,
                    )
                }
            }
        }

        private fun endsNow(b: Behaviour, s: HealthSnapshot): Boolean {
            val endsAt = b.endsAfterMs ?: return false
            return live && now - startedAt > endsAt && s.status == PlaybackStatus.READY
        }

        fun run(ms: Long) {
            val end = now + ms
            var wasPlaying = false
            while (now < end) {
                now += 500
                val s = snapshot()
                val b = current
                if (endsNow(b, s)) {
                    current = b.copy(endsAfterMs = null, data = false)
                    supervisor.onEngineState(EngineState(status = PlaybackStatus.ENDED))
                }
                val playing = s.status == PlaybackStatus.READY && s.isPlaying
                if (playing && !wasPlaying) supervisor.onEngineState(EngineState(status = PlaybackStatus.READY, isPlaying = true))
                wasPlaying = playing
                supervisor.onTick(if (online) s else s.copy(status = PlaybackStatus.BUFFERING, isPlaying = false))
            }
        }

        val playingFine: Boolean get() = snapshot().let { it.status == PlaybackStatus.READY && (it.renderedFrames ?: 1) > 0 && (it.audioUnits ?: 1) > 0 }
    }

    private fun error(kind: ErrorKind, http: Int? = null, cause: ErrorCause = ErrorCause.NONE, retryAfter: Long? = null) =
        EngineState(status = PlaybackStatus.ERROR, errorKind = kind, httpCode = http, errorCause = cause, retryAfterSec = retryAfter)

    @Test
    fun s1DeadPrimaryFallsBackToSecondSource() {
        val stream = FakeStream(live = true, variants = 2) { c, _ -> Behaviour(data = c.variant > 0) }
        stream.start()
        stream.run(40_000)
        assertEquals(listOf(RecoveryStep.SwitchVariant), stream.steps)
        assertTrue(stream.playingFine)
    }

    @Test
    fun s2DataButNeverReadyHelpedBySafeDecoderAndRemembered() {
        val stream = FakeStream(live = false) { c, _ -> Behaviour(ready = c.safeDecoder) }
        stream.start()
        stream.run(60_000)
        assertEquals(listOf(RecoveryStep.Reload(), RecoveryStep.SafeDecoder), stream.steps)
        assertTrue(stream.playingFine)
        assertEquals(StartHint(safeDecoder = true), stream.memory.hintFor("sender"))
    }

    @Test
    fun s3StallMidPlaybackIsFixedByReload() {
        val stream = FakeStream(live = false) { _, attempt -> Behaviour(stallsAfterMs = if (attempt == 1) 20_000 else null) }
        stream.start()
        stream.run(90_000)
        assertEquals(listOf(RecoveryStep.Reload()), stream.steps)
        assertTrue(stream.playingFine)
        assertNull(stream.supervisor.status.value.failure)
    }

    @Test
    fun s4FrozenPictureFixedByReselectingVideo() {
        val stream = FakeStream(live = false) { _, _ -> Behaviour(freezesAfterMs = 10_000) }
        stream.start()
        stream.run(60_000)
        assertEquals(listOf(RecoveryStep.RefreshVideo), stream.steps)
        assertTrue(stream.playingFine)
    }

    @Test
    fun s5SoundWithoutPictureFixedBySoftwareDecoder() {
        val stream = FakeStream(live = false) { c, _ -> Behaviour(picture = c.safeDecoder) }
        stream.start()
        stream.run(60_000)
        assertEquals(listOf(RecoveryStep.RefreshVideo, RecoveryStep.SafeDecoder), stream.steps)
        assertTrue(stream.playingFine)
    }

    @Test
    fun s6PictureWithoutSoundFixedByDecodingSoundAndNextStartUsesIt() {
        val stream = FakeStream(live = true) { c, _ -> Behaviour(sound = c.safeAudio) }
        stream.start()
        stream.run(60_000)
        assertEquals(listOf(RecoveryStep.SafeAudio), stream.steps)
        val hint = stream.memory.hintFor("sender")
        assertEquals(StartHint(safeAudio = true), hint)
        // Nächster Start desselben Senders: gleich richtig, keine Maßnahme nötig.
        stream.steps.clear()
        stream.start(hint!!)
        stream.run(60_000)
        assertTrue(stream.steps.isEmpty())
        assertTrue(stream.playingFine)
    }

    @Test
    fun s9DriftingLiveStreamJumpsToLiveEdge() {
        val stream = FakeStream(live = true) { _, _ -> Behaviour(driftsAfterMs = 20_000) }
        stream.start()
        stream.run(40_000)
        assertEquals(RecoveryStep.SeekToLive, stream.steps.first())
    }

    @Test
    fun s11EndedLiveStreamReconnects() {
        val stream = FakeStream(live = true) { _, attempt -> Behaviour(endsAfterMs = if (attempt == 1) 15_000 else null) }
        stream.start()
        stream.run(60_000)
        assertEquals(RecoveryStep.Reload(1_000), stream.steps.first())
        assertTrue(stream.playingFine)
    }

    @Test
    fun s12PersistentForbiddenGivesUpAfterTwoTriesWithMessage() {
        val stream = FakeStream(live = true) { _, _ -> Behaviour(error = error(ErrorKind.AUTH, 403)) }
        stream.start()
        stream.run(60_000)
        assertEquals(2, stream.steps.size)
        assertTrue(stream.supervisor.status.value.failure!!.contains("Zugriff verweigert"))
    }

    @Test
    fun s13NotFoundWithAlternativeSwitches() {
        val stream = FakeStream(live = true, variants = 2) { c, _ -> if (c.variant == 0) Behaviour(error = error(ErrorKind.NOT_FOUND, 404)) else Behaviour() }
        stream.start()
        stream.run(40_000)
        assertEquals(listOf(RecoveryStep.SwitchVariant), stream.steps)
        assertTrue(stream.playingFine)
    }

    @Test
    fun s14RateLimitedRespectsRetryAfter() {
        val stream = FakeStream(live = false) { _, attempt ->
            if (attempt == 1) Behaviour(error = error(ErrorKind.NETWORK, 429, ErrorCause.RATE_LIMITED, retryAfter = 20)) else Behaviour()
        }
        stream.start()
        stream.run(19_000)
        assertTrue(stream.steps.isEmpty())
        stream.run(30_000)
        assertEquals(1, stream.steps.size)
        assertTrue(stream.playingFine)
    }

    @Test
    fun unsupportedFormatOnVodSwitchesToLibVlc() {
        val stream = FakeStream(live = false) { c, _ ->
            if (c.engine == PlayerEngineType.MEDIA3) Behaviour(error = error(ErrorKind.FORMAT, cause = ErrorCause.CONTAINER)) else Behaviour()
        }
        stream.start()
        stream.run(30_000)
        assertEquals(listOf(RecoveryStep.SwitchEngine(PlayerEngineType.LIBVLC)), stream.steps)
        assertTrue(stream.playingFine)
    }

    @Test
    fun networkOutageWaitsAndResumesWithoutUsingBudget() {
        val stream = FakeStream(live = true) { _, _ -> Behaviour() }
        stream.start()
        stream.run(10_000)
        stream.online = false
        stream.supervisor.onNetwork(online = false, changed = true)
        stream.supervisor.onEngineState(error(ErrorKind.NETWORK))
        stream.run(120_000)
        assertTrue(stream.steps.isEmpty())
        stream.online = true
        stream.supervisor.onNetwork(online = true, changed = true)
        stream.run(30_000)
        assertEquals(RecoveryStep.Reload(), stream.steps.first())
        assertEquals(0, stream.supervisor.actionCount)
    }

    @Test
    fun hopelessStreamEndsWithMessageNotEndlessLoop() {
        val stream = FakeStream(live = true, variants = 3) { _, _ -> Behaviour(data = false) }
        stream.start()
        stream.run(30 * 60_000)
        assertTrue(stream.steps.size <= SupervisorConfig().maxActionsPerSession)
        assertTrue(stream.supervisor.status.value.failure != null)
    }
}
