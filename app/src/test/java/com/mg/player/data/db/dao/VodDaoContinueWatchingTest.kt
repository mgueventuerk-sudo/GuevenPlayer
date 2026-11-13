package com.mg.player.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.db.entity.ProgressEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Funktions-Audit vermutete eine Lücke bei "Weiterschauen" für fast fertig gesehene Filme, ohne das an
 * VodDao zu verifizieren. Das ist hier nachgeholt: pageContinue() filtert bereits watched = 0 heraus
 * (Schwelle 0.96 in PlaybackRepositoryImpl.save()), ein fast fertig gesehener Film bleibt also nicht in der
 * Liste. Kein neuer Code nötig, nur der Beleg dafür.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VodDaoContinueWatchingTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    @Test
    fun finishedMoviesStayOutOfContinueWatchingButInProgressOnesStayIn() = runBlocking {
        val playlist = db.playlistDao().insert(
            PlaylistEntity(
                name = "Test", type = "M3U", serverUrl = "", username = "", password = "",
                m3uUrl = "", customEpgUrl = "", headerEpgUrl = "", lastUpdatedMs = 0,
            ),
        )
        db.vodDao().insertAll(
            listOf("in-progress", "finished", "never-started").map { id ->
                MovieEntity(
                    id = id, playlistId = playlist, categoryId = "category", remoteId = "", name = id,
                    posterUrl = null, rating = null, year = null, streamUrl = "https://example.invalid/$id", sortIndex = 0,
                )
            },
        )
        db.progressDao().upsert(
            ProgressEntity("movie:in-progress", positionMs = 1_200_000, durationMs = 7_200_000, watched = false, updatedAtMs = 2, seriesId = null),
        )
        db.progressDao().upsert(
            ProgressEntity("movie:finished", positionMs = 6_912_000, durationMs = 7_200_000, watched = true, updatedAtMs = 1, seriesId = null),
        )
        db.progressDao().upsert(
            ProgressEntity("movie:never-started", positionMs = 0, durationMs = 7_200_000, watched = false, updatedAtMs = 3, seriesId = null),
        )

        val continueWatching = db.vodDao().pageContinue(playlist, limit = 10, offset = 0)

        assertEquals(listOf("in-progress"), continueWatching.map { it.movie.id })
    }
}
