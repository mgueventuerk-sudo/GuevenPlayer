package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `liest XMLTV-Zeit mit Zeitzone`() {
        assertEquals(1_704_137_400_000L, TimeUtils.parseXmltvTime("20240101203000 +0100"))
        assertEquals(1_721_023_200_000L, TimeUtils.parseXmltvTime("20240715010000 -0500"))
    }

    @Test
    fun `liest XMLTV-Zeit ohne Zeitzone als UTC`() {
        assertEquals(1_704_141_000_000L, TimeUtils.parseXmltvTime("20240101203000"))
    }

    @Test
    fun `ungültige Zeit ergibt null`() {
        assertNull(TimeUtils.parseXmltvTime("kaputt"))
        assertNull(TimeUtils.parseXmltvTime(null))
    }

    @Test
    fun `formatiert Dauer`() {
        assertEquals("1:02:03", TimeUtils.formatDuration(3_723_000))
        assertEquals("1:23", TimeUtils.formatDuration(83_000))
    }

    /** Manche Quellen lassen die Sekunden weg – die Zeitzone wurde dann bisher ignoriert. */
    @Test
    fun offsetWithoutSeconds() {
        assertEquals(TimeUtils.parseXmltvTime("20240101203000 +0100"), TimeUtils.parseXmltvTime("202401012030 +0100"))
    }

    @Test
    fun offsetWithColon() {
        // Manche Generatoren schreiben „+01:00“ – bis 3.12.1 fiel die Sendung dadurch weg.
        assertEquals(TimeUtils.parseXmltvTime("20240101203000 +0100"), TimeUtils.parseXmltvTime("20240101203000 +01:00"))
        assertEquals(TimeUtils.parseXmltvTime("20240715010000 -0530"), TimeUtils.parseXmltvTime("20240715010000 -05:30"))
        assertEquals(TimeUtils.parseXmltvTime("202401012030 +0100"), TimeUtils.parseXmltvTime("202401012030 +01:00"))
    }
}
