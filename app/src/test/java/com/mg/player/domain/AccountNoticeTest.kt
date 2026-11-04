package com.mg.player.domain

import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.accessDeniedReason
import com.mg.player.domain.model.playlistIdOf
import com.mg.player.domain.model.subscriptionNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Abo-Ende und genaue Gründe bei „Zugriff verweigert“. */
class AccountNoticeTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 3_600_000L

    @Test
    fun subscriptionEnd() {
        val far = ConnectionStatus(0, 1, now + 40 * day).subscriptionNotice(now)!!
        assertTrue(far.text.startsWith("Abo bis "))
        assertFalse(far.warning || far.expired)
        assertEquals("Abo läuft in 3 Tagen ab", ConnectionStatus(0, 1, now + 3 * day + 1).subscriptionNotice(now)!!.text)
        assertEquals("Abo läuft in 1 Tag ab", ConnectionStatus(0, 1, now + day + 1).subscriptionNotice(now)!!.text)
        val today = ConnectionStatus(0, 1, now + 3_600_000).subscriptionNotice(now)!!
        assertEquals("Abo läuft heute ab", today.text)
        assertTrue(today.warning)
        val expired = ConnectionStatus(0, 1, now - day).subscriptionNotice(now)!!
        assertTrue(expired.expired)
        assertNull(ConnectionStatus(0, 1, null).subscriptionNotice(now))
    }

    @Test
    fun accessDeniedReasons() {
        assertTrue(ConnectionStatus(0, 1, now - day).accessDeniedReason(now)!!.contains("abgelaufen"))
        assertTrue(ConnectionStatus(1, 1, now + day).accessDeniedReason(now)!!.contains("alle 1 Verbindungen"))
        assertNull(ConnectionStatus(0, 2, now + day).accessDeniedReason(now))
    }

    @Test
    fun playlistFromItemId() {
        assertEquals(12L, playlistIdOf("12:L:105"))
        assertNull(playlistIdOf("rec-123"))
        assertNull(playlistIdOf("abc:M:1"))
    }
}
