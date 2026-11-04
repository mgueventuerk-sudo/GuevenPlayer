package com.mg.player.integration

import androidx.paging.testing.asSnapshot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.db.entity.EpisodeEntity
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.navigation.resolveStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Kindersicherung in den virtuellen Listen: Favoriten, "Zuletzt gesehen" und "Weiterschauen" dürfen gesperrte
 * Inhalte nicht zeigen, und der Start mit dem letzten Sender geht nicht an der PIN vorbei.
 * Dazu: Bei Xtream nachgeladene Episoden überleben eine Aktualisierung.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ParentalLockListsTest {

    private val xtream = FakeXtreamServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        val base = xtream.start()
        pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = base, username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        container.settingsRepository.update { it.copy(activePlaylistId = pid, autoUpdateOnStart = false) }
    }

    @After
    fun tearDown() = runBlocking {
        container.parentalControl.removePin()
        container.settingsRepository.update { it.copy(lastChannelId = null, lastChannelCategoryId = null, startWithLastChannel = false) }
        xtream.shutdown()
    }

    private suspend fun lock(type: ContentType, name: String) {
        val category = container.categoryRepository.observeAll(pid, type).first().first { it.name == name }
        container.categoryRepository.save(pid, category.copy(locked = true))
    }

    private suspend fun categoryId(type: ContentType, name: String) = container.categoryRepository.observeAll(pid, type).first().first { it.name == name }.id

    @Test
    fun lockedChannelsLeaveFavoritesAndRecent() = runBlocking {
        val playback = container.playbackRepository
        playback.toggleFavorite(ContentType.LIVE, "$pid:L:108") // n-tv, Gruppe NACHRICHTEN
        playback.toggleFavorite(ContentType.LIVE, "$pid:L:105") // RTL, Gruppe VOLLPROGRAMM
        playback.markChannelWatched("$pid:L:109")
        playback.markChannelWatched("$pid:L:201")
        assertTrue(container.liveRepository.channelIds(pid, Category.FAVORITES).contains("$pid:L:108"))

        lock(ContentType.LIVE, "DE | NACHRICHTEN")

        val favorites = container.liveRepository.channelIds(pid, Category.FAVORITES)
        assertEquals(listOf("$pid:L:105"), favorites)
        assertEquals(favorites, container.liveRepository.channels(pid, Category.FAVORITES).asSnapshot().map { it.id })
        val recent = container.liveRepository.channelIds(pid, Category.RECENT)
        assertEquals(listOf("$pid:L:201"), recent)
        assertEquals(recent, container.liveRepository.channels(pid, Category.RECENT).asSnapshot().map { it.id })
    }

    @Test
    fun lockedMoviesAndSeriesLeaveFavoritesAndContinue() = runBlocking {
        val playback = container.playbackRepository
        playback.saveProgress(PlaybackProgress.movieKey("$pid:M:501"), 60_000, 7_000_000, null) // ACTION
        playback.saveProgress(PlaybackProgress.movieKey("$pid:M:503"), 60_000, 7_000_000, null) // KOMÖDIE
        playback.toggleFavorite(ContentType.MOVIE, "$pid:M:502")
        playback.toggleFavorite(ContentType.MOVIE, "$pid:M:504")
        playback.saveProgress(PlaybackProgress.episodeKey("$pid:E:1"), 60_000, 2_000_000, "$pid:S:701") // DE | SERIEN
        playback.saveProgress(PlaybackProgress.episodeKey("$pid:E:2"), 60_000, 2_000_000, "$pid:S:703") // EN | SERIES
        playback.toggleFavorite(ContentType.SERIES, "$pid:S:702")

        lock(ContentType.MOVIE, "DE | ACTION")
        lock(ContentType.SERIES, "DE | SERIEN")

        val vod = container.vodRepository
        assertEquals(listOf("$pid:M:503"), vod.movies(pid, Category.CONTINUE).asSnapshot().map { it.id })
        assertEquals(listOf("$pid:M:504"), vod.movies(pid, Category.FAVORITES).asSnapshot().map { it.id })
        val series = container.seriesRepository
        assertEquals(listOf("$pid:S:703"), series.series(pid, Category.CONTINUE).asSnapshot().map { it.id })
        assertTrue(series.series(pid, Category.FAVORITES).asSnapshot().isEmpty())
    }

    @Test
    fun lastChannelFromLockedGroupDoesNotAutoStart() = runBlocking {
        val news = categoryId(ContentType.LIVE, "DE | NACHRICHTEN")
        container.settingsRepository.update {
            it.copy(startWithLastChannel = true, lastChannelId = "$pid:L:108", lastChannelCategoryId = Category.FAVORITES)
        }
        assertNotNull("ohne Sperre startet der Sender", resolveStart(container).resumeChannel)

        container.parentalControl.setPin("1234")
        lock(ContentType.LIVE, "DE | NACHRICHTEN")
        assertNull("Sender aus gesperrter Gruppe", resolveStart(container).resumeChannel)
        container.settingsRepository.update { it.copy(lastChannelId = "$pid:L:105", lastChannelCategoryId = news) }
        assertNull("gesperrte Gruppe als Zapping-Liste", resolveStart(container).resumeChannel)

        container.settingsRepository.update { it.copy(lastChannelCategoryId = Category.ALL) }
        assertEquals("$pid:L:105", resolveStart(container).resumeChannel?.channelId)
    }

    @Test
    fun lazilyLoadedEpisodesSurviveRefresh() = runBlocking {
        val dao = container.database.seriesDao()
        dao.insertEpisodes(
            listOf(
                episode("$pid:E:9001", "$pid:S:701"),
                // Episode einer Serie, die es beim Anbieter nicht mehr gibt: wird aufgeräumt.
                episode("$pid:E:9002", "$pid:S:999"),
            ),
        )
        container.playlistRepository.refreshContent(pid) {}
        assertEquals(listOf("$pid:E:9001"), dao.episodes("$pid:S:701").map { it.id })
        assertTrue(dao.episodes("$pid:S:999").isEmpty())
        assertFalse(container.seriesRepository.episodes("$pid:S:701").isEmpty())
    }

    private fun episode(id: String, seriesId: String) = EpisodeEntity(
        id = id, seriesId = seriesId, playlistId = pid, season = 1, episode = 1, title = "Folge 1",
        streamUrl = "http://x.invalid/$id.mkv", imageUrl = null, plot = null, durationSec = null,
    )
}
