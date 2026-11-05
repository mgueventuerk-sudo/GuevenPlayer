package com.mg.player.data.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gleichzeitige Spiele einer Liga auf beIN SPORTS 1, 2, 3 … statt alle auf einem Sender (ab 3.46). */
class ChannelFamilyTest {

    private val hour = 3_600_000L
    private val family = listOf("beIN 1", "beIN 2", "beIN 3")

    @Test
    fun findsTheChannelNumber() {
        assertEquals(1, ChannelFamily.numberOf("|TR| BEIN SPORTS 1 FHD"))
        assertEquals(12, ChannelFamily.numberOf("Sky Sport 12"))
        assertNull(ChannelFamily.numberOf("Sky Sport Bundesliga UHD"))
        // „4K“ oder „1080p“ sind keine Sendernummern.
        assertNull(ChannelFamily.numberOf("DAZN 4K 1080p"))
    }

    @Test
    fun siblingsKeepPrefixAndQuality() {
        val names = ChannelFamily.siblingNames("|TR| BEIN SPORTS 1 FHD")
        assertEquals("|TR| BEIN SPORTS 2 FHD", names.first { it.first == 2 }.second)
        assertEquals(ChannelFamily.MAX_NUMBER, names.size)
        assertTrue(ChannelFamily.siblingNames("Sky Sport Bundesliga").isEmpty())
    }

    @Test
    fun simultaneousGamesGetConsecutiveChannels() {
        val picks = ChannelFamily.allocate(listOf("a" to 0L, "b" to 0L, "c" to 0L), family)
        assertEquals(mapOf("a" to "beIN 1", "b" to "beIN 2", "c" to "beIN 3"), picks)
    }

    @Test
    fun laterGamesReuseFreedChannels() {
        val picks = ChannelFamily.allocate(listOf("early" to 0L, "parallel" to hour, "late" to 3 * hour), family)
        assertEquals("beIN 1", picks["early"])
        assertEquals("beIN 2", picks["parallel"])
        assertEquals("beIN 1", picks["late"])
    }

    @Test
    fun channelsKnownForOtherGamesAreBusy() {
        val picks = ChannelFamily.allocate(listOf("x" to 0L), family, busy = listOf(0L to "beIN 1"))
        assertEquals("beIN 2", picks["x"])
    }

    @Test
    fun noChannelLeftMeansNoGuess() {
        val picks = ChannelFamily.allocate(listOf("a" to 0L, "b" to 0L), listOf("only"))
        assertEquals(mapOf("a" to "only"), picks)
    }
}
