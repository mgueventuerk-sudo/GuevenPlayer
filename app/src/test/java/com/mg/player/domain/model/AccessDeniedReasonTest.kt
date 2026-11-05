package com.mg.player.domain.model

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** „Zugriff verweigert“ bei vollem Zugang: eigenes Gerät ≠ fremdes Gerät (ab 3.45). */
class AccessDeniedReasonTest {

    private val now = 1_800_000_000_000L

    @Test
    fun ownConnectionIsNotBlamedOnAnotherDevice() {
        val reason = ConnectionStatus(active = 1, max = 1).accessDeniedReason(now, usedHere = true)!!
        assertTrue(reason.contains("vorige Verbindung"))
        assertTrue(!reason.contains("anderen Gerät"))
    }

    @Test
    fun foreignUseStillPointsToTheOtherDevice() {
        val reason = ConnectionStatus(active = 1, max = 1).accessDeniedReason(now, usedHere = false)!!
        assertTrue(reason.contains("anderen Gerät"))
    }

    @Test
    fun expiredSubscriptionWinsOverOwnUse() {
        val reason = ConnectionStatus(active = 1, max = 1, expiresAtMs = now - 1).accessDeniedReason(now, usedHere = true)!!
        assertTrue(reason.contains("abgelaufen"))
    }

    @Test
    fun freeAccountHasNoBetterReason() {
        assertNull(ConnectionStatus(active = 0, max = 1).accessDeniedReason(now, usedHere = true))
    }
}
