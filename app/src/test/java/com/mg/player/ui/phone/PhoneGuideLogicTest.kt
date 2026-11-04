package com.mg.player.ui.phone

import com.mg.player.domain.model.EpgProgram
import com.mg.player.ui.guide.GuideViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Was läuft um …" im TV-Guide am Handy. */
class PhoneGuideLogicTest {

    private val hour = GuideViewModel.HOUR
    private val now = 1_760_000_000_000L - 1_760_000_000_000L % hour + 20 * 60_000L // 20 Minuten nach einer vollen Stunde

    @Test
    fun timesFormATimelineAroundNow() {
        val times = guideTimes(now)
        val base = now - now % hour
        assertEquals(19, times.size)
        // "Jetzt" in der Mitte, davor 6 Stunden Archiv, danach 12 Stunden.
        assertEquals(6, times.indexOf(null))
        val hours = times.filterNotNull()
        assertTrue(hours.all { it % hour == 0L })
        assertEquals(hours.sorted(), hours)
        assertEquals(base - 6 * hour, hours.first())
        assertEquals(base - hour, times[5])
        assertEquals(base + hour, times[7])
        assertEquals(base + 12 * hour, hours.last())
        // Die laufende Stunde steckt schon in "Jetzt".
        assertTrue(base !in hours)
        // Sichtbar beim Öffnen: die Stunde vor "Jetzt".
        assertEquals(base - hour, times[GUIDE_TIMES_FIRST_VISIBLE])
    }

    private fun program(title: String, startMin: Int, endMin: Int) = EpgProgram(title, null, now + startMin * 60_000L, now + endMin * 60_000L)

    @Test
    fun findsRunningAndFollowingProgram() {
        val programs = listOf(program("C", 40, 90), program("A", -30, 10), program("B", 10, 40))
        val (current, next) = programsAt(programs, now)
        assertEquals("A", current?.title)
        assertEquals("B", next?.title)
    }

    @Test
    fun boundaryBelongsToTheNextProgram() {
        val programs = listOf(program("A", -30, 10), program("B", 10, 40))
        val (current, next) = programsAt(programs, now + 10 * 60_000L)
        assertEquals("B", current?.title)
        assertNull(next)
    }

    @Test
    fun gapShowsOnlyWhatComesNext() {
        val programs = listOf(program("A", -60, -30), program("B", 30, 60))
        val (current, next) = programsAt(programs, now)
        assertNull(current)
        assertEquals("B", next?.title)
        assertEquals(null to null, programsAt(emptyList(), now))
    }
}
