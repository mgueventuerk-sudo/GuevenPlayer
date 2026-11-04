package com.mg.player.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bildzeiten-Auswertung für die Ruckel-Baseline („Janky Frames < 5 %“). */
class FrameCounterTest {

    private val budget60Hz = 16_666_666L

    private fun FrameCounter.addMs(ms: Long, times: Int = 1) = repeat(times) { add(ms * 1_000_000) }

    @Test
    fun countsMissedJankyAndFrozenFrames() {
        val counter = FrameCounter(budget60Hz)
        counter.addMs(8, times = 90)
        counter.addMs(20, times = 6) // verpasst, aber nicht doppelt
        counter.addMs(40, times = 3) // ruckelig (> 33 ms)
        counter.addMs(900) // eingefroren
        assertEquals(100, counter.frames)
        assertEquals(10, counter.missed)
        assertEquals(4, counter.janky)
        assertEquals(1, counter.frozen)
    }

    @Test
    fun percentilesFromHistogram() {
        val counter = FrameCounter(budget60Hz)
        counter.addMs(10, times = 90)
        counter.addMs(30, times = 9)
        counter.addMs(5_000) // über der Obergrenze: landet im letzten Fach statt abzustürzen
        assertEquals(10, counter.percentileMs(50))
        assertEquals(10, counter.percentileMs(90))
        assertEquals(30, counter.percentileMs(99))
        assertEquals(1_000, counter.percentileMs(100))
    }

    @Test
    fun tooFewFramesGiveNoLine() {
        val counter = FrameCounter(budget60Hz)
        counter.addMs(10, times = FrameCounter.MIN_FRAMES - 1)
        assertNull(counter.summary("live", debugBuild = false))
        assertEquals(0, FrameCounter(budget60Hz).percentileMs(90))
    }

    @Test
    fun summaryNamesScreenRatesAndDebugBuild() {
        val counter = FrameCounter(budget60Hz)
        counter.addMs(10, times = 190)
        counter.addMs(20, times = 10)
        val line = counter.summary("live", debugBuild = true)!!
        assertTrue(line, line.startsWith("Bildzeiten live: 200 Bilder, 5,0 % verpasst (> 16 ms), 0,0 % ruckelig (> 33 ms)"))
        assertTrue(line, line.endsWith("[Debug-Build: langsamer als Release]"))
    }

    /** Nach Bildfrequenz-Anpassung auf 50 Hz gilt ein 20-ms-Budget: 18-ms-Bilder sind dann pünktlich. */
    @Test
    fun budgetFollowsRefreshRate() {
        val counter = FrameCounter(20_000_000L)
        counter.addMs(18, times = 10)
        assertEquals(0, counter.missed)
    }
}
