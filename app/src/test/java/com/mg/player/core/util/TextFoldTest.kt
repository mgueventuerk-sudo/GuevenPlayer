package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Zierschriften, wie sie IPTV-Anbieter gern verwenden, werden zu normaler Schrift. */
class TextFoldTest {

    @Test
    fun smallCapitals() {
        assertEquals("DE ┃ ZDF HD", TextFold.plain("ᴅᴇ ┃ ᴢᴅꜰ ʜᴅ"))
    }

    @Test
    fun mathematicalAndFullwidthLetters() {
        assertEquals("ProSieben", TextFold.plain("𝗣𝗿𝗼𝗦𝗶𝗲𝗯𝗲𝗻"))
        assertEquals("ZDF", TextFold.plain("ＺＤＦ"))
    }

    @Test
    fun negativeSquaredAndCircledLetters() {
        assertEquals("ZDF", TextFold.plain("🆉🅳🅵"))
        assertEquals("ARD", TextFold.plain("🅐🅡🅓"))
    }

    @Test
    fun plainTextStaysUntouched() {
        assertEquals("DE: Das Erste HD", TextFold.plain("DE: Das Erste HD"))
        assertEquals("Café Münster", TextFold.plain("Café Münster"))
    }

    @Test
    fun countryOfStyledCategoryIsDetected() {
        assertEquals("DE", CountryDetector.detect("ᴅᴇ ┃ ɴᴀᴄʜʀɪᴄʜᴛᴇɴ"))
    }

    @Test
    fun styledChannelGetsSameEpgNameAsPlain() {
        assertEquals(NameNormalizer.normalize("DE: ZDF HD"), NameNormalizer.normalize("ᴅᴇ: ᴢᴅꜰ ʜᴅ"))
    }
}
