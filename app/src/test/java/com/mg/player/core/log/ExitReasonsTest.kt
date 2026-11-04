package com.mg.player.core.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitReasonsTest {
    private val fg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    private val bg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED

    @Test
    fun anrFollowedByNormalExitIsStillFound() {
        // Neuestes Ende: normal (Aufnahme im Hintergrund beendet) – davor ein ANR. Bis 3.12.1 ging der ANR verloren.
        val exits = listOf(
            Triple(300L, ApplicationExitInfo.REASON_EXIT_SELF, bg),
            Triple(200L, ApplicationExitInfo.REASON_ANR, fg),
            Triple(100L, ApplicationExitInfo.REASON_CRASH, fg),
        )
        assertEquals(200L, ExitReasons.pickUnexpected(exits))
    }

    @Test
    fun onlyNormalEndsMeanNothing() {
        val exits = listOf(
            Triple(300L, ApplicationExitInfo.REASON_USER_REQUESTED, fg),
            Triple(200L, ApplicationExitInfo.REASON_LOW_MEMORY, bg),
        )
        assertNull(ExitReasons.pickUnexpected(exits))
    }
}
