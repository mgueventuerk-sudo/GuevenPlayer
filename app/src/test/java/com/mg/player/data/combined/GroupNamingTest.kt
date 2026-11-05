package com.mg.player.data.combined

import com.mg.player.data.combined.CategoryTopics.Topic
import com.mg.player.domain.model.GroupSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Einheitliche Gruppennamen und -ordnung in „Alle Zugänge“ (ab 3.44). */
class GroupNamingTest {

    @Test
    fun providerDecorationsBecomeCountryAndTopic() {
        assertEquals("Deutschland · Science-Fiction", CategoryTopics.classify("MOVIE", "|DE| ✪ SCI-FI").label)
        assertEquals("Deutschland · Neuheiten", CategoryTopics.classify("MOVIE", "|DE| ✪ NEUE FILME").label)
        assertEquals("Deutschland · Sport", CategoryTopics.classify("LIVE", "DE | Sport").label)
    }

    @Test
    fun groupsWithoutTopicGetTheSameFormat() {
        assertEquals("Deutschland · Filme 2023", CategoryTopics.classify("MOVIE", "|DE| ✪ FILME 2023").label)
        assertEquals("Eigene Sender", CategoryTopics.classify("LIVE", "Eigene Sender").label)
    }

    @Test
    fun abbreviationsStayUppercaseWordsAreNormalised() {
        assertEquals("Deutschland · Kino UHD", CategoryTopics.labelWithoutTopic("DE ▸ KINO UHD", "DE", "Deutschland"))
        assertEquals("Deutschland · VIP Kanäle", CategoryTopics.labelWithoutTopic("|DE| VIP KANÄLE", "DE", "Deutschland"))
    }

    private fun group(key: String, name: String, sort: Int, country: String?, topic: Topic?) =
        CombinedGroup(key, "MOVIE", name, sort, country, CategoryTopics.displayRank(topic))

    @Test
    fun orderIsCountryThenTopicThenAlphabet() {
        val groups = listOf(
            group("a", "Türkei · Action", 50, "TR", Topic.ACTION),
            group("b", "Deutschland · Filme 2024", 3, "DE", null),
            group("c", "Deutschland · Action", 9, "DE", Topic.ACTION),
            group("d", "Deutschland · Filme 2023", 4, "DE", null),
            group("e", "Deutschland · Neuheiten", 7, "DE", Topic.NEW),
            group("f", "Eigene", 0, null, null),
            group("g", "Deutschland · Erwachsene", 1, "DE", Topic.ADULT),
        )
        assertEquals(
            listOf(
                "Deutschland · Neuheiten",
                "Deutschland · Action",
                "Deutschland · Filme 2023",
                "Deutschland · Filme 2024",
                "Deutschland · Erwachsene",
                "Türkei · Action",
                "Eigene",
            ),
            CombinedGroup.ordered(groups).map { it.name },
        )
    }

    @Test
    fun sortPolicy() {
        assertTrue(SortPolicy.alphabetical(GroupSort.AUTO, Combined.ID))
        assertFalse(SortPolicy.alphabetical(GroupSort.AUTO, 1L))
        assertFalse(SortPolicy.alphabetical(GroupSort.PROVIDER, Combined.ID))
        assertTrue(SortPolicy.alphabetical(GroupSort.ALPHABETICAL, 1L))
    }
}
