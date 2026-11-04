package com.mg.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryAccentTest {

    @Test
    fun recognisesTypicalProviderGroups() {
        assertEquals(CategoryKind.SPORT, CategoryAccent.of("DE | Sport HD"))
        assertEquals(CategoryKind.SPORT, CategoryAccent.of("TR: Spor Kanalları"))
        assertEquals(CategoryKind.SPORT, CategoryAccent.of("|AR| beIN Sports"))
        assertEquals(CategoryKind.NEWS, CategoryAccent.of("DE Nachrichten"))
        assertEquals(CategoryKind.NEWS, CategoryAccent.of("TR | Haber"))
        assertEquals(CategoryKind.MOVIES, CategoryAccent.of("Filme 2025"))
        assertEquals(CategoryKind.MOVIES, CategoryAccent.of("TR Sinema"))
        assertEquals(CategoryKind.KIDS, CategoryAccent.of("Kinder & Familie"))
        assertEquals(CategoryKind.DOCUMENTARY, CategoryAccent.of("Dokumentationen"))
        assertEquals(CategoryKind.MUSIC, CategoryAccent.of("Musik"))
    }

    @Test
    fun kidsWinsOverMoviesAndSportOverNews() {
        assertEquals(CategoryKind.KIDS, CategoryAccent.of("Kids Movies"))
        assertEquals(CategoryKind.SPORT, CategoryAccent.of("Sport News"))
    }

    @Test
    fun shortKeysOnlyAsWholeWords() {
        assertEquals(CategoryKind.SPORT, CategoryAccent.of("F1 Live"))
        assertNull(CategoryAccent.of("F14 Kanal"))
        assertNull(CategoryAccent.of("Nbc Universal"))
    }

    @Test
    fun unknownGroupsGetNoColor() {
        assertNull(CategoryAccent.of("DE | Allgemein"))
        assertNull(CategoryAccent.of("Regional"))
        assertNull(CategoryAccent.of(""))
    }
}
