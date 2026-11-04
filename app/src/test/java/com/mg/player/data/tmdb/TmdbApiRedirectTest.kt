package com.mg.player.data.tmdb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.domain.model.TmdbHomeFeed
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TmdbApiRedirectTest {

    private val server = MockWebServer()
    private val destination = MockWebServer()

    @Before
    fun start() {
        server.start()
        destination.start()
    }

    @After
    fun stop() {
        server.shutdown()
        destination.shutdown()
    }

    @Test
    fun classicApiKeyIsNotForwardedToRedirectDestination() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/capture")))
        val api = TmdbApi(OkHttpClient(), server.url("/3/").toString())

        assertThrows(IOException::class.java) { api.search("test-key", TmdbApi.Kind.MOVIE, "Film", null) }
        assertTrue(server.takeRequest().requestUrl.toString().contains("api_key=test-key"))
        assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun newestCatalogUsesReleaseDateAndParsesTmdbMetadata() {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"id":42,"title":"Nordlicht","overview":"Beschreibung","vote_average":8.4,"vote_count":1250,""" +
                    """"release_date":"2026-04-05","poster_path":"/poster.jpg","backdrop_path":"/backdrop.jpg"}]}""",
            ),
        )
        val api = TmdbApi(OkHttpClient(), server.url("/3/").toString())

        val result = api.homeCatalog("test-key", TmdbApi.Kind.MOVIE, TmdbHomeFeed.NEWEST).single()
        val request = server.takeRequest()

        assertEquals("discover/movie", request.requestUrl?.encodedPath?.removePrefix("/3/"))
        assertNotNull(request.requestUrl?.queryParameter("primary_release_date.lte"))
        assertEquals("primary_release_date.desc", request.requestUrl?.queryParameter("sort_by"))
        assertEquals("test-key", request.requestUrl?.queryParameter("api_key"))
        assertEquals(42, result.id)
        assertEquals("Nordlicht", result.title)
        assertEquals("2026", result.year)
        assertEquals(8.4f, result.rating!!, 0f)
        assertEquals(1250, result.voteCount)
        assertEquals("/backdrop.jpg", result.backdropPath)
    }

    @Test
    fun bestSeriesCatalogUsesVoteAverageRanking() {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"id":88,"name":"Stadt der Lichter","vote_average":9.1,"vote_count":800,"first_air_date":"2024-09-12"}]}""",
            ),
        )
        val api = TmdbApi(OkHttpClient(), server.url("/3/").toString())

        val result = api.homeCatalog("test-key", TmdbApi.Kind.TV, TmdbHomeFeed.TOP_RATED).single()
        val request = server.takeRequest()

        assertEquals("discover/tv", request.requestUrl?.encodedPath?.removePrefix("/3/"))
        assertEquals("vote_average.desc", request.requestUrl?.queryParameter("sort_by"))
        assertEquals("100", request.requestUrl?.queryParameter("vote_count.gte"))
        assertEquals("Stadt der Lichter", result.title)
        assertEquals("2024", result.year)
    }
}
