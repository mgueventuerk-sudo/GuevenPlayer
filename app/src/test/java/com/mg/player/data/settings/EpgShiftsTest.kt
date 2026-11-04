package com.mg.player.data.settings

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.ChannelWithNow
import com.mg.player.data.db.entity.EpgProgramEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.repository.ChannelPagingSource
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** EPG-Zeitverschiebung je Zugang (3.32): Übernahme des alten gemeinsamen Werts und Anwendung je EPG-Zugang. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EpgShiftsTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    private class Settings(initial: AppSettings) : SettingsRepository {
        private val value = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = value
        override suspend fun current() = value.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = value.update(transform)
    }

    private suspend fun playlist(name: String): Long = db.playlistDao().insert(
        PlaylistEntity(
            name = name,
            type = "M3U",
            serverUrl = "",
            username = "",
            password = "",
            m3uUrl = "",
            customEpgUrl = "",
            headerEpgUrl = "",
            lastUpdatedMs = 0,
        ),
    )

    @Test
    fun legacyValueAppliesUntilAdoptedThenEachPlaylistKeepsItsOwn() = runBlocking {
        val a = playlist("A")
        val b = playlist("B")
        val settings = Settings(AppSettings(epgShiftHours = 2))
        val shifts = EpgShifts(db, settings)
        // Vor der Übernahme gilt der alte Wert für alle.
        assertEquals(2 * HOUR, shifts.forPlaylist(a))
        assertEquals(2 * HOUR, shifts.forPlaylist(b))

        shifts.adoptLegacy()
        assertEquals(0, settings.current().epgShiftHours)
        assertEquals(2 * HOUR, shifts.forPlaylist(a))
        assertEquals(2 * HOUR, shifts.forPlaylist(b))

        db.playlistDao().setEpgShiftHours(b, -1)
        assertEquals(2 * HOUR, shifts.forPlaylist(a))
        assertEquals(-1 * HOUR, shifts.forPlaylist(b))
        // Zweite Übernahme ändert nichts mehr.
        shifts.adoptLegacy()
        assertEquals(-1 * HOUR, shifts.forPlaylist(b))
        // Unbekannter Zugang (z. B. „Alle Zugänge“): keine Verschiebung.
        assertEquals(0L, shifts.forPlaylist(999))
    }

    @Test
    fun nowPlayingUsesTheShiftOfTheEpgPlaylistPerRow() = runBlocking {
        val a = playlist("A")
        val b = playlist("B")
        db.playlistDao().setEpgShiftHours(a, 2)
        val shifts = EpgShifts(db, Settings(AppSettings()))
        val now = 1_800_000_000_000L
        // Anbieter A sendet Zeiten 2 h zu früh: die laufende Sendung steht dort 2 h vor „jetzt“.
        db.epgDao().insertPrograms(
            listOf(
                EpgProgramEntity(
                    playlistId = a,
                    channelKey = "ard",
                    startMs = now - 2 * HOUR - MIN10,
                    endMs = now - 2 * HOUR + MIN50,
                    title = "A läuft",
                    description = null,
                ),
                EpgProgramEntity(playlistId = b, channelKey = "zdf", startMs = now - MIN10, endMs = now + MIN50, title = "B läuft", description = null),
                EpgProgramEntity(
                    playlistId = b,
                    channelKey = "zdf",
                    startMs = now - 2 * HOUR - MIN10,
                    endMs = now - 2 * HOUR + MIN50,
                    title = "B alt",
                    description = null,
                ),
            ),
        )
        // „Alle Zugänge“: beide Sender in einer Liste, das EPG kommt aus dem jeweiligen Original-Zugang.
        val rows = listOf(channel("ard", epgPlaylist = a), channel("zdf", epgPlaylist = b))
        val source = ChannelPagingSource(db, playlistId = -1, nowMs = now, shiftFor = shifts::forPlaylist, onTablesChanged = { _, _ -> }) { _, _ -> rows }
        val page = source.load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page
        val ard = page.data[0].value
        val zdf = page.data[1].value
        assertEquals("A läuft", ard.nowTitle)
        assertEquals(now - MIN10, ard.nowStart)
        assertEquals("B läuft", zdf.nowTitle)
        assertEquals(now - MIN10, zdf.nowStart)
    }

    private fun channel(key: String, epgPlaylist: Long) = ChannelWithNow(
        ChannelEntity(
            id = "c:$key", playlistId = -1, categoryId = "x", remoteId = key, name = key, logoUrl = null, streamUrl = "",
            epgChannelId = key, normalizedName = key, epgKey = key, sortIndex = 0, epgPlaylistId = epgPlaylist,
        ),
        nowTitle = null,
        nowStart = null,
        nowEnd = null,
        variants = 1,
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val MIN10 = 600_000L
        const val MIN50 = 3_000_000L
    }
}
