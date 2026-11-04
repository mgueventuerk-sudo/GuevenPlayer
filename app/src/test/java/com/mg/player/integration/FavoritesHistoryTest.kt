package com.mg.player.integration

import androidx.paging.testing.asSnapshot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.repository.PlaybackRepositoryImpl
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Favoriten, "Zuletzt gesehen" und "Weiterschauen" nach dem Umbau auf Index-Abfragen – gleiche Ergebnisse wie vorher. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FavoritesHistoryTest {

    private val xtream = FakeXtreamServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var pid = 0L

    private fun playbackRepository() = PlaybackRepositoryImpl(container.database, noHistory = { false })

    @Before
    fun setUp() = runBlocking {
        val base = xtream.start()
        pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = base, username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
    }

    @After
    fun tearDown() = xtream.shutdown()

    private fun liveId(stream: String) = "$pid:L:$stream"

    @Test
    fun favoritesShowOnlyExplicitlySelectedChannelsWhenGroupIsPinned() = runBlocking {
        val playback = playbackRepository()
        playback.toggleFavorite(ContentType.LIVE, liveId("105")) // RTL
        Thread.sleep(5)
        playback.toggleFavorite(ContentType.LIVE, liveId("104")) // Das Erste – neuer, steht also oben
        val news = container.categoryRepository.observeAll(pid, ContentType.LIVE).first().first { it.name == "DE | NACHRICHTEN" }
        container.categoryRepository.save(pid, news.copy(pinned = true))

        val ids = container.liveRepository.channelIds(pid, Category.FAVORITES)
        assertEquals(listOf(liveId("104"), liveId("105")), ids)
        val page = container.liveRepository.channels(pid, Category.FAVORITES).asSnapshot()
        assertEquals(ids, page.map { it.id })
    }

    @Test
    fun recentChannelsNewestFirst() = runBlocking {
        listOf("101", "201", "109").forEach {
            playbackRepository().markChannelWatched(liveId(it))
            Thread.sleep(5)
        }
        assertEquals(listOf(liveId("109"), liveId("201"), liveId("101")), container.liveRepository.channelIds(pid, Category.RECENT))
        assertEquals(
            listOf(liveId("109"), liveId("201"), liveId("101")),
            container.liveRepository.channels(pid, Category.RECENT).asSnapshot().map { it.id },
        )
    }

    @Test
    fun continueWatchingOnlyUnfinishedMovies() = runBlocking {
        val playback = playbackRepository()
        playback.saveProgress(PlaybackProgress.movieKey("$pid:M:501"), 60_000, 7_000_000, null)
        Thread.sleep(5)
        playback.saveProgress(PlaybackProgress.movieKey("$pid:M:502"), 6_900_000, 7_000_000, null) // fast fertig = gesehen
        Thread.sleep(5)
        playback.saveProgress(PlaybackProgress.movieKey("$pid:M:503"), 120_000, 7_000_000, null)
        val movies = container.vodRepository.movies(pid, Category.CONTINUE).asSnapshot()
        assertEquals(listOf("$pid:M:503", "$pid:M:501"), movies.map { it.id })
    }

    @Test
    fun movieAndSeriesFavorites() = runBlocking {
        val playback = playbackRepository()
        playback.toggleFavorite(ContentType.MOVIE, "$pid:M:504")
        playback.toggleFavorite(ContentType.SERIES, "$pid:S:702")
        val comedy = container.categoryRepository.observeAll(pid, ContentType.MOVIE).first().first { it.name == "DE | KOMÖDIE" }
        container.categoryRepository.save(pid, comedy.copy(pinned = true))
        val germanSeries = container.categoryRepository.observeAll(pid, ContentType.SERIES).first().first { it.name == "DE | SERIEN" }
        container.categoryRepository.save(pid, germanSeries.copy(pinned = true))
        assertEquals(listOf("$pid:M:504"), container.vodRepository.movies(pid, Category.FAVORITES).asSnapshot().map { it.id })
        assertEquals(listOf("$pid:S:702"), container.seriesRepository.series(pid, Category.FAVORITES).asSnapshot().map { it.id })
    }
}
