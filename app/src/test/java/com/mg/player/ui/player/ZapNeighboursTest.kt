package com.mg.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ZapNeighboursTest {

    private val ids = listOf("a", "b", "c", "d")

    @Test
    fun nextThenPrevious() {
        assertEquals(listOf("c", "a"), ZapNeighbours.of(ids, "b"))
    }

    @Test
    fun wrapsAroundAtBothEnds() {
        assertEquals(listOf("b", "d"), ZapNeighbours.of(ids, "a"))
        assertEquals(listOf("a", "c"), ZapNeighbours.of(ids, "d"))
    }

    @Test
    fun twoChannelsHaveOnlyOneNeighbour() {
        assertEquals(listOf("b"), ZapNeighbours.of(listOf("a", "b"), "a"))
    }

    @Test
    fun nothingToZapToWithOneChannelOrAnUnknownCurrent() {
        assertEquals(emptyList<String>(), ZapNeighbours.of(listOf("a"), "a"))
        assertEquals(emptyList<String>(), ZapNeighbours.of(ids, "x"))
        assertEquals(emptyList<String>(), ZapNeighbours.of(emptyList(), "a"))
    }
}
