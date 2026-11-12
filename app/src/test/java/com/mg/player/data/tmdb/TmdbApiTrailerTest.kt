package com.mg.player.data.tmdb

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Der Trailer-Knopf im Titel muss wirklich einen Trailer öffnen, nie einen Teaser oder ein anderes Video als
 * Ersatz ("100%ige Übereinstimmung des Trailers"). Vorher fiel trailer() ohne echten Trailer auf das erste
 * beliebige Video zurück (auch einen Teaser) - der Knopf hieß "Trailer", zeigte aber etwas anderes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TmdbApiTrailerTest {

    private val server = MockWebServer()

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun trailerKeyFor(videos: String): String? {
        server.enqueue(MockResponse().setBody("""{"overview":"x","videos":{"results":[$videos]}}"""))
        val api = TmdbApi(OkHttpClient(), server.url("/3/").toString())
        return api.details("test-key", TmdbApi.Kind.MOVIE, 1).trailerKey
    }

    @Test
    fun onlyATeaserYieldsNoTrailerKey() {
        val key = trailerKeyFor("""{"site":"YouTube","type":"Teaser","key":"teaser123","iso_639_1":"de"}""")
        assertNull("Ein Teaser ist kein Trailer - der Knopf darf nicht darauf zeigen", key)
    }

    @Test
    fun prefersTheGermanTrailerOverOtherLanguages() {
        val key = trailerKeyFor(
            """{"site":"YouTube","type":"Trailer","key":"en-trailer","iso_639_1":"en"},""" +
                """{"site":"YouTube","type":"Trailer","key":"de-trailer","iso_639_1":"de"}""",
        )
        assertEquals("de-trailer", key)
    }

    @Test
    fun fallsBackToAnyTrailerWithoutAGermanOne() {
        val key = trailerKeyFor("""{"site":"YouTube","type":"Trailer","key":"en-trailer","iso_639_1":"en"}""")
        assertEquals("en-trailer", key)
    }

    @Test
    fun ignoresNonYoutubeVideosEvenIfMarkedAsTrailer() {
        val key = trailerKeyFor("""{"site":"Vimeo","type":"Trailer","key":"vimeo123","iso_639_1":"de"}""")
        assertNull(key)
    }
}
