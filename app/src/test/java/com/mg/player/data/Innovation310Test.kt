package com.mg.player.data

import com.mg.player.core.network.HttpFetcher
import com.mg.player.data.recording.AutoRecordPlanner
import com.mg.player.data.recording.AutoRecordPlanner.Candidate
import com.mg.player.data.repository.HabitRanking
import com.mg.player.data.sport.BzzoiroSource
import com.mg.player.data.sport.EspnJson
import com.mg.player.data.sport.EspnSource
import com.mg.player.domain.model.Recording
import com.mg.player.domain.model.sportScore
import com.mg.player.ui.player.detailOf
import com.mg.player.ui.player.goalsOf
import com.mg.player.ui.player.totalOf
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version 3.10: Live-Ergebnis, „Oft gesehen“, „Automatisch aufnehmen“. */
class Innovation310Test {

    private val minute = 60_000L
    private val now = 1_793_000_000_000L

    @Test
    fun scoreFormat() {
        assertEquals("2:1 · 67'", sportScore("2", "1", "67'"))
        assertEquals("0:0", sportScore("0", "0", null))
        assertNull(sportScore(null, "1", "67'"))
        assertNull(sportScore("null", "1", null))
        assertEquals("2:1", goalsOf("2:1 · 67'"))
        assertEquals("67'", detailOf("2:1 · 67'"))
        assertNull(detailOf("2:1"))
        assertEquals(3, totalOf("2:1"))
        assertEquals(0, totalOf("x:y"))
    }

    @Test
    fun bzzoiroScoreOnlyOnceStarted() {
        val live = JSONObject("""{"status":"inprogress","home_score":2,"away_score":1,"current_minute":67}""")
        assertEquals("2:1 · 67'", BzzoiroSource.scoreOf(live))
        val done = JSONObject("""{"status":"finished","home_score":3,"away_score":3,"current_minute":90}""")
        assertEquals("3:3 · Ende", BzzoiroSource.scoreOf(done))
        assertNull(BzzoiroSource.scoreOf(JSONObject("""{"status":"notstarted","home_score":0,"away_score":0}""")))
        assertNull(BzzoiroSource.scoreOf(JSONObject("""{"status":"postponed","home_score":null,"away_score":null}""")))
    }

    @Test
    fun espnScoreFromCompetitors() {
        val live = EspnJson.event("1", now, "Galatasaray", "Fenerbahce", state = "in")
        val competitors = live.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors")
        competitors.getJSONObject(0).put("score", "2")
        competitors.getJSONObject(1).put("score", "1")
        live.getJSONObject("status").getJSONObject("type").put("shortDetail", "67'")
        val pre = EspnJson.event("2", now + 3_600_000, "Bayern", "Dortmund")
        pre.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors").getJSONObject(0).put("score", "0")
        val events = EspnSource(HttpFetcher(OkHttpClient())).parse(EspnJson.scoreboard(live, pre), EspnSource.LEAGUES.first())
        assertEquals("2:1 · 67'", events.first { it.id.endsWith("#1") }.score)
        assertNull(events.first { it.id.endsWith("#2") }.score)
    }

    @Test
    fun habitsRankByDaypart() {
        assertEquals(0, HabitRanking.daypart(7))
        assertEquals(1, HabitRanking.daypart(12))
        assertEquals(2, HabitRanking.daypart(20))
        assertEquals(3, HabitRanking.daypart(2))
        val counts = HashMap<String, IntArray>()
        repeat(5) { HabitRanking.bump(counts, "news", 0) }
        repeat(3) { HabitRanking.bump(counts, "sport", 2) }
        HabitRanking.bump(counts, "news", 2)
        // Abends: Sport (3·3+3 = 12) vor Nachrichten (1·3+6 = 9); morgens umgekehrt.
        assertEquals(listOf("sport", "news"), HabitRanking.rank(counts, 2, 10))
        assertEquals(listOf("news", "sport"), HabitRanking.rank(counts, 0, 10))
        assertEquals(listOf("news"), HabitRanking.rank(counts, 0, 1))
        assertEquals(listOf(1, 0, 2, 0), HabitRanking.parse("1,0,2,0")?.toList())
        assertNull(HabitRanking.parse("1,2"))
        assertNull(HabitRanking.parse(null))
    }

    @Test
    fun habitsStaySmall() {
        val counts = HashMap<String, IntArray>()
        repeat(HabitRanking.MAX_CHANNELS + 20) { HabitRanking.bump(counts, "c$it", 1) }
        assertEquals(HabitRanking.MAX_CHANNELS, counts.size)
        // Der gerade gezählte Sender fliegt nie heraus.
        assertTrue("c${HabitRanking.MAX_CHANNELS + 19}" in counts)
    }

    @Test
    fun autoRecordPicksEachShowOnceWithoutOverlap() {
        val a = Candidate("ch1", "Galatasaray – Fenerbahçe", null, now + 60 * minute, now + 165 * minute)
        val sameShowOtherChannel = a.copy(channelId = "ch2")
        val overlapping = Candidate("ch3", "Galatasaray Magazin", null, now + 120 * minute, now + 150 * minute)
        val later = Candidate("ch1", "Galatasaray Magazin", null, now + 300 * minute, now + 330 * minute)
        val running = Candidate("ch1", "Galatasaray live", null, now - 10 * minute, now + 20 * minute)
        val picked = AutoRecordPlanner.pick(listOf(later, overlapping, sameShowOtherChannel, a, running), emptyList(), emptyMap(), now)
        assertEquals(listOf(a.startMs, later.startMs), picked.map { it.startMs })
        assertEquals(1, picked.count { it.startMs == a.startMs })
    }

    @Test
    fun autoRecordRespectsExistingAndDeleted() {
        val a = Candidate("ch1", "Tatort", null, now + 60 * minute, now + 150 * minute)
        val busy = listOf((now + 100 * minute)..(now + 200 * minute))
        assertTrue(AutoRecordPlanner.pick(listOf(a), busy, emptyMap(), now).isEmpty())
        val done = mapOf(Recording.programKey("ch1", a.startMs) to a.endMs)
        assertTrue(AutoRecordPlanner.pick(listOf(a), emptyList(), done, now).isEmpty())
        assertEquals(1, AutoRecordPlanner.pick(listOf(a), emptyList(), emptyMap(), now).size)
    }

    @Test
    fun autoRecordKeywords() {
        assertEquals("Galatasaray", AutoRecordPlanner.normalize("  Galatasaray "))
        assertEquals("Der Bergdoktor", AutoRecordPlanner.normalize("Der   Bergdoktor"))
        assertNull(AutoRecordPlanner.normalize("ab"))
        assertEquals("%100 Prozent%", AutoRecordPlanner.pattern("100% Pro_zent"))
    }
}
