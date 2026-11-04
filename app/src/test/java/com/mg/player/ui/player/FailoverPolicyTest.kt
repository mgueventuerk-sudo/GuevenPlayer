package com.mg.player.ui.player

import com.mg.player.player.engine.ErrorKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fall aus der Prüfung: Ein kurzer WLAN-Aussetzer schaltete sofort auf SD um – und blieb dort. */
class FailoverPolicyTest {

    @Test
    fun noSwitchWhileOffline() {
        assertFalse(FailoverPolicy.isStreamProblem(ErrorKind.AUTH, 403, online = false, retriesSoFar = 5))
        assertFalse(FailoverPolicy.isStreamProblem(ErrorKind.NETWORK, null, online = false, retriesSoFar = 5))
    }

    @Test
    fun plainNetworkErrorIsRetriedBeforeSwitching() {
        assertFalse(FailoverPolicy.isStreamProblem(ErrorKind.NETWORK, null, online = true, retriesSoFar = 0))
        assertFalse(FailoverPolicy.isStreamProblem(ErrorKind.NETWORK, null, online = true, retriesSoFar = 1))
        assertTrue(FailoverPolicy.isStreamProblem(ErrorKind.NETWORK, null, online = true, retriesSoFar = 2))
    }

    @Test
    fun httpAndFormatErrorsSwitchImmediately() {
        assertTrue(FailoverPolicy.isStreamProblem(ErrorKind.NOT_FOUND, 404, online = true, retriesSoFar = 0))
        assertTrue(FailoverPolicy.isStreamProblem(ErrorKind.AUTH, 403, online = true, retriesSoFar = 0))
        assertTrue(FailoverPolicy.isStreamProblem(ErrorKind.FORMAT, null, online = true, retriesSoFar = 0))
        assertTrue(FailoverPolicy.isStreamProblem(ErrorKind.NETWORK, 502, online = true, retriesSoFar = 0))
    }
}
