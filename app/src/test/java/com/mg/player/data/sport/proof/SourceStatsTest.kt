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

    /** Ein Spiel, das für diese Quelle **kein** Erkundungsspiel ist (dort greift das Überspringen). */
    private fun skipped(stats: SourceStats, source: MatchSource) =
        (0..200).map { "x$it" }.first { !stats.explores(it, source, 1L) }

    /** Ein Erkundungsspiel: Die Quelle wird trotz schlechter Quote gefragt. */
    private fun explored(stats: SourceStats, source: MatchSource) =
        (0..200).map { "x$it" }.first { stats.explores(it, source, 1L) }

    @Test
    fun weakSourceIsSkippedOnlyAfterFiveGamesAndOnlyOutsideExploration() {
        val stats = SourceStats()
        val source = MatchSource.KEYWORD
        val cold = skipped(stats, source)
        repeat(4) { stats.learn("g$it", league, friday, "sky", listOf(guess(source, false))) }
        assertFalse(stats.shouldSkip(source, league, 1L, cold))
        stats.learn("g4", league, friday, "sky", listOf(guess(source, false)))
        assertTrue(stats.shouldSkip(source, league, 1L, cold))
        // Erkundung: Jedes achte Spiel fragt die Quelle trotzdem – sonst erholt sie sich nie.
        assertFalse(stats.shouldSkip(source, league, 1L, explored(stats, source)))
        // Andere Playlist, andere Wertung.
        assertFalse(stats.shouldSkip(source, league, 2L, cold))
    }

    @Test
    fun exploresRoughlyEveryEighthGameAndStaysStable() {
        val stats = SourceStats()
        val ids = (0 until 4_000).map { "event-$it" }
        val explored = ids.count { stats.explores(it, MatchSource.EPG, 1L) }
        // Ein Achtel von 4000 ist 500 – aus dem Hash, nicht aus dem Zufall, darum eine feste Spanne.
        assertTrue("erkundet: $explored von ${ids.size}", explored in 400..600)
        // Dieselbe Frage, dieselbe Antwort: Die Zuordnung darf zwischen zwei Aufrufen nicht flackern.
        ids.take(50).forEach { assertEquals(stats.explores(it, MatchSource.EPG, 1L), stats.explores(it, MatchSource.EPG, 1L)) }
    }

    @Test
    fun aSourceThatStillHitsSometimesIsNotSkipped() {
        val stats = SourceStats()
        val source = MatchSource.KEYWORD
        // 1 von 5: Obergrenze des 95-%-Intervalls liegt weit über WEAK_MAX – keine Aussage „schwach“.
        stats.learn("h0", league, friday, "sky", listOf(guess(source, true)))
        repeat(4) { stats.learn("h${it + 1}", league, friday, "sky", listOf(guess(source, false))) }
        assertFalse(stats.shouldSkip(source, league, 1L, skipped(stats, source)))
    }

    @Test
    fun rankingUsesTheCautiousRateNotTheRawPercentage() {
        val stats = SourceStats()
        // Eine Quelle mit 1 von 1 (100 %) gegen eine mit 17 von 20 (85 %).
        stats.learn("a0", league, friday, "sky", listOf(guess(MatchSource.KEYWORD, true)))
        repeat(20) { stats.learn("b$it", league, friday, "sky", listOf(guess(MatchSource.EPG, it < 17))) }
        val lucky = stats.rateScore(MatchSource.KEYWORD, league, 1L)
        val proven = stats.rateScore(MatchSource.EPG, league, 1L)
        assertTrue("1 von 1 = $lucky muss unter 17 von 20 = $proven liegen", lucky < proven)
        assertEquals(1 to 1, stats.sample(MatchSource.KEYWORD, league, 1L))
        assertEquals(17 to 20, stats.overallSample(MatchSource.EPG))
        assertNull(stats.overallSample(MatchSource.STATION))
    }

    @Test
    fun wilsonBoundsBracketTheRate() {
        // 0 von 5 ist keine Null: die Obergrenze liegt bei 43 % (Wilson; die exakte Grenze nach Clopper-Pearson
        // waere 52 %). Nach fünf Fehlschlägen ist eine Quelle also nicht "nachweislich nutzlos".
        assertTrue(SourceStats.wilsonUpper(0, 5) in 0.4..0.45)
        assertEquals(0.0, SourceStats.wilsonLower(0, 5), 1e-9)
        // 4 von 5 (80 %) liegt zwischen 30 % und 100 %.
        assertTrue(SourceStats.wilsonLower(4, 5) in 0.3..0.5)
        assertTrue(SourceStats.wilsonUpper(4, 5) > 0.9)
        // Mehr Spiele, engeres Intervall.
        assertTrue(SourceStats.wilsonLower(80, 100) > SourceStats.wilsonLower(4, 5))
        assertTrue(SourceStats.wilsonUpper(80, 100) < SourceStats.wilsonUpper(4, 5))
        // 1 von 30 gilt als schwach, 0 von 5 noch nicht.
        assertTrue(SourceStats.wilsonUpper(1, 30) < SourceStats.WEAK_MAX)
        assertTrue(SourceStats.wilsonUpper(0, 5) > SourceStats.WEAK_MAX)
    }

    @Test
    fun kickoffTimesSnapToAQuarterHourAndUseTheLeaguesTimeZone() {
        val stats = SourceStats()
        // TV-Pick: 20:30 wird zu 20:40 – dasselbe Zeitfenster, darum zählt der zweite Beweis dazu.
        stats.learn("p0", league, friday, "sky", emptyList())
        stats.learn("p1", league, friday + week + 10 * 60_000L, "sky", emptyList())
        assertEquals(listOf("sky"), stats.slotStations(league, friday + 2 * week))
        // Premier League zählt in Londoner Zeit, Bundesliga in Berliner.
        assertEquals("Europe/London", SourceStats.zoneOf("espn:soccer/eng.1").id)
        assertEquals("Europe/Berlin", SourceStats.zoneOf("espn:soccer/ger.1").id)
        // Unbekanntes (Champions League) bleibt bei Mitteleuropa.
        assertEquals("Europe/Berlin", SourceStats.zoneOf("espn:soccer/uefa.champions").id)
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
    fun wrongChannelCountsAsAMissWithoutBlockingALaterProof() {
        val store = MemStore()
        val stats = SourceStats(store)
        val source = MatchSource.KEYWORD
        // Vier Korrekturen „Falscher Sender“ – je Spiel und Quelle nur einmal, auch bei doppeltem Klick.
        repeat(4) { stats.learnMiss("w$it", league, source, 1L, friday) }
        stats.learnMiss("w0", league, source, 1L, friday)
        assertEquals(0 to 4, stats.sample(source, league, 1L))
        // Dasselbe Spiel wird später doch bewiesen: Die Wertung aus dem Beweis zählt trotzdem noch.
        stats.learn("w0", league, friday, "sky", listOf(guess(source, true)))
        assertEquals(1 to 5, stats.sample(source, league, 1L))
        assertEquals(20, stats.ratePercent(source, league, 1L))
    }

    @Test
    fun wrongChannelMarkersSurviveRestartAndExpire() {
        val store = MemStore()
        SourceStats(store).learnMiss("w0", league, MatchSource.KEYWORD, 1L, friday)
        // Nach einem Neustart nicht doppelt zählen …
        SourceStats(store, clock = { friday }).learnMiss("w0", league, MatchSource.KEYWORD, 1L, friday)
        assertEquals(0 to 1, SourceStats(store, clock = { friday }).sample(MatchSource.KEYWORD, league, 1L))
        // … und die Marke nach einer Woche wegwerfen, damit der Speicher nicht endlos wächst.
        SourceStats(store, clock = { friday + 2 * week })
        assertFalse(store.map.keys.any { it.startsWith("w:") })
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
