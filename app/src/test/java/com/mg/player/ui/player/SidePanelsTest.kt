package com.mg.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Fehler aus 3.19 (am Handy gemeldet): Mit offenem „Mehr“-Panel schloss ein Tipp neben das Panel nur die
 * Seitenpanels und die Stream-Info – `moreOpen` blieb stehen. Da sich die Steuerung bei offenem Panel nicht
 * ausblendet, hing die Ansicht dauerhaft fest.
 */
class SidePanelsTest {

    @Test
    fun morePanelCountsAsOpenAndIsClosedWithTheRest() {
        val panels = SidePanels()
        assertFalse(panels.anyOpen)
        panels.moreOpen = true
        assertTrue("„Mehr“ ist eine Seitenfläche", panels.anyOpen)
        panels.closeAll()
        assertFalse("Tipp daneben muss auch „Mehr“ schließen", panels.anyOpen)
        assertFalse(panels.moreOpen)
    }

    @Test
    fun everyKindOfPanelIsClosed() {
        OverlayPanel.entries.forEach { kind ->
            val panels = SidePanels()
            panels.panel = kind
            panels.infoOpen = true
            panels.moreOpen = true
            assertTrue(panels.anyOpen)
            panels.closeAll()
            assertNull(kind.name, panels.panel)
            assertFalse(kind.name, panels.infoOpen)
            assertFalse(kind.name, panels.moreOpen)
        }
    }

    @Test
    fun panelOpenedFromMoreReturnsThere() {
        val panels = SidePanels()
        panels.moreOpen = false
        panels.panelFromMore = true
        panels.panel = OverlayPanel.SLEEP
        panels.panel = null
        panels.backToMore()
        assertTrue("Zurück führt ins „Mehr“-Panel", panels.moreOpen)
        assertFalse(panels.panelFromMore)
    }

    @Test
    fun closingEverythingAlsoForgetsTheWayBack() {
        val panels = SidePanels()
        panels.panelFromMore = true
        panels.panel = OverlayPanel.ENGINE
        panels.closeAll()
        panels.backToMore()
        assertFalse("Nach einem Tipp daneben darf nichts zurückkommen", panels.anyOpen)
    }

    @Test
    fun openingAndClosingIsStableOverManyRounds() {
        val panels = SidePanels()
        repeat(50) { n ->
            panels.moreOpen = true
            if (n % 2 == 0) panels.panel = OverlayPanel.TRACKS
            if (n % 3 == 0) panels.infoOpen = true
            panels.closeAll()
            assertEquals("Runde $n", false, panels.anyOpen)
        }
    }
}
