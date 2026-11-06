package com.mg.player.domain.sport

import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.MatchedEvent
import com.mg.player.domain.model.SportEvent
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportEventFilterTest {

    private val zone = TimeZone.getTimeZone("Europe/Berlin")
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    /** Sa, 31.10.2026, 12:00 Ortszeit. */
    private val now = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.OCTOBER, 31, 12, 0)
    }.timeInMillis

    private fun item(id: String, start: Long, status: EventStatus = EventStatus.PRE, end: Long? = null, sport: String = "soccer") =
        MatchedEvent(SportEvent(id, sport, "Bundesliga", id, "A", "B", start, end, status, emptyList()), "ch", "Sender", "cat")

    @Test
    fun liveWindowAndPostPrecedence() {
        val f = SportEventFilter
        // 15 min vor Anpfiff gilt als live, 16 min vorher noch nicht.
        assertTrue(f.isLive(item("a", now + 15 * min), now))
        assertFalse(f.isLive(item("a", now + 16 * min), now))
        // Ohne Ende: Fußball 120 min + 15 min Nachlauf.
        assertTrue(f.isLive(item("b", now - 135 * min), now))
        assertFalse(f.isLive(item("b", now - 136 * min), now))
        // Beendet hat Vorrang – auch mitten im Zeitfenster.
        assertFalse(f.isLive(item("c", now - 30 * min, EventStatus.POST), now))
        // Laut Quelle läuft es: auch außerhalb des geschätzten Fensters (Verlängerung), aber nicht ewig.
        assertTrue(f.isLive(item("d", now - 4 * hour, EventStatus.IN), now))
        assertFalse(f.isLive(item("d", now - 9 * hour, EventStatus.IN), now))
    }

    @Test
    fun upcomingIsLimitedToSevenDays() {
        val f = SportEventFilter
        assertTrue(f.isUpcoming(item("a", now + 7 * day), now))
        assertFalse(f.isUpcoming(item("a", now + 7 * day + 1), now))
        assertFalse(f.isUpcoming(item("a", now - 1), now))
        assertFalse(f.isUpcoming(item("a", now + hour, EventStatus.POST), now))
    }

    @Test
    fun groupsByLocalDayAcrossMidnight() {
        // Sa 31.10. 23:30 → heute, So 01.11. 00:30 → morgen (nach Ortszeit, nicht UTC).
        val lateToday = now + 11 * hour + 30 * min
        val afterMidnight = now + 12 * hour + 30 * min
        val board = SportEventFilter.categorize(
            listOf(
                item("live", now - 10 * min),
                item("late", lateToday),
                item("night", afterMidnight),
                item("week", now + 3 * day),
                item("far", now + 8 * day),
            ),
            now,
            zone,
        )
        assertEquals(listOf("live"), board.live.map { it.event.id })
        assertEquals(listOf("late"), board.upcoming[UpcomingGroup.TODAY]?.map { it.event.id })
        assertEquals(listOf("night"), board.upcoming[UpcomingGroup.TOMORROW]?.map { it.event.id })
        assertEquals(listOf("week"), board.upcoming[UpcomingGroup.THIS_WEEK]?.map { it.event.id })
        assertEquals("live", board.firstFocus?.event?.id)
        assertFalse(board.isEmpty)
    }

    @Test
    fun sectionsAreSplitBySportOnlyWhenMixed() {
        // Nur Fußball: keine Unterüberschriften.
        val soccerOnly = SportEventFilter.categorize(listOf(item("a", now + hour), item("b", now + 2 * hour)), now, zone)
        assertFalse(soccerOnly.mixed)
        assertEquals(listOf(""), soccerOnly.sections().single().groups.map { it.sport })

        // Gemischt: je Abschnitt nach Sportart (Reihenfolge der Ligenauswahl), darin nach Anstoß.
        val board = SportEventFilter.categorize(
            listOf(
                item("f1", now + hour, sport = "racing"),
                item("ufc", now + 2 * hour, sport = "mma"),
                item("bl", now + 3 * hour),
                item("bl2", now + 4 * hour),
                item("moto", now + 5 * hour, sport = "motogp"),
                item("nba-live", now - 10 * min, sport = "basketball"),
                item("darts", now + day, sport = "darts"),
            ),
            now,
            zone,
        )
        assertTrue(board.mixed)
        val sections = board.sections()
        assertEquals(listOf("Jetzt live", "Heute", "Morgen"), sections.map { it.title })
        assertEquals(listOf("US-Sport"), sections[0].groups.map { it.sport })
        assertEquals(listOf("Fußball", "Kampfsport", "Motorsport"), sections[1].groups.map { it.sport })
        assertEquals(listOf("bl", "bl2"), sections[1].groups[0].events.map { it.event.id })
        assertEquals(listOf("f1", "moto"), sections[1].groups[2].events.map { it.event.id })
        assertEquals(listOf("Weitere Sportarten"), sections[2].groups.map { it.sport })
        // Fokus: oberstes angezeigtes Spiel.
        assertEquals("nba-live", board.firstFocus?.event?.id)
        val noLive = SportEventFilter.categorize(listOf(item("f1", now + hour, sport = "racing"), item("bl", now + 3 * hour)), now, zone)
        assertEquals("bl", noLive.firstFocus?.event?.id)
    }

    @Test
    fun dayIndexIsContinuousOverYearEnd() {
        val utc = TimeZone.getTimeZone("UTC")
        val dec31 = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.DECEMBER, 31, 12, 0)
        }.timeInMillis
        assertEquals(1L, SportEventFilter.dayIndex(dec31 + day, utc) - SportEventFilter.dayIndex(dec31, utc))
    }

    @Test
    fun emptyBoardHasNoFocus() {
        val board = SportEventFilter.categorize(listOf(item("old", now - day, EventStatus.POST)), now, zone)
        assertTrue(board.isEmpty)
        assertNull(board.firstFocus)
    }

    @Test
    fun countdownFormats() {
        assertEquals("jetzt", formatCountdown(now, now))
        assertEquals("in 23:05", formatCountdown(now + 23 * min + 5_000, now))
        // Eine Einheit je Groessenordnung: Minuten nur, solange sie etwas aendern.
        assertEquals("in 2 Std 12 Min", formatCountdown(now + 2 * hour + 12 * min, now))
        assertEquals("in 4 Std", formatCountdown(now + 4 * hour + 12 * min, now))
        assertEquals("in 1 Tag", formatCountdown(now + day + 3 * hour, now))
        assertEquals("in 2 Tagen", formatCountdown(now + 2 * day + 14 * hour + 23 * min, now))
    }
}
