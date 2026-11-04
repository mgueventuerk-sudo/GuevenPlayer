package com.mg.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFocusPolicyTest {

    @Test
    fun freshListDoesNotStealFocusFromTheHomeCategory() {
        assertNull(liveFocusTarget(restoreTarget = null))
    }

    @Test
    fun returningFromPlayerRestoresTheChosenChannel() {
        assertEquals(LiveFocusTarget.Restore("chosen"), liveFocusTarget(restoreTarget = "chosen"))
    }
}
