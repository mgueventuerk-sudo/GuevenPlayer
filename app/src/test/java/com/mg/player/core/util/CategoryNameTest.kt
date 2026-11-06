package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gruppennamen des Anbieters lesbar machen, ohne Information zu verlieren. */
class CategoryNameTest {

    @Test
    fun uppercaseGroupsBecomeReadableAndKeepTheirCountry() {
        assertEquals("DE · Vollprogramm", CategoryName.display("DE | VOLLPROGRAMM"))
        assertEquals("DE · Nachrichten", CategoryName.display("DE | NACHRICHTEN"))
        assertEquals("TR · Ulusal", CategoryName.display("TR | ULUSAL"))
        assertEquals("DE · Komödie", CategoryName.display("DE | KOMÖDIE"))
        assertEquals("DE · Doku", CategoryName.display("DE|DOKU"))
    }

    @Test
    fun shortCodesAndAcronymsKeepTheirCapitals() {
        // Kürzel bis drei Zeichen und bekannte Abkürzungen bleiben – „Uhd“ oder „Nba“ wäre schlechter als vorher.
        assertEquals("DE · Sport UHD", CategoryName.display("DE | SPORT UHD"))
        assertEquals("US · NBA", CategoryName.display("US | NBA"))
        assertEquals("DE · DAZN Fussball", CategoryName.display("DE | DAZN FUSSBALL"))
        // Zahlen im Wort deuten auf eine Kennung hin (4K, H265, Sky1).
        assertEquals("DE · Premium 4K", CategoryName.display("DE | PREMIUM 4K"))
        // Grenze der Heuristik: Woerter bis drei Zeichen bleiben gross, damit ARD, ZDF und RTL nicht zu "Ard"
        // werden - "SKY" faellt darunter und bleibt deshalb ebenfalls gross. Schlechter als vorher ist es nicht.
        assertEquals("DE · SKY Sport", CategoryName.display("DE | SKY SPORT"))
        assertEquals("DE · ARD Mediathek", CategoryName.display("DE | ARD MEDIATHEK"))
    }

    @Test
    fun namesTheProviderAlreadyWroteProperlyAreLeftAlone() {
        assertEquals("Sky Sport Bundesliga", CategoryName.display("Sky Sport Bundesliga"))
        assertEquals("DE · Sky Cinema", CategoryName.display("DE | Sky Cinema"))
        assertEquals("Favoriten", CategoryName.display("Favoriten"))
    }

    @Test
    fun emptyAndOddInputSurvive() {
        assertEquals("", CategoryName.display(""))
        assertEquals("", CategoryName.display("   "))
        assertEquals("DE", CategoryName.display("DE |"))
        assertEquals("Action", CategoryName.display("  ACTION  "))
        // Schrägstrich trennt genauso wie der Strich.
        assertEquals("DE · Serien", CategoryName.display("DE / SERIEN"))
    }
}
