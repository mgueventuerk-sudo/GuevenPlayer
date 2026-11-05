package com.mg.player.ui.player

import com.mg.player.core.util.SourceHealth
import com.mg.player.domain.model.Channel
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.ErrorKind
import com.mg.player.player.engine.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Was der Wächter sich je Quelle merkt (ab 3.50) – und was bewusst nicht. */
class SourceHealthTrackerTest {

    private var now = 0L
    private var online = true
    private val health = SourceHealth { 1_000_000L }
    private val tracker = SourceHealthTracker(health, { online }, { now })

    private val playing = EngineState(status = PlaybackStatus.READY, isPlaying = true)
    private val buffering = EngineState(status = PlaybackStatus.BUFFERING)

    private fun channel(id: String) = Channel(id, "Sender", null, "http://x/$id", "", "", null, null, null, playlistId = 1)

    private fun live(index: Int = 0): Playable {
        val variants = listOf(channel("1:L:a"), channel("1:L:b"))
        return Playable(PlayableKind.LIVE, "1:L:a", "Sender", null, "u", 0, null, variants = variants, variantIndex = index)
    }

    private fun startPlaying(p: Playable = live()) {
        tracker.onPrepared(p)
        now += 500
        tracker.onState(playing)
    }

    @Test
    fun evenAOneSecondHangIsRemembered() {
        startPlaying()
        now += 10_000
        tracker.onState(buffering)
        now += 1_200
        tracker.onState(playing)
        assertEquals(1, health.rank("1:L:a"))
    }

    @Test
    fun aBlinkIsNoHang() {
        startPlaying()
        tracker.onState(buffering)
        now += 400
        tracker.onState(playing)
        assertEquals(0, health.rank("1:L:a"))
    }

    @Test
    fun leavingAHangingSourceCountsButAManualSwitchWhilePlayingDoesNot() {
        startPlaying()
        now += 5_000
        tracker.onLeaving()
        assertEquals(0, health.rank("1:L:a"))

        startPlaying(live(1))
        tracker.onState(buffering)
        now += 2_000
        tracker.onLeaving()
        assertEquals(2, health.rank("1:L:b"))
    }

    @Test
    fun startThatNeverComesIsAFailure() {
        tracker.onPrepared(live())
        now += 2_500
        tracker.onLeaving()
        assertEquals(3, health.rank("1:L:a"))
    }

    @Test
    fun zappingAwayDuringStartIsNoFailure() {
        tracker.onPrepared(live())
        now += 2_500
        tracker.onPrepared(Playable(PlayableKind.LIVE, "1:L:z", "Anderer", null, "u", 0, null))
        assertEquals(0, health.rank("1:L:a"))
    }

    @Test
    fun noInternetOrBusyAccountIsNotTheSourcesFault() {
        online = false
        startPlaying()
        tracker.onState(buffering)
        now += 5_000
        tracker.onState(playing)
        assertEquals(0, health.rank("1:L:a"))
        online = true
        tracker.onState(EngineState(status = PlaybackStatus.ERROR, errorKind = ErrorKind.AUTH, httpCode = 403))
        assertEquals(0, health.rank("1:L:a"))
    }

    @Test
    fun longCleanPlaybackRestoresTheSource() {
        health.record("1:L:a", SourceHealth.FAILED)
        startPlaying()
        now += SourceHealthTracker.STABLE_MS
        tracker.onTick()
        assertTrue(health.penalty("1:L:a") < SourceHealth.FAILED)
    }
}
