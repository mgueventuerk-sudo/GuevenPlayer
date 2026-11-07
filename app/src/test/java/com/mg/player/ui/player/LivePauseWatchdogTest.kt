package com.mg.player.ui.player

import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PlaybackPauseReason
import com.mg.player.player.engine.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePauseWatchdogTest {
    private val playing = EngineState(status = PlaybackStatus.READY, isPlaying = true, playWhenReady = true)
    private fun paused(reason: PlaybackPauseReason = PlaybackPauseReason.USER_REQUEST) =
        EngineState(status = PlaybackStatus.READY, playWhenReady = false, pauseReason = reason)

    @Test fun resumesUnexpectedPauseWithoutReloadingStream() {
        val watchdog = LivePauseWatchdog()
        watchdog.check(playing, 0, true)
        assertEquals(LivePauseWatchdog.Decision.Observed(PlaybackPauseReason.USER_REQUEST), watchdog.check(paused(), 500, true))
        assertNull(watchdog.check(paused(), 3_000, true))
        assertEquals(LivePauseWatchdog.Decision.Resume(1, PlaybackPauseReason.USER_REQUEST), watchdog.check(paused(), 3_500, true))
        assertNull(watchdog.check(paused(), 5_000, true))
        assertEquals(LivePauseWatchdog.Decision.Resume(2, PlaybackPauseReason.USER_REQUEST), watchdog.check(paused(), 8_500, true))
        assertTrue(watchdog.check(paused(), 13_500, true) is LivePauseWatchdog.Decision.Unresolved)
        assertNull(watchdog.check(paused(), 14_000, true))
    }

    @Test fun waitsLongerForAudioFocus() {
        val watchdog = LivePauseWatchdog()
        watchdog.check(playing, 0, true)
        watchdog.check(paused(PlaybackPauseReason.AUDIO_FOCUS_LOSS), 1_000, true)
        assertNull(watchdog.check(paused(PlaybackPauseReason.AUDIO_FOCUS_LOSS), 8_500, true))
        assertEquals(
            LivePauseWatchdog.Decision.Resume(1, PlaybackPauseReason.AUDIO_FOCUS_LOSS),
            watchdog.check(paused(PlaybackPauseReason.AUDIO_FOCUS_LOSS), 9_000, true),
        )
    }

    @Test fun doesNotResumeUserPauseOrBackgroundPlayer() {
        val watchdog = LivePauseWatchdog()
        watchdog.check(playing, 0, true)
        watchdog.check(paused(), 500, false)
        assertNull(watchdog.check(paused(), 20_000, false))
        assertEquals(LivePauseWatchdog.Decision.Observed(PlaybackPauseReason.USER_REQUEST), watchdog.check(paused(), 21_000, true))
        watchdog.interrupt()
        assertEquals(LivePauseWatchdog.Decision.Observed(PlaybackPauseReason.USER_REQUEST), watchdog.check(paused(), 40_000, true))
    }

    @Test fun doesNotFightRemotePauseOrDisconnectedAudioRoute() {
        for (reason in listOf(PlaybackPauseReason.REMOTE, PlaybackPauseReason.AUDIO_BECOMING_NOISY)) {
            val watchdog = LivePauseWatchdog()
            watchdog.check(playing, 0, true)
            assertEquals(LivePauseWatchdog.Decision.Observed(reason), watchdog.check(paused(reason), 1_000, true))
            assertNull(watchdog.check(paused(reason), 30_000, true))
        }
    }

    @Test fun doesNotMistakeBufferingOrInitialStartForPause() {
        val watchdog = LivePauseWatchdog()
        assertNull(watchdog.check(paused().copy(status = PlaybackStatus.BUFFERING), 10_000, true))
        watchdog.check(playing, 11_000, true)
        assertNull(watchdog.check(playing.copy(status = PlaybackStatus.BUFFERING, isPlaying = false), 20_000, true))
        assertNull(watchdog.check(playing.copy(status = PlaybackStatus.READY, isPlaying = false), 30_000, true))
    }

    @Test fun recoveryResetsWhenPlaybackReturnsAndOnChannelChange() {
        val watchdog = LivePauseWatchdog()
        watchdog.check(playing, 0, true)
        watchdog.check(paused(), 500, true)
        watchdog.check(paused(), 3_500, true)
        watchdog.check(playing, 4_000, true)
        assertEquals(LivePauseWatchdog.Decision.Observed(PlaybackPauseReason.USER_REQUEST), watchdog.check(paused(), 5_000, true))
        watchdog.resetSession()
        assertNull(watchdog.check(paused(), 20_000, true))
    }
}
