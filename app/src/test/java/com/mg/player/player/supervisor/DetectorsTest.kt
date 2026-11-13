package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detektoren einzeln, vor allem die Fälle, in denen sie NICHT anschlagen dürfen (Fehlalarm wäre schlimmer als
 * das Problem: unnötiges Umschalten, belegte Anbieter-Verbindungen).
 */
class DetectorsTest {

    private val config = SupervisorConfig()
    private fun ctx(
        live: Boolean = true,
        alternative: Boolean = false,
        engine: PlayerEngineType = PlayerEngineType.MEDIA3,
        picture: PictureExpectation = PictureExpectation.UNKNOWN,
    ) = DetectContext(SessionInfo("c", "p", live, alternative, engine), preparedAtMs = 0, config = config, picture = picture)

    /** Füttert [detector] [ms] lang alle 500 ms und liefert alle Befunde. */
    private fun feed(detector: Detector, ms: Long, ctx: DetectContext = ctx(), from: Long = 0, snapshot: (Long) -> HealthSnapshot): List<Finding> {
        val findings = mutableListOf<Finding>()
        var t = from
        while (t < from + ms) {
            t += 500
            detector.check(snapshot(t), t, ctx)?.let(findings::add)
        }
        return findings
    }

    private fun playing(t: Long, frames: Long? = t / 40, audio: Long? = t / 20, video: Boolean? = true, sound: Boolean? = true, dropped: Long? = 0) =
        HealthSnapshot(PlaybackStatus.READY, true, t, 1_000, frames, audio, video, sound, droppedFrames = dropped)

    @Test
    fun healthyPlaybackTriggersNothingForTenMinutes() {
        val detectors =
            listOf(StartDetector(), StallDetector(), FrozenPictureDetector(), AvPresenceDetector(), DesyncDetector(), JudderDetector(), LiveDriftDetector())
        detectors.forEach { assertTrue(it.javaClass.simpleName, feed(it, 600_000) { t -> playing(t) }.isEmpty()) }
    }

    @Test
    fun pausedPlayerIsNeverStalledOrFrozen() {
        val paused = { t: Long -> HealthSnapshot(PlaybackStatus.READY, false, 5_000, 1_000, 100, 200, true, true) }
        listOf(StallDetector(), FrozenPictureDetector(), AvPresenceDetector()).forEach { detector ->
            feed(detector, 3_000) { t -> playing(t) }
            assertTrue(feed(detector, 300_000, from = 3_000, snapshot = paused).isEmpty())
        }
    }

    @Test
    fun shortBufferingWithoutAlternativeIsNoStall() {
        val detector = StallDetector()
        feed(detector, 2_000) { t -> playing(t) }
        val findings = (0 until 20).flatMap { round ->
            val base = 2_000 + round * 6_000L
            feed(detector, 2_000, from = base) { HealthSnapshot(PlaybackStatus.BUFFERING, false, base) } +
                feed(detector, 4_000, from = base + 2_000) { t -> playing(t) }
        }
        assertTrue(findings.isEmpty())
    }

    @Test
    fun stallWithAlternativeTriggersWhenNothingArrives() {
        val detector = StallDetector()
        feed(detector, 2_000) { t -> playing(t) }
        // Puffert und es kommt nichts nach (Datenmenge bleibt stehen).
        val findings = feed(detector, 7_000, ctx(alternative = true), from = 2_000) { HealthSnapshot(PlaybackStatus.BUFFERING, false, 2_000, 1_000) }
        assertEquals(Symptom.STALL, findings.single().symptom)
    }

    /**
     * Ohne Ersatzquelle (nur Neu-Verbinden hilft): Solange Daten nachkommen, ist Puffern kein Hänger – erst nach dem
     * langen Zeitfenster. Fire-TV-Protokoll vom 01.11.: „puffert seit 3001 ms“, danach „behoben nach 0,6 s“.
     */
    @Test
    fun bufferingWithIncomingDataIsNoStallWithoutAlternative() {
        val detector = StallDetector()
        feed(detector, 2_000) { t -> playing(t) }
        val findings = feed(detector, 15_000, from = 2_000) { t ->
            HealthSnapshot(PlaybackStatus.BUFFERING, false, 2_000, bytesLoaded = 1_000 + t * 50)
        }
        assertTrue(findings.isEmpty())
        val later = feed(detector, 15_000, from = 17_000) { t ->
            HealthSnapshot(PlaybackStatus.BUFFERING, false, 2_000, bytesLoaded = 1_000 + t * 50)
        }
        assertEquals(Symptom.STALL, later.single().symptom)
    }

    /** Ab 3.51, Live mit Ersatzquelle: höchstens 2,5 s – auch wenn noch Daten kommen (mit 500-ms-Takt ≤ 3 s). */
    @Test
    fun withAlternativeEveryHangEndsWithinThreeSeconds() {
        val cases = listOf<(Long) -> HealthSnapshot>(
            // Daten kommen nach.
            { t -> HealthSnapshot(PlaybackStatus.BUFFERING, false, 2_000, bytesLoaded = 1_000 + t * 50) },
            // Engine ohne Datenmenge (LibVLC, System-Player).
            { _ -> HealthSnapshot(PlaybackStatus.BUFFERING, false, 2_000, bytesLoaded = null) },
            // „Spielt“, aber die Position steht.
            { _ -> HealthSnapshot(PlaybackStatus.READY, true, 2_000, bytesLoaded = null) },
        )
        cases.forEach { snapshot ->
            val detector = StallDetector()
            feed(detector, 2_000) { t -> playing(t) }
            var firedAt = -1L
            var t = 2_000L
            while (firedAt < 0 && t < 10_000) {
                t += 500
                if (detector.check(snapshot(t), t, ctx(alternative = true)) != null) firedAt = t
            }
            assertTrue("Wechsel nach ${firedAt - 2_000} ms", firedAt in 2_001..5_000)
            assertTrue("Wechsel nach ${firedAt - 2_000} ms", firedAt - 2_000 <= 3_000)
        }
    }

    /** Kurzes Nachladen ist kein Hänger – auch mit Ersatzquelle (2 s mit Daten, 1,5 s ohne). */
    @Test
    fun shortRefillWithAlternativeIsNoStall() {
        val withData = StallDetector()
        feed(withData, 2_000) { t -> playing(t) }
        val a = feed(withData, 2_000, ctx(alternative = true), from = 2_000) { t ->
            HealthSnapshot(PlaybackStatus.BUFFERING, false, 2_000, bytesLoaded = 1_000 + t * 50)
        }
        val withoutData = StallDetector()
        feed(withoutData, 2_000) { t -> playing(t) }
        val b = feed(withoutData, 1_500, ctx(alternative = true), from = 2_000) { HealthSnapshot(PlaybackStatus.BUFFERING, false, 2_000, 9_999) }
        assertTrue(a.isEmpty() && b.isEmpty())
    }

    /** Filme behalten die langen Grenzen (Wechsel ist dort teuer). */
    @Test
    fun vodKeepsTheLongLimit() {
        val detector = StallDetector()
        feed(detector, 2_000, ctx(live = false)) { t -> playing(t) }
        val findings = feed(detector, 10_000, ctx(live = false, alternative = true), from = 2_000) { t ->
            HealthSnapshot(PlaybackStatus.BUFFERING, false, 2_000, bytesLoaded = 1_000 + t * 50)
        }
        assertTrue(findings.isEmpty())
    }

    @Test
    fun soundBeforePictureAtStartIsNotAudioOnlyWithinThreshold() {
        // Viele Sender liefern den Ton 1–3 s vor dem ersten Bild.
        val detector = AvPresenceDetector()
        val early = feed(detector, 3_000) { t -> playing(t, frames = 0) }
        val later = feed(detector, 30_000, from = 3_000) { t -> playing(t, frames = (t - 3_000) / 40) }
        assertTrue(early.isEmpty() && later.isEmpty())
    }

    @Test
    fun audioOnlyWithVideoTrackIsReported() {
        val findings = feed(AvPresenceDetector(), 8_000) { t -> playing(t, frames = 0) }
        assertEquals(Symptom.AUDIO_WITHOUT_VIDEO, findings.single().symptom)
    }

    @Test
    fun unknownCountersNeverGuess() {
        val findings = listOf(FrozenPictureDetector(), AvPresenceDetector(), JudderDetector(), DecoderLagDetector()).flatMap { detector ->
            feed(detector, 120_000) { t -> playing(t, frames = null, audio = null, video = null, sound = null, dropped = null) }
        }
        assertTrue(findings.isEmpty())
    }

    @Test
    fun repeatedNativeVideoDecoderLagEventsAreDetectedWithoutPretendingToHaveFrameCounts() {
        val detector = DecoderLagDetector()
        fun sample(count: Int) = HealthSnapshot(PlaybackStatus.READY, true, 1_000, videoLagEvents = count)

        assertNull(detector.check(sample(0), 0, ctx(engine = PlayerEngineType.NATIVE)))
        assertNull(detector.check(sample(1), 1_000, ctx(engine = PlayerEngineType.NATIVE)))
        val finding = detector.check(sample(2), 2_000, ctx(engine = PlayerEngineType.NATIVE))

        assertEquals(Symptom.DECODER, finding?.symptom)
        assertTrue(finding?.evidence?.contains("Videodecoder-Warnungen") == true)
    }

    @Test
    fun isolatedNativeDecoderLagEventsDoNotTriggerRecovery() {
        val detector = DecoderLagDetector()
        val context = ctx(engine = PlayerEngineType.NATIVE)
        fun sample(count: Int) = HealthSnapshot(PlaybackStatus.READY, true, 1_000, videoLagEvents = count)

        assertNull(detector.check(sample(0), 0, context))
        assertNull(detector.check(sample(1), 1_000, context))
        assertNull(detector.check(sample(2), 9_001, context))
    }

    @Test
    fun videoTrackThatNeverRendersItsFirstFrameIsDetected() {
        val findings = feed(FrozenPictureDetector(), 12_000) { t ->
            playing(t, frames = 0, audio = t / 20, video = true)
        }
        assertEquals(Symptom.FROZEN_PICTURE, findings.single().symptom)
        assertTrue(findings.single().evidence.startsWith("kein erstes Bild"))
    }

    @Test
    fun audioOnlyChannelDoesNotTriggerFrozenPicture() {
        val findings = feed(FrozenPictureDetector(), 120_000) { t ->
            playing(t, frames = 0, audio = t / 20, video = false)
        }
        assertTrue(findings.isEmpty())
    }

    @Test
    fun rendererCounterResetStartsANewFreezeWindow() {
        val detector = FrozenPictureDetector()
        feed(detector, 4_000) { t -> playing(t, frames = 100 + t / 500) }
        feed(detector, 5_000, from = 4_000) { t -> playing(t, frames = 100 + t / 500) }
        // Renderer wurde um 9 s zurückgesetzt; der alte Zählerstand darf den neuen Abschnitt nicht sofort auslösen.
        val afterReset = feed(detector, 7_500, from = 9_000) { t -> playing(t, frames = (t - 9_000) / 500) }
        assertTrue(afterReset.isEmpty())
        val later = feed(detector, 9_000, from = 16_500) { playing(0, frames = 0) }
        assertEquals(Symptom.FROZEN_PICTURE, later.single().symptom)
    }

    /** Häufung von Ausgabefehlern ohne Tonzähler (Ton-Durchreichen): echter Tonausfall. */
    @Test
    fun repeatedAudioOutputErrorsAreDetected() {
        val detector = AvPresenceDetector()
        feed(detector, 2_000) { t -> playing(t, audio = null) }
        val findings = (1..config.audioErrorBurst).mapNotNull { n ->
            detector.check(playing(2_000 + n * 500L, audio = null).copy(audioErrors = n), 2_000 + n * 500L, ctx())
        }
        assertEquals(Symptom.VIDEO_WITHOUT_AUDIO, findings.single().symptom)
    }

    /**
     * Fire-TV-Protokoll vom 31.10.: Ein einzelner Ausgabefehler löste dreimal „Bild ohne Ton“ aus, jedes Mal
     * „behoben nach 0,0 s“. Ein Ausrutscher (Tonformat-Wechsel) darf nichts umschalten.
     */
    @Test
    fun singleAudioOutputErrorIsIgnored() {
        val detector = AvPresenceDetector()
        feed(detector, 2_000) { t -> playing(t, audio = null) }
        assertNull(detector.check(playing(2_500, audio = null).copy(audioErrors = 1), 2_500, ctx()))
        val later = feed(detector, 60_000, from = 3_000) { t -> playing(t, audio = null).copy(audioErrors = 1) }
        assertTrue(later.isEmpty())
    }

    @Test
    fun judderNeedsRealFrameLoss() {
        assertTrue(feed(JudderDetector(), 30_000) { t -> playing(t, dropped = t / 4_000) }.isEmpty())
        val bad = feed(JudderDetector(), 30_000) { t -> playing(t, frames = t / 40, dropped = t / 100) }
        assertEquals(Symptom.JUDDER, bad.single().symptom)
    }

    @Test
    fun liveDriftOnlyForLiveAndOnlyWhenFarBehind() {
        val near = feed(LiveDriftDetector(), 60_000) { t -> playing(t).copy(liveOffsetMs = 8_000) }
        val vod = feed(LiveDriftDetector(), 60_000, ctx(live = false)) { t -> playing(t).copy(liveOffsetMs = 90_000) }
        val far = feed(LiveDriftDetector(), 15_000) { t -> playing(t).copy(liveOffsetMs = 90_000) }
        assertTrue(near.isEmpty() && vod.isEmpty())
        assertEquals(Symptom.LIVE_DRIFT, far.single().symptom)
    }

    @Test
    fun startDetectorIgnoresErrorsAndStopsOnceReady() {
        val detector = StartDetector()
        assertTrue(feed(detector, 60_000, ctx(live = false)) { HealthSnapshot(PlaybackStatus.ERROR, false, 0) }.isEmpty())
        detector.reset()
        feed(detector, 1_000) { HealthSnapshot(PlaybackStatus.READY, true, 0) }
        assertNull(detector.check(HealthSnapshot(PlaybackStatus.BUFFERING, false, 0), 120_000, ctx()))
    }

    // ---- Ton ohne Bild: „keine Videospur“ ist nicht automatisch Radio (ab 3.59) ----

    /** Ton läuft, Player kennt keine Videospur; [bytesPerMs] Daten je ms (100 = ca. 98 KB/s, 300 = ca. 293 KB/s). */
    private fun soundOnly(t: Long, bytesPerMs: Long) = HealthSnapshot(
        PlaybackStatus.READY,
        true,
        t,
        bytesLoaded = t * bytesPerMs,
        renderedFrames = 0,
        audioUnits = t / 20,
        hasVideoTrack = false,
        hasAudioTrack = true,
    )

    @Test
    fun noVideoTrackAtRadioDataRateIsOnlyAHint() {
        // 20 KB/s: so viel braucht ein Radiosender. Ohne Hinweis auf Fernsehen und ohne Ersatzquelle: nur der Hinweis wie bisher.
        val findings = feed(AvPresenceDetector(), 30_000) { t -> soundOnly(t, bytesPerMs = 20) }
        assertEquals(Symptom.RADIO_ONLY, findings.single().symptom)
    }

    @Test
    fun noVideoTrackButTelevisionDataRateIsAFailure() {
        // 293 KB/s kommen an, aber kein Bild: Im Stream steckt Video, das der Player nicht zeigt.
        val findings = feed(AvPresenceDetector(), 12_000) { t -> soundOnly(t, bytesPerMs = 300) }
        val finding = findings.single()
        assertEquals(Symptom.NO_VIDEO_TRACK, finding.symptom)
        assertTrue(finding.confident)
        assertTrue(finding.evidence, finding.evidence.contains("KB/s"))
    }

    @Test
    fun noVideoTrackOnChannelThatHadPictureIsAFailureEvenAtLowDataRate() {
        val findings = feed(AvPresenceDetector(), 12_000, ctx(picture = PictureExpectation.TV)) { t -> soundOnly(t, bytesPerMs = 20) }
        assertEquals(Symptom.NO_VIDEO_TRACK, findings.single().symptom)
        assertTrue(findings.single().confident)
    }

    @Test
    fun noVideoTrackWithAlternativeIsOnlyGuessedAndProbedEarly() {
        val findings = feed(AvPresenceDetector(), 6_000, ctx(alternative = true)) { t -> soundOnly(t, bytesPerMs = 20) }
        val finding = findings.single()
        assertEquals(Symptom.NO_VIDEO_TRACK, finding.symptom)
        assertTrue("Vermutung, nicht sicher", !finding.confident)
    }

    @Test
    fun radioByNameOrMemoryNeverEscalatesEvenWithTelevisionDataRate() {
        val findings = feed(AvPresenceDetector(), 30_000, ctx(alternative = true, picture = PictureExpectation.RADIO)) { t -> soundOnly(t, bytesPerMs = 300) }
        assertEquals(Symptom.RADIO_ONLY, findings.single().symptom)
    }

    @Test
    fun audioOnlyFileOnDemandStaysAHint() {
        val findings = feed(AvPresenceDetector(), 30_000, ctx(live = false)) { t -> soundOnly(t, bytesPerMs = 300) }
        assertEquals(Symptom.RADIO_ONLY, findings.single().symptom)
    }

    @Test
    fun noVideoTrackBeforeDataRateIsKnownDoesNotGuessTooEarly() {
        // Unter 2 s Messzeit gibt es keine Datenrate; der Befund kommt trotzdem erst nach der Wartezeit.
        val early = feed(AvPresenceDetector(), 3_000, ctx(picture = PictureExpectation.TV)) { t -> soundOnly(t, bytesPerMs = 300) }
        assertTrue(early.isEmpty())
    }

    @Test
    fun tunnelingWithoutFrameCounterStillNoticesThatNoPictureWasEverShown() {
        // Tunneling: kein Bilderzähler. Die Engine meldet aber „erstes Bild gezeigt“ – hier nie.
        fun snap(t: Long, shown: Boolean?) = HealthSnapshot(
            PlaybackStatus.READY,
            true,
            t,
            1_000,
            renderedFrames = null,
            audioUnits = t / 20,
            hasVideoTrack = true,
            hasAudioTrack = true,
            pictureShown = shown,
        )
        val never = feed(AvPresenceDetector(), 10_000) { t -> snap(t, shown = false) }
        assertEquals(Symptom.AUDIO_WITHOUT_VIDEO, never.single().symptom)
        assertTrue(never.single().evidence.contains("ohne Bilderzähler"))
        // Bild wurde gezeigt → nichts. Engine meldet es nicht (null) → nie raten.
        assertTrue(feed(AvPresenceDetector(), 60_000) { t -> snap(t, shown = true) }.isEmpty())
        assertTrue(feed(AvPresenceDetector(), 60_000) { t -> snap(t, shown = null) }.isEmpty())
    }

    @Test
    fun soundPassthroughWithoutAudioCounterUsesThePositionAsProofOfSound() {
        // Ton-Durchreichen: keine Tonblöcke gezählt. Läuft die Position und es kommt nie ein Bild, ist es Ton ohne Bild.
        fun snap(t: Long, frames: Long) =
            HealthSnapshot(PlaybackStatus.READY, true, t, 1_000, renderedFrames = frames, audioUnits = null, hasVideoTrack = true, hasAudioTrack = true)
        val broken = feed(AvPresenceDetector(), 10_000) { t -> snap(t, frames = 0) }
        assertEquals(Symptom.AUDIO_WITHOUT_VIDEO, broken.single().symptom)
        assertTrue(feed(AvPresenceDetector(), 120_000) { t -> snap(t, frames = t / 40) }.isEmpty())
    }

    @Test
    fun audioWithoutVideoReactsFasterWithAlternative() {
        // Der Ton-Zähler bewegt sich ab der zweiten Messung (1 s); 4,5 s danach ist es nach 5,5 s soweit, die 6 s erst nach 7 s.
        val slow = feed(AvPresenceDetector(), 6_000) { t -> playing(t, frames = 0) }
        val quick = feed(AvPresenceDetector(), 6_000, ctx(alternative = true)) { t -> playing(t, frames = 0) }
        assertTrue("ohne Ersatzquelle noch nicht", slow.isEmpty())
        assertEquals(Symptom.AUDIO_WITHOUT_VIDEO, quick.single().symptom)
    }

    @Test
    fun dataRateMeterNeedsTwoSecondsAndIgnoresCounterResets() {
        val meter = DataRateMeter()
        meter.update(0, 0)
        meter.update(100_000, 1_000)
        assertNull(meter.bytesPerSecond())
        meter.update(300_000, 2_000)
        assertEquals(150_000L, meter.bytesPerSecond())
        meter.update(10, 2_500)
        assertNull("nach Zählersprung neu messen", meter.bytesPerSecond())
    }

    @Test
    fun radioNamesAreRecognisedButTelevisionIsNot() {
        listOf("Antenne Bayern Radio", "DE: Radio Gong 96.3", "TR| Kral FM", "Power FM HD", "Radyo D", "РАДИО Релакс", "Rádio Comercial")
            .forEach { assertTrue(it, RadioNames.looksLikeRadio(it)) }
        listOf("DE: Das Erste HD", "TR| TRT 1", "Sky Sport Bundesliga", "Formel 1 FMX", "Radiohead Konzert TV", "ProSieben UHD")
            .forEach { assertTrue(it, !RadioNames.looksLikeRadio(it)) }
    }
}
