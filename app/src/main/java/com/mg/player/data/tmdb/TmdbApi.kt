package com.mg.player.data.tmdb

import android.net.Uri
import com.mg.player.core.network.HttpFetcher
import com.mg.player.domain.model.CastMember
import com.mg.player.domain.model.TmdbHomeFeed
import java.time.LocalDate
import java.time.ZoneOffset
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Zugriff auf die TMDB-API (v3). Akzeptiert sowohl den klassischen API-Schlüssel
 * als auch das längere "API Read Access Token". Alle Methoden blockieren (Dispatchers.IO).
 */
class TmdbApi(client: OkHttpClient, private val baseUrl: String = BASE) {

    // Klassische TMDB-v3-Schlüssel stehen als query-Parameter in der URL; Redirects könnten sie weiterreichen.
    private val client = HttpFetcher.clientWithoutRedirects(client)

    enum class Kind(val path: String) { MOVIE("movie"), TV("tv") }

    data class Details(
        val id: Int,
        val title: String?,
        val overview: String?,
        val rating: Float?,
        val voteCount: Int,
        val genres: List<String>,
        val runtimeMin: Int?,
        val year: String?,
        val certification: String?,
        val trailerKey: String?,
        val posterPath: String?,
        val backdropPath: String?,
        val cast: List<CastMember>,
        val similar: List<String>,
    )

    data class SearchResult(val id: Int, val title: String, val year: String?, val posterPath: String?, val overview: String?)

    data class CatalogResult(
        val id: Int,
        val title: String,
        val originalTitle: String?,
        val overview: String?,
        val rating: Float?,
        val voteCount: Int,
        val year: String?,
        val posterPath: String?,
        val backdropPath: String?,
    )

    class InvalidKeyException : IOException("TMDB-API-Schlüssel ungültig")

    fun search(key: String, kind: Kind, query: String, year: String?): List<SearchResult> {
        val yearParam = when {
            year == null -> ""
            kind == Kind.MOVIE -> "&year=$year"
            else -> "&first_air_date_year=$year"
        }
        val json = get(key, "search/${kind.path}?query=${Uri.encode(query)}&language=de-DE&include_adult=false$yearParam")
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            SearchResult(
                id = item.optInt("id"),
                title = item.optString(if (kind == Kind.MOVIE) "title" else "name"),
                year = item.optString(if (kind == Kind.MOVIE) "release_date" else "first_air_date").take(4).takeIf { it.length == 4 },
                posterPath = item.optStringOrNull("poster_path"),
                overview = item.optStringOrNull("overview"),
            )
        }
    }

    /** Aktuelle Veröffentlichungen bzw. stark bewertete Titel aus dem TMDB-Katalog. */
    fun homeCatalog(key: String, kind: Kind, feed: TmdbHomeFeed): List<CatalogResult> {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val path = when (feed) {
            TmdbHomeFeed.NEWEST -> if (kind == Kind.MOVIE) {
                "discover/movie?language=de-DE&include_adult=false&include_video=false&page=1&" +
                    "sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=10"
            } else {
                "discover/tv?language=de-DE&include_adult=false&page=1&" +
                    "sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=5"
            }
            TmdbHomeFeed.TOP_RATED -> if (kind == Kind.MOVIE) {
                "discover/movie?language=de-DE&include_adult=false&include_video=false&page=1&" +
                    "sort_by=vote_average.desc&vote_count.gte=500"
            } else {
                "discover/tv?language=de-DE&include_adult=false&page=1&" +
                    "sort_by=vote_average.desc&vote_count.gte=100"
            }
        }
        val json = get(key, path)
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString(if (kind == Kind.MOVIE) "title" else "name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val date = item.optString(if (kind == Kind.MOVIE) "release_date" else "first_air_date")
            CatalogResult(
                id = item.optInt("id"),
                title = title,
                originalTitle = item.optString(if (kind == Kind.MOVIE) "original_title" else "original_name")
                    .takeIf(String::isNotBlank),
                overview = item.optStringOrNull("overview"),
                rating = item.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0f },
                voteCount = item.optInt("vote_count"),
                year = date.take(4).takeIf { it.length == 4 },
                posterPath = item.optStringOrNull("poster_path"),
                backdropPath = item.optStringOrNull("backdrop_path"),
            )
        }
    }

    fun details(key: String, kind: Kind, id: Int): Details {
        val extra = if (kind == Kind.MOVIE) "release_dates" else "content_ratings"
        val json = get(key, "${kind.path}/$id?language=de-DE&append_to_response=credits,videos,similar,$extra&include_video_language=de,en,null")
        var overview = json.optStringOrNull("overview")
        if (overview == null) {
            // Ohne deutsche Beschreibung auf Englisch ausweichen.
            overview = runCatching { get(key, "${kind.path}/$id?language=en-US").optStringOrNull("overview") }.getOrNull()
        }
        return Details(
            id = id,
            title = json.optStringOrNull(if (kind == Kind.MOVIE) "title" else "name"),
            overview = overview,
            rating = json.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0f },
            voteCount = json.optInt("vote_count"),
            genres = json.optJSONArray("genres").objects().mapNotNull { it.optStringOrNull("name") },
            runtimeMin = if (kind == Kind.MOVIE) {
                json.optInt("runtime").takeIf { it > 0 }
            } else {
                json.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
            },
            year = json.optString(if (kind == Kind.MOVIE) "release_date" else "first_air_date").take(4).takeIf { it.length == 4 },
            certification = if (kind == Kind.MOVIE) movieCertification(json) else tvCertification(json),
            trailerKey = trailer(json),
            posterPath = json.optStringOrNull("poster_path"),
            backdropPath = json.optStringOrNull("backdrop_path"),
            cast = json.optJSONObject("credits")?.optJSONArray("cast").objects().take(12).map {
                CastMember(it.optString("name"), it.optStringOrNull("character"), it.optStringOrNull("profile_path")?.let(::profileUrl))
            },
            similar = json.optJSONObject("similar")?.optJSONArray("results").objects().take(20).mapNotNull {
                it.optStringOrNull(if (kind == Kind.MOVIE) "title" else "name")
            },
        )
    }

    private fun movieCertification(json: JSONObject): String? = json.optJSONObject("release_dates")?.optJSONArray("results").objects()
        .firstOrNull { it.optString("iso_3166_1") == "DE" }
        ?.optJSONArray("release_dates").objects()
        .mapNotNull { it.optStringOrNull("certification") }
        .firstOrNull()

    private fun tvCertification(json: JSONObject): String? = json.optJSONObject("content_ratings")?.optJSONArray("results").objects()
        .firstOrNull { it.optString("iso_3166_1") == "DE" }
        ?.optStringOrNull("rating")

    /**
     * Nur ein echter Trailer, nie ein Teaser oder sonstiges Video als Ersatz: Der Knopf im Titel heißt „Trailer“ -
     * zeigt er etwas anderes, stimmt die Zusage nicht mehr. Ohne Trailer bleibt der Knopf lieber ganz weg (null).
     */
    private fun trailer(json: JSONObject): String? {
        val trailers = json.optJSONObject("videos")?.optJSONArray("results").objects()
            .filter { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }
        val best = trailers.firstOrNull { it.optString("iso_639_1") == "de" } ?: trailers.firstOrNull()
        return best?.optStringOrNull("key")
    }

    private fun get(key: String, pathAndQuery: String): JSONObject {
        val bearer = key.startsWith("eyJ")
        val url = baseUrl + pathAndQuery + if (bearer) "" else "&api_key=${Uri.encode(key)}"
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (bearer) builder.header("Authorization", "Bearer $key")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) throw InvalidKeyException()
            if (!response.isSuccessful) throw IOException("TMDB antwortet mit HTTP ${response.code}")
            return JSONObject(response.body.string())
        }
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3/"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"

        fun posterUrl(path: String) = "${IMAGE_BASE}w500$path"
        fun backdropUrl(path: String) = "${IMAGE_BASE}w1280$path"
        fun profileUrl(path: String) = "${IMAGE_BASE}w185$path"
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }
