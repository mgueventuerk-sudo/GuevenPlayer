package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryDetectorTest {

    private fun detect(name: String) = CountryDetector.detect(name)

    @Test
    fun `typische Praefixe werden erkannt`() {
        assertEquals("DE", detect("DE | Sport"))
        assertEquals("TR", detect("TR: Ulusal"))
        assertEquals("AR", detect("|AR| News"))
        assertEquals("FR", detect("VOD - FR"))
        assertEquals("DE", detect("4K | DE"))
        assertEquals("UK", detect("UK | Sky Sports"))
        assertEquals("US", detect("USA NEWS"))
        assertEquals("DE", detect("DE/AT/CH"))
    }

    @Test
    fun `Kuerzel am Ende oder in Klammern`() {
        assertEquals("TR", detect("Filme [TR]"))
        assertEquals("DE", detect("NEWS DE"))
        assertEquals("DE", detect("SERIEN | NETFLIX DE"))
        assertEquals("AR", detect("BEIN SPORTS AR"))
    }

    @Test
    fun `ausgeschriebene Namen und Schriften`() {
        assertEquals("DE", detect("GERMANY HD"))
        assertEquals("DE", detect("Deutschland Sport"))
        assertEquals("TR", detect("TÜRKİYE ULUSAL"))
        assertEquals("AR", detect("ARABIC MOVIES"))
        assertEquals("AR", detect("قنوات عربية"))
        assertEquals("BALKAN", detect("EX-YU Filmovi"))
    }

    @Test
    fun `arabische Laender werden zusammengefasst`() {
        assertEquals("AR", detect("SA | MBC"))
        assertEquals("AR", detect("EG: Nile"))
        assertEquals("AR", detect("🇸🇦 Rotana"))
    }

    @Test
    fun `Flaggen-Emoji`() {
        assertEquals("DE", detect("🇩🇪 Bundesliga"))
    }

    @Test
    fun `mehrdeutige Kuerzel brauchen ein Trennzeichen`() {
        assertNull(detect("IT CROWD"))
        assertEquals("IT", detect("IT | Serie A"))
        assertNull(detect("Al Jazeera"))
        assertNull(detect("IN CINEMA"))
        assertEquals("IN", detect("IN | Hindi"))
    }

    @Test
    fun `ohne Laenderhinweis`() {
        assertNull(detect("Sport"))
        assertNull(detect("Kinder"))
        assertNull(detect("LIGA DE CAMPEONES"))
        assertNull(detect("beIN Sports"))
    }
}
