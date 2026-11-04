package com.mg.player.data.importer

import com.mg.player.core.security.SecretCipher
import com.mg.player.core.util.CountryDetector
import com.mg.player.core.util.NameNormalizer
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.db.entity.SeriesEntity
import com.mg.player.data.parser.M3uClassifier
import com.mg.player.data.parser.XtreamDetailParser
import com.mg.player.data.parser.float
import com.mg.player.data.parser.forEachObject
import com.mg.player.data.parser.int
import com.mg.player.data.parser.jsonReader
import com.mg.player.data.parser.str
import com.mg.player.data.remote.XtreamApi
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.repository.ImportProgress
import java.io.IOException
import java.util.Locale

/** Importiert Live-TV, Filme und Serien über die Xtream-API per Streaming-JSON-Parser. */
class XtreamImporter(private val db: AppDatabase, private val api: XtreamApi) {

    private class Session(val playlistId: Long, val creds: XtreamApi.Credentials, val generation: Long) {
        /** Live-Kategorie (Remote-ID) -> erkanntes Land, für die Sender-Gruppierung. */
        val liveCategoryCountry = HashMap<String, String?>()
    }

    suspend fun import(playlist: PlaylistEntity, generation: Long, onProgress: (ImportProgress) -> Unit) {
        if (!SecretCipher.isReadable(playlist.password)) throw IOException(CREDENTIALS_LOST)
        val session = Session(playlist.id, api.credentials(playlist), generation)
        onProgress(ImportProgress("Prüfe Zugangsdaten…"))
        val auth = api.open(api.playerApiUrl(session.creds), session.creds.allowInsecureHttp).use { XtreamDetailParser.parseAuth(it) }
        if (!auth.authenticated) throw IOException("Login fehlgeschlagen – bitte Benutzername und Passwort prüfen.")
        auth.timezone?.let { db.playlistDao().updateTimezone(playlist.id, it) }

        importCategories(session, "get_live_categories", ContentType.LIVE)
        importCategories(session, "get_vod_categories", ContentType.MOVIE)
        importCategories(session, "get_series_categories", ContentType.SERIES)

        importLive(session, onProgress)
        importMovies(session, onProgress)
        importSeries(session, onProgress)
    }

    private suspend fun importCategories(s: Session, action: String, type: ContentType) {
        val categories = ArrayList<CategoryEntity>()
        api.openAction(s.creds, action).use { input ->
            jsonReader(input).forEachObject { item ->
                val remoteId = item.str("category_id") ?: return@forEachObject
                val categoryName = item.str("category_name") ?: "Kategorie $remoteId"
                if (type == ContentType.LIVE) s.liveCategoryCountry[remoteId] = CountryDetector.detect(categoryName)
                categories += CategoryEntity(
                    id = categoryId(s.playlistId, type, remoteId),
                    playlistId = s.playlistId,
                    type = type.name,
                    remoteId = remoteId,
                    name = categoryName,
                    sortIndex = categories.size,
                    generation = s.generation,
                )
            }
        }
        db.categoryDao().insertAll(categories)
    }

    private suspend fun importLive(s: Session, onProgress: (ImportProgress) -> Unit) {
        val writer = BatchWriter<ChannelEntity> { batch -> db.channelDao().insertAll(batch) }
        api.openAction(s.creds, "get_live_streams").use { input ->
            jsonReader(input).forEachObject { item ->
                val remoteCategory = item.str("category_id") ?: "0"
                val channel = liveChannel(api, s.playlistId, item, s.liveCategoryCountry[remoteCategory], writer.count, s.generation)
                    ?: return@forEachObject
                writer.add(channel)
                if (writer.count % 500 == 0) onProgress(ImportProgress("Lade Sender…", writer.count))
            }
        }
        writer.finish()
        onProgress(ImportProgress("Sender geladen", writer.count))
    }

    private suspend fun importMovies(s: Session, onProgress: (ImportProgress) -> Unit) {
        val writer = BatchWriter<MovieEntity> { batch -> db.vodDao().insertAll(batch) }
        api.openAction(s.creds, "get_vod_streams").use { input ->
            jsonReader(input).forEachObject { item ->
                val streamId = item.str("stream_id") ?: return@forEachObject
                val name = item.str("name") ?: "Film $streamId"
                val extension = item.str("container_extension") ?: "mp4"
                writer.add(
                    MovieEntity(
                        id = "${s.playlistId}:M:$streamId",
                        playlistId = s.playlistId,
                        categoryId = categoryId(s.playlistId, ContentType.MOVIE, item.str("category_id") ?: "0"),
                        remoteId = streamId,
                        name = name,
                        posterUrl = item.str("stream_icon"),
                        rating = item.float("rating")?.takeIf { it > 0f },
                        year = item.str("year") ?: M3uClassifier.year(name),
                        streamUrl = api.moviePlaceholder(s.playlistId, streamId, extension),
                        sortIndex = writer.count,
                        tmdbId = tmdbIdOf(item),
                        generation = s.generation,
                    ),
                )
                if (writer.count % 500 == 0) onProgress(ImportProgress("Lade Filme…", writer.count))
            }
        }
        writer.finish()
        onProgress(ImportProgress("Filme geladen", writer.count))
    }

    private suspend fun importSeries(s: Session, onProgress: (ImportProgress) -> Unit) {
        val writer = BatchWriter<SeriesEntity> { batch -> db.seriesDao().insertAll(batch) }
        api.openAction(s.creds, "get_series").use { input ->
            jsonReader(input).forEachObject { item ->
                val seriesId = item.str("series_id") ?: return@forEachObject
                val name = item.str("name") ?: "Serie $seriesId"
                writer.add(
                    SeriesEntity(
                        id = "${s.playlistId}:S:$seriesId",
                        playlistId = s.playlistId,
                        categoryId = categoryId(s.playlistId, ContentType.SERIES, item.str("category_id") ?: "0"),
                        remoteId = seriesId,
                        name = name,
                        posterUrl = item.str("cover"),
                        rating = item.float("rating")?.takeIf { it > 0f },
                        year = (item.str("releaseDate") ?: item.str("release_date"))?.take(4) ?: M3uClassifier.year(name),
                        plot = item.str("plot"),
                        backdropUrl = item.str("backdrop_path"),
                        sortIndex = writer.count,
                        tmdbId = tmdbIdOf(item),
                        generation = s.generation,
                    ),
                )
                if (writer.count % 500 == 0) onProgress(ImportProgress("Lade Serien…", writer.count))
            }
        }
        writer.finish()
        onProgress(ImportProgress("Serien geladen", writer.count))
    }

    private fun tmdbIdOf(item: Map<String, String?>): String? = (item.str("tmdb") ?: item.str("tmdb_id"))?.takeIf { id -> id != "0" && id.all { it.isDigit() } }

    companion object {
        const val CATCHUP_XTREAM = "xtream"
        const val CREDENTIALS_LOST = "Die gespeicherten Zugangsdaten lassen sich nicht mehr entschlüsseln (z. B. nach einer " +
            "Geräte-Wiederherstellung). Bitte den Zugang löschen und mit Benutzername und Passwort neu anlegen."

        fun categoryId(playlistId: Long, type: ContentType, remoteId: String) = "$playlistId:${type.name.first()}:$remoteId"

        fun liveChannelId(playlistId: Long, streamId: String) = "$playlistId:L:$streamId"

        /**
         * Ein Sender aus get_live_streams (gemeinsam für den vollen Import und die Kontrolle der Event-Gruppen vor Anstoß).
         * [categoryCountry]: aus dem Gruppennamen erkanntes Land (für die Sender-Gruppierung). null ohne stream_id.
         */
        fun liveChannel(
            api: XtreamApi,
            playlistId: Long,
            item: Map<String, String?>,
            categoryCountry: String?,
            sortIndex: Int,
            generation: Long,
        ): ChannelEntity? {
            val streamId = item.str("stream_id") ?: return null
            val name = item.str("name") ?: "Sender $streamId"
            val epgId = item.str("epg_channel_id").orEmpty()
            val normalized = NameNormalizer.normalize(name)
            val archive = item.str("tv_archive") == "1"
            val remoteCategory = item.str("category_id") ?: "0"
            val grouping = ChannelGrouping.of(name, categoryCountry)
            return ChannelEntity(
                id = liveChannelId(playlistId, streamId),
                playlistId = playlistId,
                categoryId = categoryId(playlistId, ContentType.LIVE, remoteCategory),
                remoteId = streamId,
                name = name,
                logoUrl = item.str("stream_icon"),
                streamUrl = api.livePlaceholder(playlistId, streamId),
                epgChannelId = epgId,
                normalizedName = normalized,
                epgKey = epgKeyFor(epgId, normalized),
                sortIndex = sortIndex,
                catchupDays = if (archive) item.int("tv_archive_duration") ?: 1 else 0,
                catchupType = if (archive) CATCHUP_XTREAM else null,
                generation = generation,
                groupKey = grouping.key,
                qualityRank = grouping.rank,
            )
        }

        fun epgKeyFor(epgChannelId: String, normalizedName: String): String =
            if (epgChannelId.isNotBlank()) epgChannelId.lowercase(Locale.ROOT) else "name:$normalizedName"
    }
}
