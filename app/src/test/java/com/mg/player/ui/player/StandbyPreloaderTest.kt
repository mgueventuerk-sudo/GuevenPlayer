package com.mg.player.ui.player

import com.mg.player.domain.model.ConnectionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ersatz-Variante auf Vorrat nur mit nachweislich freiem Platz – sonst droht „Zugang belegt“. */
class StandbyPreloaderTest {

    @Test
    fun onlyWithAKnownFreeSlot() {
        assertTrue(StandbyPreloader.hasFreeSlot(ConnectionStatus(active = 1, max = 2)))
        assertFalse(StandbyPreloader.hasFreeSlot(ConnectionStatus(active = 1, max = 1)))
        assertFalse(StandbyPreloader.hasFreeSlot(ConnectionStatus(active = 2, max = 2)))
        // Unbegrenzt bzw. unbekannt: nicht riskieren.
        assertFalse(StandbyPreloader.hasFreeSlot(ConnectionStatus(active = 1, max = 0)))
        assertFalse(StandbyPreloader.hasFreeSlot(null))
    }
}
