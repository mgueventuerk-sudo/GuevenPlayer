package com.mg.player.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Bildbeweis: NullPointerException" ohne Stelle war nicht auffindbar - der Fehlerort gehört ins Protokoll. */
class AppLogLocationTest {

    @Test
    fun locationNamesTheThrowingMethodAndLine() {
        val error = runCatching { throw NullPointerException() }.exceptionOrNull()!!
        val location = AppLog.location(error)
        assertTrue(location, location.startsWith(" @ AppLogLocationTest."))
        assertTrue(location, Regex(":\\d+").containsMatchIn(location))
    }

    @Test
    fun locationIsLimitedToTheTopFrames() {
        val error = Throwable()
        error.stackTrace = Array(10) { StackTraceElement("a.b.C$it", "m$it", "C.kt", it + 1) }
        assertEquals(" @ C0.m0:1 < C1.m1:2 < C2.m2:3", AppLog.location(error))
    }

    @Test
    fun noStackMeansNoLocation() {
        val error = Throwable()
        error.stackTrace = emptyArray()
        assertEquals("", AppLog.location(error))
    }
}
