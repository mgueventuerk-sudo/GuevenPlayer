package com.mg.player.data.parser

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream

/** Parser für die Detail-Endpunkte get_series_info, get_vod_info und den Login-Check. */
object XtreamDetailParser {

    data class XEpisode(
        val id: String,
        val season: Int,
        val episode: Int,
        val title: String,
        val extension: String,
        val imageUrl: String?,
        val plot: String?,
        val durationSec: Int?,
    )

    data class XSeriesInfo(val plot: String?, val backdropUrl: String?, val episodes: List<XEpisode>, val tmdbId: String? = null)

    data class XVodInfo(
        val plot: String?,
        val backdropUrl: String?,
        val posterUrl: String?,
        val durationSec: Int?,
        val year: String?,
        val rating: Float?,
        val tmdbId: String? = null,
    )

    data class AuthResult(val authenticated: Boolean, val timezone: String?)

    /** Prüft user_info.auth und liest die Server-Zeitzone (wichtig für Catch-up-Links). */
    fun parseAuth(input: InputStream): AuthResult {
        jsonReader(input).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return AuthResult(false, null)
            var auth = false
            var timezone: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "user_info" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                    val info = reader.readFlatObject()
                    auth = info.str("auth") == "1" || info.str("auth") == "true"
                } else if (name == "server_info" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                    timezone = reader.readFlatObject().str("timezone")
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            return AuthResult(auth, timezone)
        }
    }

    fun parseSeriesInfo(input: InputStream): XSeriesInfo {
        jsonReader(input).use { reader ->
            var plot: String? = null
            var backdrop: String? = null
            var tmdbId: String? = null
            val episodes = ArrayList<XEpisode>()
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return XSeriesInfo(null, null, emptyList())
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "info" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        val info = reader.readFlatObject()
                        plot = info.str("plot")
                        backdrop = info.str("backdrop_path") ?: info.str("cover")
                        tmdbId = info.str("tmdb_id") ?: info.str("tmdb")
                    } else {
                        reader.skipValue()
                    }

                    "episodes" -> readEpisodes(reader, episodes)

                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            episodes.sortWith(compareBy({ it.season }, { it.episode }))
            return XSeriesInfo(plot, backdrop, episodes, tmdbId?.takeIf { it != "0" })
        }
    }

    /** "episodes" ist meist ein Objekt {"1":[...]}, bei manchen Panels ein Array von Arrays. */
    private fun readEpisodes(reader: JsonReader, out: MutableList<XEpisode>) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val seasonKey = reader.nextName().toIntOrNull() ?: 1
                    readSeasonArray(reader, seasonKey, out)
                }
                reader.endObject()
            }

            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var index = 1
                while (reader.hasNext()) {
                    readSeasonArray(reader, index++, out)
                }
                reader.endArray()
            }

            else -> reader.skipValue()
        }
    }

    private fun readSeasonArray(reader: JsonReader, season: Int, out: MutableList<XEpisode>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) readEpisode(reader, season)?.let(out::add) else reader.skipValue()
        }
        reader.endArray()
    }

    private fun readEpisode(reader: JsonReader, defaultSeason: Int): XEpisode? {
        val fields = HashMap<String, String?>()
        var info: Map<String, String?> = emptyMap()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name == "info" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                info = reader.readFlatObject()
            } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
            } else {
                fields[name] = reader.nextStringOrNull()
            }
        }
        reader.endObject()
        val id = fields.str("id") ?: return null
        val episodeNum = fields.int("episode_num") ?: 0
        return XEpisode(
            id = id,
            season = fields.int("season") ?: defaultSeason,
            episode = episodeNum,
            title = fields.str("title") ?: "Folge $episodeNum",
            extension = fields.str("container_extension") ?: "mp4",
            imageUrl = info.str("movie_image") ?: info.str("cover_big"),
            plot = info.str("plot"),
            durationSec = info.int("duration_secs"),
        )
    }

    fun parseVodInfo(input: InputStream): XVodInfo? {
        jsonReader(input).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
            var info: Map<String, String?> = emptyMap()
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "info" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                    info = reader.readFlatObject()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            val release = info.str("releasedate") ?: info.str("release_date")
            return XVodInfo(
                plot = info.str("plot") ?: info.str("description"),
                backdropUrl = info.str("backdrop_path"),
                posterUrl = info.str("movie_image") ?: info.str("cover_big"),
                durationSec = info.int("duration_secs"),
                year = release?.take(4)?.takeIf { y -> y.all { it.isDigit() } },
                rating = info.float("rating"),
                tmdbId = (info.str("tmdb_id") ?: info.str("tmdb"))?.takeIf { it != "0" },
            )
        }
    }
}
