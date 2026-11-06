package com.mg.player.data.combined

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.db.dao.VodSourceRow
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.db.entity.SeriesEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Cursor-Seiten schützen große/fehlerhafte Anbieterzeilen und lassen beim Zusammenführen keine Einträge aus. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CombinedDaoPagingTest {

    private companion object {
        const val PAGE_SIZE = 37
        const val ROW_COUNT = 403
    }

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    @Test
    fun channelMovieAndSeriesPagesCoverRowsAndBoundUntrustedText() = runBlocking {
        val playlist = insertPlaylist()
        val longText = "x".repeat(100_000)
        insertFixtureRows(playlist, longText)

        val channels = readChannelPages(playlist)
        val movies = readVodPages(playlist, db.combinedDao()::movieSourcePage)
        val series = readVodPages(playlist, db.combinedDao()::seriesSourcePage)

        assertEquals(ROW_COUNT, channels.size)
        assertEquals(ROW_COUNT, movies.size)
        assertEquals(ROW_COUNT, series.size)
        assertEquals(2_048, channels.first { it.id == "channel-0" }.name.length)
        assertEquals(4_096, channels.first { it.id == "channel-0" }.logoUrl?.length)
        assertEquals(2_048, movies.first { it.id == "movie-0" }.name.length)
        assertEquals(4_096, series.first { it.id == "series-0" }.posterUrl?.length)
    }

    private suspend fun insertPlaylist(): Long =
        db.playlistDao().insert(
            PlaylistEntity(
                name = "Test",
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

    private suspend fun insertFixtureRows(playlist: Long, longText: String) {
        db.channelDao().insertAll((0 until ROW_COUNT).map { i ->
            ChannelEntity(
                id = "channel-$i",
                playlistId = playlist,
                categoryId = "category",
                remoteId = "",
                name = if (i == 0) longText else "Channel $i",
                logoUrl = if (i == 0) longText else null,
                streamUrl = "https://example.invalid/$i",
                epgChannelId = "",
                normalizedName = "channel$i",
                epgKey = "",
                sortIndex = i,
            )
        })
        db.vodDao().insertAll((0 until ROW_COUNT).map { i ->
            MovieEntity(
                id = "movie-$i",
                playlistId = playlist,
                categoryId = "category",
                remoteId = "",
                name = if (i == 0) longText else "Movie $i",
                posterUrl = if (i == 0) longText else null,
                rating = null,
                year = null,
                streamUrl = "https://example.invalid/$i",
                sortIndex = i,
            )
        })
        db.seriesDao().insertAll((0 until ROW_COUNT).map { i ->
            SeriesEntity(
                id = "series-$i",
                playlistId = playlist,
                categoryId = "category",
                remoteId = "",
                name = if (i == 0) longText else "Series $i",
                posterUrl = if (i == 0) longText else null,
                rating = null,
                year = null,
                plot = null,
                backdropUrl = null,
                sortIndex = i,
            )
        })
    }

    private suspend fun readChannelPages(playlist: Long): List<ChannelSourceRow> = buildList {
        var after = 0L
        while (true) {
            val page = db.combinedDao().channelSourcePage(playlist, after, PAGE_SIZE)
            if (page.isEmpty()) break
            assertTrue(page.size <= PAGE_SIZE)
            addAll(page)
            after = page.last().rowId
        }
    }

    private suspend fun readVodPages(
        playlist: Long,
        loadPage: suspend (Long, Long, Int) -> List<VodSourceRow>,
    ): List<VodSourceRow> = buildList {
        var after = 0L
        while (true) {
            val page = loadPage(playlist, after, PAGE_SIZE)
            if (page.isEmpty()) break
            assertTrue(page.size <= PAGE_SIZE)
            addAll(page)
            after = page.last().rowId
        }
    }
}
