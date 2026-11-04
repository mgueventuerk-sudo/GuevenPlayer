package com.mg.player.data.sync

import com.mg.player.domain.model.AutoRefreshSchedule
import com.mg.player.domain.model.AutoRefreshSchedule.DAY_MS
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Countdown der automatischen Komplett-Aktualisierung: exakt nach N × 24 h, auch über Ausschaltzeiten hinweg. */
class AutoRefreshTest {

    private val t0 = 1_780_000_000_000L // 2026

    @Test
    fun dueExactlyAfterDaysNotOneMillisecondEarlier() {
        assertFalse(AutoRefreshSchedule.isDue(t0, 3, t0 + 3 * DAY_MS - 1))
        assertTrue(AutoRefreshSchedule.isDue(t0, 3, t0 + 3 * DAY_MS))
        assertEquals(t0 + 3 * DAY_MS, AutoRefreshSchedule.dueAt(t0, 3, t0))
    }

    @Test
    fun timeWhileDeviceWasOffCounts() {
        // Aktualisiert, dann 2 Tage aus, eingeschaltet: Bei 3 Tagen fehlt noch genau 1 Tag; bei 10 Tagen aus sofort fällig.
        assertEquals(DAY_MS, AutoRefreshSchedule.dueAt(t0, 3, t0 + 2 * DAY_MS)!! - (t0 + 2 * DAY_MS))
        assertTrue(AutoRefreshSchedule.isDue(t0, 3, t0 + 10 * DAY_MS))
    }

    @Test
    fun offNeverDueAndNeverLoadedIsDue() {
        assertNull(AutoRefreshSchedule.dueAt(t0, 0, t0 + 400 * DAY_MS))
        assertFalse(AutoRefreshSchedule.isDue(t0, 0, t0 + 400 * DAY_MS))
        assertTrue(AutoRefreshSchedule.isDue(0L, 1, t0))
    }

    @Test
    fun wrongDeviceClockIsHandled() {
        // Uhr nach dem Einschalten noch auf 1970: nichts entscheiden.
        assertFalse(AutoRefreshSchedule.isDue(t0, 1, 5_000L))
        // Letzte Aktualisierung mit vorgehender Uhr gespeichert (2 Tage in der Zukunft): sofort fällig statt ewig warten.
        assertTrue(AutoRefreshSchedule.isDue(t0 + 2 * DAY_MS, 7, t0))
        // Kleine Abweichung (unter 1 h) zählt normal.
        assertFalse(AutoRefreshSchedule.isDue(t0 + 30 * 60 * 1000, 1, t0))
    }

    private class Fake(var playlists: List<Playlist>, var now: Long, val succeed: Boolean = true) {
        val refreshed = ArrayList<Long>()
        val refresher = AutoRefresher(
            playlists = { playlists },
            refresh = { id ->
                refreshed += id
                if (succeed) playlists = playlists.map { if (it.id == id) it.copy(lastUpdatedMs = now) else it }
            },
            clock = { now },
        )
    }

    private fun playlist(id: Long, last: Long, days: Int) =
        Playlist(id, "Z$id", PlaylistType.M3U, "", "", "", "", "", "", last, autoRefreshDays = days)

    @Test
    fun onlyDuePlaylistsAreRefreshedAndCountdownRestarts() = runBlocking {
        val f = Fake(listOf(playlist(1, t0, 1), playlist(2, t0, 3), playlist(3, t0, 0)), t0 + DAY_MS)
        val next = f.refresher.runDue("Test")
        assertEquals(listOf(1L), f.refreshed)
        // Nächster Ablauf: Zugang 1 in einem Tag ab jetzt, Zugang 2 in zwei Tagen → der frühere zählt.
        assertEquals(t0 + 2 * DAY_MS, next)
        f.now = t0 + 3 * DAY_MS
        f.refresher.runDue("Test")
        assertEquals(listOf(1L, 1L, 2L), f.refreshed)
    }

    @Test
    fun failureKeepsPlaylistDueAndRetriesAfterOneHour() = runBlocking {
        val f = Fake(listOf(playlist(1, t0, 2)), t0 + 2 * DAY_MS, succeed = false)
        val next = f.refresher.runDue("Test")
        assertEquals(1, f.refreshed.size)
        assertEquals(t0 + 2 * DAY_MS + AutoRefresher.RETRY_MS, next)
        f.now += AutoRefresher.RETRY_MS - 1
        f.refresher.runDue("Test")
        assertEquals(1, f.refreshed.size)
        f.now += 1
        f.refresher.runDue("Test")
        assertEquals(2, f.refreshed.size)
    }

    @Test
    fun noPlaylistEnabledMeansNothingToPlan() = runBlocking {
        val f = Fake(listOf(playlist(1, t0, 0)), t0)
        assertNull(f.refresher.runDue("Test"))
        assertEquals(AutoRefresher.MAX_WAIT_MS, AutoRefresher.waitMs(null, t0))
        assertEquals(AutoRefresher.MIN_WAIT_MS, AutoRefresher.waitMs(t0 - 1, t0))
        assertEquals(90_000L, AutoRefresher.waitMs(t0 + 90_000L, t0))
    }
}
