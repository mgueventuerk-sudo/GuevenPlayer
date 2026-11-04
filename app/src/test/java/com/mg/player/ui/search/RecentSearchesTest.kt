package com.mg.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {

    @Test
    fun newestFirstWithoutDuplicates() {
        val list = RecentSearches.add(listOf("tatort", "zdf"), "ZDF")
        assertEquals(listOf("ZDF", "tatort"), list)
    }

    @Test
    fun tooShortOrBlankIsIgnored() {
        assertEquals(listOf("a1"), RecentSearches.add(listOf("a1"), " x "))
        assertEquals(listOf("a1"), RecentSearches.add(listOf("a1"), "   "))
    }

    @Test
    fun whitespaceIsNormalisedAndListIsCapped() {
        val full = (1..RecentSearches.MAX).map { "t$it" }
        val list = RecentSearches.add(full, "  Champions   League ")
        assertEquals("Champions League", list.first())
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("t${RecentSearches.MAX - 1}", list.last())
    }
}
