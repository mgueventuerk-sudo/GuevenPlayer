package com.mg.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `Wartezeiten steigen und enden nach sechs Versuchen`() {
        val policy = ReconnectPolicy()
        val delays = List(6) { policy.nextDelayMs() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), delays)
        assertNull(policy.nextDelayMs())
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMs())
    }
}
