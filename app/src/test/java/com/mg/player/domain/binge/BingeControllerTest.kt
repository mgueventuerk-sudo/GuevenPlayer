package com.mg.player.domain.binge

import com.mg.player.domain.model.IntroSkipMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BingeControllerTest {

    private val duration = 2_400_000L

    private fun controller(mode: IntroSkipMode = IntroSkipMode.BUTTON, hasNext: Boolean = true) = BingeController(
        BingeConfig(
            isEpisode = true,
            hasNextEpisode = hasNext,
            introMode = mode,
            introSkipSeconds = 85,
            autoPlayNext = true,
            nextTriggerSeconds = 45,
        ),
    )

    @Test
    fun `zeigt Intro-Button im Fallback-Fenster und blendet ihn nach 8 Sekunden aus`() {
        val c = controller()
        val (visible, _) = c.tick(30_000, duration, 0)
        assertTrue(visible.skipIntroVisible)
        assertEquals("Intro überspringen (+85s)", visible.skipIntroLabel)
        val (hidden, _) = c.tick(31_000, duration, 9_000)
        assertFalse(hidden.skipIntroVisible)
    }

    @Test
    fun `überspringt bekanntes Intro vollautomatisch`() {
        val c = controller(IntroSkipMode.AUTO)
        c.knownWindow = IntroWindow(60_000, 150_000, IntroSource.PROFILE)
        val (_, action) = c.tick(61_000, duration, 0)
        assertEquals(BingeAction.SeekTo(150_000), action)
    }

    @Test
    fun `springt beim Fallback nicht automatisch`() {
        val c = controller(IntroSkipMode.AUTO)
        val (state, action) = c.tick(30_000, duration, 0)
        assertNull(action)
        assertTrue(state.skipIntroVisible)
    }

    @Test
    fun `lernt das Intro beim Überspringen`() {
        val c = controller()
        val result = c.onSkipIntro(40_000)
        assertEquals(125_000L, result.seekToMs)
        assertEquals(IntroWindow(40_000, 125_000, IntroSource.PROFILE), result.learnedWindow)
    }

    @Test
    fun `Countdown für nächste Folge und automatischer Start`() {
        val c = controller()
        val (countdown, first) = c.tick(duration - 40_000, duration, 100_000)
        assertNull(first)
        assertTrue(countdown.nextVisible)
        assertEquals(10, countdown.nextSecondsLeft)
        val (_, action) = c.tick(duration - 30_000, duration, 110_000)
        assertEquals(BingeAction.PlayNext, action)
    }

    @Test
    fun `abgebrochener Countdown startet nicht`() {
        val c = controller()
        c.tick(duration - 40_000, duration, 100_000)
        c.onCancelNext()
        val (state, action) = c.tick(duration - 30_000, duration, 110_000)
        assertFalse(state.nextVisible)
        assertNull(action)
        assertFalse(c.shouldPlayNextOnEnd())
    }

    @Test
    fun `ohne nächste Folge kein Countdown`() {
        val c = controller(hasNext = false)
        val (state, action) = c.tick(duration - 40_000, duration, 100_000)
        assertFalse(state.nextVisible)
        assertNull(action)
    }
}
