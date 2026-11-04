package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTitleTest {

    @Test
    fun `Film-Tags und Jahr werden herausgeloest`() {
        val info = MediaTitle.parse("|DE| Avatar (2009) [4K] [HEVC]")
        assertEquals("Avatar", info.title)
        assertEquals("2009", info.year)
        assertEquals(listOf("4K", "HEVC"), info.badges)
    }

    @Test
    fun `Sendernamen ohne Laenderkuerzel und Qualitaet`() {
        assertEquals("ZDF", MediaTitle.parse("DE: ZDF UHD").title)
        assertEquals("4K", MediaTitle.parse("DE: ZDF UHD").resolution)
        assertEquals("Sky Bundesliga 1", MediaTitle.parse("GE - Sky Bundesliga 1 FHD").title)
        assertEquals("Das Erste", MediaTitle.parse("[DE] Das Erste HD").title)
        assertEquals("ProSieben", MediaTitle.parse("ProSieben (RAW)").title)
    }

    @Test
    fun `Regionalnamen und Zahlen bleiben erhalten`() {
        assertEquals("WDR (Köln)", MediaTitle.parse("WDR (Köln) HD").title)
        assertEquals("Blade Runner 2049", MediaTitle.parse("Blade Runner 2049").title)
        assertEquals("Raw", MediaTitle.parse("Raw").title)
    }

    @Test
    fun `Varianten bekommen denselben Gruppenschluessel`() {
        val uhd = MediaTitle.groupKey(MediaTitle.parse("DE: ZDF UHD").title, "DE")
        val hd = MediaTitle.groupKey(MediaTitle.parse("DE: ZDF HD").title, "DE")
        assertEquals(uhd, hd)
        val bl1 = MediaTitle.groupKey(MediaTitle.parse("Sky Bundesliga 1 HD").title, "DE")
        val bl2 = MediaTitle.groupKey(MediaTitle.parse("Sky Bundesliga 2 HD").title, "DE")
        assertNotEquals(bl1, bl2)
        val koeln = MediaTitle.groupKey(MediaTitle.parse("WDR Köln").title, "DE")
        val essen = MediaTitle.groupKey(MediaTitle.parse("WDR Essen").title, "DE")
        assertNotEquals(koeln, essen)
    }

    @Test
    fun `Qualitaetsrang 4K vor RAW vor FHD HEVC vor FHD vor HD vor SD`() {
        fun rank(name: String) = MediaTitle.qualityRank(MediaTitle.parse(name).badges)
        val order = listOf("ZDF UHD", "ZDF RAW", "ZDF FHD HEVC", "ZDF FHD", "ZDF HD", "ZDF SD").map(::rank)
        assertEquals(order.sorted(), order)
        assertTrue(rank("ZDF HD") < rank("ZDF HD [Backup]"))
    }

    @Test
    fun `ohne Tags bleibt alles wie es ist`() {
        val info = MediaTitle.parse("Der Pate")
        assertEquals("Der Pate", info.title)
        assertNull(info.year)
        assertTrue(info.badges.isEmpty())
    }

    @Test
    fun `GE wird als Deutsch erkannt`() {
        assertEquals("DE", CountryDetector.detect("|GE| Sport"))
        assertEquals("DE", CountryDetector.codeFor("GE"))
    }

    @Test
    fun superscriptQualityIsRecognised() {
        assertEquals("4K", MediaTitle.parse("DE: ZDF ᵁᴴᴰ").resolution)
        assertEquals("ZDF", MediaTitle.parse("DE: ZDF ᵁᴴᴰ").title)
        assertEquals("FHD", MediaTitle.parse("DE: ZDF ᶠᴴᴰ").resolution)
    }

    @Test
    fun fancyProviderFontsAreShownAsNormalLetters() {
        assertEquals("ProSieben", MediaTitle.parse("DE: 𝗣𝗿𝗼𝗦𝗶𝗲𝗯𝗲𝗻 HD").title)
        assertEquals("ZDF", MediaTitle.parse("ＺＤＦ").title)
    }
}
