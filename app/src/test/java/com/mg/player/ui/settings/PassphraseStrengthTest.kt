package com.mg.player.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PassphraseStrengthTest {
    @Test
    fun grades() {
        assertEquals(0, passphraseStrength("abcdef"))
        assertEquals(1, passphraseStrength("abcdef12"))
        assertEquals(2, passphraseStrength("Fernseher-2026!"))
    }
}
