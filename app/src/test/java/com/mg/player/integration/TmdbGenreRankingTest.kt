package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.db.entity.TmdbCacheEntity
import com.mg.player.data.tmdb.TmdbApi
import com.mg.player.data.tmdb.TmdbRepositoryImpl
import com.mg.player.domain.model.ContentType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Deine Empfehlungen" bestand bisher nur aus TMDBs "neueste"/"bestbewertete" Listen ohne Bezug zum eigenen
 * Sehverlauf (Funktions-Audit). GenreRanking sortiert die schon geladene Liste jetzt so um, dass Titel mit dem
 * häufigsten Genre der eigenen Favoriten vorne stehen - ohne Favoriten (siehe TmdbHomeRecommendationsTest)
 * bleibt die TMDB-Reihenfolge unverändert.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TmdbGenreRankingTest {

    private val tmdbServer = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = tmdbServer.shutdown()

    @Test
    fun favoriteGenreMovesAMatchingTitleToTheFront() = runBlocking {
        container.settingsRepository.update { it.copy(tmdbApiKey = "test-tmdb-key", tmdbEnabled = true, onlineExtras = true) }
        // Favorit mit zwischengespeichertem TMDB-Genre "Action" (Kennung 28) - kein echter Netzaufruf nötig.
        container.database.tmdbDao().upsert(
            TmdbCacheEntity(
                itemKey = "movie:fav-1",
                tmdbId = 1,
                mediaType = "movie",
                title = "Mein Favorit",
                overview = null,
                rating = null,
                voteCount = 0,
                genres = "Action",
                runtimeMin = null,
                year = null,
                certification = null,
                trailerKey = null,
                posterPath = null,
                backdropPath = null,
                cast = "",
                similar = "",
                fetchedAtMs = System.currentTimeMillis(),
                notFound = false,
                manual = false,
            ),
        )
        container.playbackRepository.toggleFavorite(ContentType.MOVIE, "fav-1")

        // "Zweiter" (Komödie) steht in der TMDB-Antwort vorne, "Action-Treffer" (Action, wie der Favorit) hinten.
        val body = """{"results":[
            {"id":901,"title":"Zweiter","vote_average":9.0,"vote_count":1000,"release_date":"2024-01-01","genre_ids":[35]},
            {"id":902,"title":"Action-Treffer","vote_average":5.0,"vote_count":10,"release_date":"2024-01-01","genre_ids":[28]}
        ]}"""
        repeat(4) { tmdbServer.enqueue(MockResponse().setBody(body)) }
        val repository = TmdbRepositoryImpl(
            db = container.database,
            api = TmdbApi(OkHttpClient(), tmdbServer.url("/3/").toString()),
            settings = container.settingsRepository,
            search = container.searchRepository,
        )

        val recommendations = repository.homeRecommendations()

        assertEquals(listOf("Action-Treffer", "Zweiter"), recommendations.newestMovies.map { it.title })
        assertEquals(listOf("Action-Treffer", "Zweiter"), recommendations.topRatedMovies.map { it.title })
    }
}
