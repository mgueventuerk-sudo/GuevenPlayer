package com.mg.player.data.sport.proof

import com.mg.player.core.util.StreamMemory
import com.mg.player.domain.model.MatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStatsTest {

    private class MemStore : StreamMemory.Store {
        val map = LinkedHashMap<String, String>()
        override fun all(): Map<String, String> = map.toMap()
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(keys: Collection<String>) { keys.forEach(map::remove) }
    }

    private val league = "espn:soccer/ger.1"

    // Sonntag, 8. November 2026, 20:30 Uhr in Berlin (19:30 UTC).
    private val friday = 1_794_166_200_000L
    private val week = 7L * 24 * 60 * 60 * 1000

    private fun guess(source: MatchSource, hit: Boolean) = SourceStats.Guess(source, 1L, hit)

    @Test
    fun rateNeedsFiveGamesAndCountsEachGameOnce() {
        val stats = SourceStats(MemStore())
        repeat(4) { stats.learn("g$it", league, friday, "sky", listOf(guess(MatchSource.EPG, it % 2 == 0))) }
        assertNull(stats.ratePercent(MatchSource.EPG, league, 1L))
        stats.learn("g4", league, friday, "sky", listOf(guess(MatchSource.EPG, true)))
        stats.learn("g4", league, friday, "sky", listOf(guess(MatchSource.EPG, false)))
        assertEquals(60, stats.ratePercent(MatchSource.EPG, league, 1L))
        assertEquals(60, stats.overallPercent(MatchSource.EPG))
    }

    @Test
    fun sourceIsUselessOnlyAfterZeroOfFive() {
        val stats = SourceStats()
        repeat(4) { stats.learn("g$it", league, friday, "sky", listOf(guess(MatchSource.KEYWORD, false))) }
        assertFalse(stats.isUseless(MatchSource.KEYWORD, league, 1L))
        stats.learn("g4", league, friday, "sky", listOf(guess(MatchSource.KEYWORD, false)))
        assertTrue(stats.isUseless(MatchSource.KEYWORD, league, 1L))
        assertFalse(stats.isUseless(MatchSource.KEYWORD, league, 2L))
    }

    @Test
    fun timeSlotPredictsAfterTwoProofsInSameSlot() {
        val stats = SourceStats()
        stats.learn("a", league, friday, "sky", emptyList())
        assertTrue(stats.slotStations(league, friday + week).isEmpty())
        stats.learn("b", league, friday + week, "sky", emptyList())
        assertEquals(listOf("sky"), stats.slotStations(league, friday + 2 * week))
        assertTrue(stats.slotStations(league, friday + 60 * 60 * 1000).isEmpty())
        assertTrue(stats.slotStations("espn:soccer/eng.1", friday + 2 * week).isEmpty())
    }

    @Test
    fun survivesRestart() {
        val store = MemStore()
        val first = SourceStats(store)
        repeat(5) { first.learn("g$it", league, friday + it * week, "sky", listOf(guess(MatchSource.STATION, true))) }
        val second = SourceStats(store, clock = { friday })
        assertEquals(100, second.ratePercent(MatchSource.STATION, league, 1L))
        assertEquals(listOf("sky"), second.slotStations(league, friday))
        second.learn("g0", league, friday, "sky", listOf(guess(MatchSource.STATION, false)))
        assertEquals(100, second.ratePercent(MatchSource.STATION, league, 1L))
    }

    @Test
    fun forgetsCountedGamesAfterAWeek() {
        val store = MemStore()
        SourceStats(store).learn("g0", league, friday, "sky", emptyList())
        SourceStats(store, clock = { friday + 2 * week })
        assertFalse(store.map.keys.any { it.startsWith("d:") })
    }
}
