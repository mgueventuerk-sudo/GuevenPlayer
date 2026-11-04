package com.mg.player.ui.player

import com.mg.player.domain.model.EpgProgram
import com.mg.player.player.engine.TrackOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sleep-Timer: Auswahl, Endzeit und Texte. */
class SleepTimerTest {

    private var now = 1_000_000_000_000L
    private val timer = SleepTimerState { now }
    private val program = EpgProgram("Tatort", null, now - 600_000, now + 3_000_000)

    @Test
    fun liveOffersUntilProgramEnd() {
        val options = timer.options(program, PlaybackPosition(), isLive = true)
        assertEquals(listOf("Aus", "30 Minuten", "60 Minuten", "90 Minuten"), options.take(4).map { it.label })
        assertTrue(options.last().label.startsWith("Bis Sendungsende"))
        assertTrue(options.first().selected)

        val text = timer.select(options.last(), program, PlaybackPosition(), isLive = true)
        assertEquals(program.endMs, timer.endsAtMs)
        assertTrue(text.startsWith("Wiedergabe endet um "))
        assertTrue(timer.buttonLabel.startsWith("Aus um "))
    }

    @Test
    fun minutesAndOff() {
        timer.select(TrackOption("30", "30 Minuten", false), null, PlaybackPosition(), isLive = true)
        assertEquals(now + 30 * 60_000L, timer.endsAtMs)
        assertEquals("Sleep-Timer aus", timer.select(TrackOption(SleepTimerState.OFF, "Aus", false), null, PlaybackPosition(), isLive = true))
        assertNull(timer.endsAtMs)
        assertEquals("Sleep-Timer", timer.buttonLabel)
    }

    @Test
    fun movieEndsWithTheFilmAndLiveWithoutEpgHasNoEndOption() {
        val position = PlaybackPosition(positionMs = 600_000, durationMs = 6_000_000)
        val options = timer.options(null, position, isLive = false)
        assertTrue(options.last().label.startsWith("Bis zum Ende"))
        timer.select(options.last(), null, position, isLive = false)
        assertEquals(now + 5_400_000, timer.endsAtMs)
        assertEquals(4, timer.options(null, PlaybackPosition(), isLive = true).size)
    }

    @Test
    fun epgReloadWaitsForProgramEnd() {
        assertEquals(5 * 60_000L, nextEpgReloadMs(null, now))
        assertEquals(30_000L, nextEpgReloadMs(now + 1_000, now))
        assertEquals(10 * 60_000L + 2_000L, nextEpgReloadMs(now + 10 * 60_000L, now))
        assertEquals(15 * 60_000L, nextEpgReloadMs(now + 3 * 3_600_000L, now))
    }
}
