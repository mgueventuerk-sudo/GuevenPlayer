package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.tmdb.TmdbApi
import com.mg.player.data.tmdb.TmdbRepositoryImpl
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.TmdbRecommendation
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.domain.model.PlaylistType
import com.mg.player.screenshots.Showcase
import com.mg.player.screenshots.ShowcaseData
import com.mg.player.screenshots.ShowcaseServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TmdbHomeRecommendationsTest {

    private val playlistServer = ShowcaseServer()
    private val otherPlaylistServer = ShowcaseServer()
    private val tmdbServer = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private lateinit var data: ShowcaseData
    private var otherPlaylistId = 0L

    @Before
    fun setUp() = runBlocking {
        data = Showcase.prepare(playlistServer, container)
        otherPlaylistId = container.playlistRepository.addPlaylist(
            PlaylistInput(
                name = "Zweiter Zugang",
                type = PlaylistType.XTREAM,
                serverUrl = otherPlaylistServer.start(),
                username = "demo",
                password = "demo",
                allowInsecureHttp = true,
            ),
        )
        container.playlistRepository.refreshContent(otherPlaylistId) {}
        tmdbServer.start()
    }

    @After
    fun tearDown() {
        playlistServer.shutdown()
        otherPlaylistServer.shutdown()
        tmdbServer.shutdown()
    }

    @Test
    fun tmdbHomeCatalogIncludesTitlesMissingFromPlaylistsAndFindsCopiesAcrossAllPlaylists() = runBlocking {
        container.settingsRepository.update {
            it.copy(tmdbApiKey = "test-tmdb-key", tmdbEnabled = true, onlineExtras = true, activePlaylistId = null)
        }
        val body = """{"results":[
            {"id":901,"title":"Nordlicht","original_title":"Nordlicht","name":"Stadt der Lichter","original_name":"City of Lights","overview":"TMDB Handlung","vote_average":8.7,"vote_count":1400,"release_date":"2023-02-03","first_air_date":"2023-06-07","poster_path":"/poster.jpg","backdrop_path":"/backdrop.jpg"},
            {"id":902,"title":"Nicht in meiner Mediathek","name":"Nicht in meiner Mediathek","vote_average":9.9,"vote_count":2000,"release_date":"2026-01-01","first_air_date":"2026-01-01"}
        ]}"""
        repeat(4) { tmdbServer.enqueue(MockResponse().setBody(body)) }
        val repository = TmdbRepositoryImpl(
            db = container.database,
            api = TmdbApi(OkHttpClient(), tmdbServer.url("/3/").toString()),
            settings = container.settingsRepository,
            search = container.searchRepository,
        )

        val recommendations = repository.homeRecommendations()

        assertEquals(listOf("Nordlicht", "Nicht in meiner Mediathek"), recommendations.newestMovies.map { it.title })
        assertEquals(listOf("Nordlicht", "Nicht in meiner Mediathek"), recommendations.topRatedMovies.map { it.title })
        assertEquals(listOf("Stadt der Lichter", "Nicht in meiner Mediathek"), recommendations.newestSeries.map { it.title })
        assertEquals(listOf("Stadt der Lichter", "Nicht in meiner Mediathek"), recommendations.topRatedSeries.map { it.title })
        assertEquals(ContentType.MOVIE, recommendations.newestMovies.first().type)
        assertEquals("TMDB Handlung", recommendations.newestMovies.first().overview)
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", recommendations.newestMovies.first().backdropUrl)
        assertEquals("Nordlicht", recommendations.newestMovies.first().originalTitle)
        val movieCopies = repository.findPlayableCopies(recommendations.newestMovies.first())
        assertEquals(setOf(data.movieId, "$otherPlaylistId:M:601"), movieCopies.map { it.itemId }.toSet())
        assertEquals(setOf(data.playlistId, otherPlaylistId), movieCopies.map { it.playlistId }.toSet())
        val misspelled = recommendations.newestMovies.first().copy(title = "Nordlichtt", originalTitle = null)
        val typoCopies = repository.findPlayableCopies(misspelled)
        assertEquals(setOf(data.movieId, "$otherPlaylistId:M:601"), typoCopies.map { it.itemId }.toSet())
        assertTrue(typoCopies.all { it.isApproximate })
        assertTrue(repository.findPlayableCopies(recommendations.newestMovies.first().copy(year = "1999")).isEmpty())
        assertTrue(repository.findPlayableCopies(recommendations.newestMovies.last()).isEmpty())
        val seriesCopies = repository.findPlayableCopies(recommendations.newestSeries.first())
        assertEquals(setOf(data.seriesId, "$otherPlaylistId:S:701"), seriesCopies.map { it.itemId }.toSet())
        assertTrue((0 until 4).all { tmdbServer.takeRequest().requestUrl?.queryParameter("api_key") == "test-tmdb-key" })
        assertEquals(4, tmdbServer.requestCount)

        repository.homeRecommendations()
        assertEquals("Cache verhindert erneute TMDB-Abfragen", 4, tmdbServer.requestCount)
    }

    @Test
    fun missingKeyDoesNotMakeNetworkRequests() = runBlocking {
        container.settingsRepository.update { it.copy(tmdbApiKey = "", tmdbEnabled = true, onlineExtras = true) }
        val repository = TmdbRepositoryImpl(
            db = container.database,
            api = TmdbApi(OkHttpClient(), tmdbServer.url("/3/").toString()),
            settings = container.settingsRepository,
            search = container.searchRepository,
        )

        val recommendations = repository.homeRecommendations()

        assertTrue(recommendations.newestMovies.isEmpty())
        assertEquals(0, tmdbServer.requestCount)
    }
}
