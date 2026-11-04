package com.mg.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spulen über die Zeitleiste: schnelles Tippen und Halten beschleunigen, Pause und Richtungswechsel setzen zurück. */
class ScrubberTest {
    private var now = 0L
    private val scrubber = Scrubber { now }
    private val twoHours = 2 * 3_600_000L

    private fun tap(dir: Int, pos: Long = 600_000, dur: Long = twoHours) = scrubber.press(dir, repeat = false, positionMs = pos, durationMs = dur)

    @Test
    fun fastTapsGrowTheStep() {
        repeat(5) {
            tap(1)
            now += 300
        }
        // 10 s + 10 s + 30 s + 30 s + 1 min
        assertEquals(600_000L + 140_000, scrubber.targetMs)
        assertEquals(60_000L, scrubber.stepMs)
    }

    @Test
    fun pauseResetsToSmallestStep() {
        repeat(3) {
            tap(1)
            now += 300
        }
        now += 1_500
        tap(1)
        assertEquals(10_000L, scrubber.stepMs)
        assertEquals(600_000L + 60_000, scrubber.targetMs)
    }

    @Test
    fun directionChangeResetsStep() {
        repeat(4) {
            tap(1)
            now += 300
        }
        tap(-1)
        assertEquals(10_000L, scrubber.stepMs)
        assertEquals(600_000L + 80_000 - 10_000, scrubber.targetMs)
    }

    @Test
    fun shortEpisodeCapsStepAtOneTwentieth() {
        repeat(12) {
            tap(1, pos = 0, dur = 22 * 60_000L)
            now += 100
        }
        assertEquals(66_000L, scrubber.stepMs)
    }

    @Test
    fun holdingAdvancesEveryFewRepeats() {
        scrubber.press(1, repeat = false, positionMs = 0, durationMs = twoHours)
        repeat(16) {
            now += 50
            scrubber.press(1, repeat = true, positionMs = 0, durationMs = twoHours)
        }
        assertEquals(30_000L, scrubber.stepMs)
    }

    @Test
    fun targetStopsAtStartAndEnd() {
        tap(-1, pos = 5_000)
        assertEquals(0L, scrubber.targetMs)
        scrubber.cancel()
        tap(1, pos = twoHours - 2_000)
        assertEquals(twoHours - 1_000, scrubber.targetMs)
    }

    @Test
    fun commitReturnsTargetOnceAndCancelDiscards() {
        tap(1)
        assertEquals(610_000L, scrubber.commit())
        assertNull(scrubber.commit())
        tap(1)
        scrubber.cancel()
        assertNull(scrubber.targetMs)
    }
}
