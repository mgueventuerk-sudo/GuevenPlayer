package com.mg.player.data.sport.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubAuthTest {

    private val key = HubAuth.key("12345678")

    @Test
    fun sameCodeSameKeyOtherCodeOtherKey() {
        assertTrue(key.contentEquals(HubAuth.key("12345678")))
        assertFalse(key.contentEquals(HubAuth.key("12345679")))
    }

    @Test
    fun signatureCoversEveryPart() {
        val sig = HubAuth.sign(key, "/release", "abc", "1000", "acct")
        assertTrue(HubAuth.verify(key, sig, "/release", "abc", "1000", "acct"))
        assertFalse(HubAuth.verify(key, sig, "/release", "abc", "1000", "other"))
        assertFalse(HubAuth.verify(key, sig, "/proofs", "abc", "1000", "acct"))
        assertFalse(HubAuth.verify(HubAuth.key("00000000"), sig, "/release", "abc", "1000", "acct"))
        assertFalse(HubAuth.verify(key, null, "/release", "abc", "1000", "acct"))
    }

    @Test
    fun signatureDoesNotContainTheCode() {
        assertFalse(HubAuth.sign(key, "/proofs", "n", "1").contains("12345678"))
    }

    @Test
    fun noncesAreRandomAndRequestsExpire() {
        assertNotEquals(HubAuth.nonce(), HubAuth.nonce())
        assertEquals(32, HubAuth.nonce().length)
        assertTrue(HubAuth.fresh(1_000_000L, 1_000_000L + HubAuth.MAX_SKEW_MS))
        assertFalse(HubAuth.fresh(1_000_000L, 1_000_001L + HubAuth.MAX_SKEW_MS))
        assertFalse(HubAuth.fresh(null, 0L))
    }
}
