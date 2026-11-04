package com.mg.player.data.importer

import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.security.SecretCipher
import com.mg.player.core.util.CountryDetector
import com.mg.player.core.util.NameNormalizer
import com.mg.player.core.util.StreamTarget
import com.mg.player.core.util.UrlUtils
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.EpisodeEntity
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.db.entity.SeriesEntity
import com.mg.player.data.parser.M3uClassifier
import com.mg.player.data.parser.M3uParser
import com.mg.player.data.remote.ConnectionMonitor
import com.mg.player.data.remote.CredentialMask
import com.mg.player.data.remote.StreamSecretStorage
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.repository.ImportProgress

/**
 * Importiert M3U-Playlists. Filme und Serien werden anhand der URL bzw. von S01E01-Mustern erkannt,
 * Episoden werden automatisch zu Serien gruppiert.
 */
class M3uImporter(private val db: AppDatabase, private val fetcher: HttpFetcher) {

    private class CategoryCollector(private val playlistId: Long, private val generation: Long) {
        val categories = LinkedHashMap<String, CategoryEntity>()

        fun idFor(type: ContentType, group: String): String {
            val key = "${type.name}|$group"
            categories[key]?.let { return it.id }
            val id = XtreamImporter.categoryId(playlistId, type, stableHash(group))
            categories[key] = CategoryEntity(id, playlistId, type.name, group, group, categories.size, generation)
            return id
        }

        private val countries = HashMap<String, String?>()

        /** Land je Gruppe nur einmal erkennen (wenige Gruppen, viele Sender – Profil Labor 3.24). */
        fun countryOf(group: String): String? =
            if (countries.containsKey(group)) countries[group] else CountryDetector.detect(group).also { countries[group] = it }
    }

    suspend fun import(playlist: PlaylistEntity, generation: Long, onProgress: (ImportProgress) -> Unit) {
        val pid = playlist.id
        val categories = CategoryCollector(pid, generation)
        // Serien sofort blockweise schreiben; nur ihre stabilen IDs zur Duplikaterkennung behalten.
        val seenSeriesIds = HashSet<String>()
        var seriesCount = 0
        val series = BatchWriter<SeriesEntity> { db.seriesDao().insertAll(it) }
        val channels = BatchWriter<ChannelEntity> { db.channelDao().insertAll(it) }
        val movies = BatchWriter<MovieEntity> { db.vodDao().insertAll(it) }
        val episodes = BatchWriter<EpisodeEntity> { db.seriesDao().insertEpisodes(it) }
        var total = 0

        if (!SecretCipher.isReadable(playlist.m3uUrl)) throw java.io.IOException(XtreamImporter.CREDENTIALS_LOST)
        onProgress(ImportProgress("Lade Playlist…"))
        val listUrl = SecretCipher.decrypt(playlist.m3uUrl)
        // Xtream-Format: Zugangsdaten nicht in jeder Stream-Adresse der Datenbank speichern (Platzhalter).
        val creds = ConnectionMonitor.credentialsFromM3u(listUrl)
        val stored = { url: String, headers: Map<String, String> ->
            val target = StreamTarget.withHeaders(creds?.let { CredentialMask.mask(url, it.username, it.password) } ?: url, headers)
            StreamSecretStorage.protect(target)
        }
        fetcher.openMaybeGzip(listUrl, playlist.allowInsecureHttp).use { input ->
            M3uParser.parse(
                input = input,
                onHeaderEpg = { url -> db.playlistDao().updateHeaderEpg(pid, url) },
                onEntry = { entry ->
                    total++
                    val ext = UrlUtils.extension(entry.url)
                    when (M3uClassifier.classify(entry, ext)) {
                        M3uClassifier.Kind.LIVE -> channels.add(liveEntity(pid, entry, categories, channels.count, generation, stored))

                        M3uClassifier.Kind.MOVIE -> movies.add(movieEntity(pid, entry, categories, movies.count, generation, stored))

                        M3uClassifier.Kind.SERIES -> {
                            val info = M3uClassifier.seriesInfo(entry.name)
                                ?: M3uClassifier.SeriesInfo(entry.name, 1, episodes.count + 1)
                            val seriesId = "$pid:S:${stableHash(entry.group, info.seriesName)}"
                            if (seenSeriesIds.add(seriesId)) {
                                series.add(
                                    SeriesEntity(
                                        id = seriesId,
                                        playlistId = pid,
                                        categoryId = categories.idFor(ContentType.SERIES, entry.group),
                                        remoteId = "",
                                        name = info.seriesName,
                                        posterUrl = entry.logo,
                                        rating = null,
                                        year = M3uClassifier.year(entry.name),
                                        plot = null,
                                        backdropUrl = null,
                                        sortIndex = seriesCount++,
                                        generation = generation,
                                    ),
                                )
                            }
                            episodes.add(
                                EpisodeEntity(
                                    id = "$pid:E:${stableHash(StableUrl.key(entry.url))}",
                                    seriesId = seriesId,
                                    playlistId = pid,
                                    season = info.season,
                                    episode = info.episode,
                                    title = entry.name,
                                    streamUrl = stored(entry.url, entry.headers),
                                    imageUrl = entry.logo,
                                    plot = null,
                                    durationSec = null,
                                    generation = generation,
                                ),
                            )
                        }
                    }
                    if (total % 1000 == 0) onProgress(ImportProgress("Verarbeite Playlist…", total))
                },
            )
        }
        channels.finish()
        movies.finish()
        episodes.finish()
        series.finish()
        db.categoryDao().insertAll(categories.categories.values.toList())
        onProgress(ImportProgress("Playlist geladen", total))
    }

    private fun liveEntity(
        pid: Long,
        entry: M3uParser.Entry,
        categories: CategoryCollector,
        index: Int,
        generation: Long,
        stored: (String, Map<String, String>) -> String,
    ): ChannelEntity {
        val normalized = NameNormalizer.normalize(entry.tvgName?.takeIf { it.isNotBlank() } ?: entry.name)
        val grouping = ChannelGrouping.of(entry.name, categories.countryOf(entry.group))
        return ChannelEntity(
            id = "$pid:L:${stableHash(StableUrl.key(entry.url), entry.name)}",
            playlistId = pid,
            categoryId = categories.idFor(ContentType.LIVE, entry.group),
            remoteId = "",
            name = entry.name,
            logoUrl = entry.logo,
            streamUrl = stored(entry.url, entry.headers),
            epgChannelId = entry.tvgId,
            normalizedName = normalized,
            epgKey = XtreamImporter.epgKeyFor(entry.tvgId, normalized),
            sortIndex = index,
            catchupDays = entry.catchupDays,
            catchupType = entry.catchupType,
            catchupSource = entry.catchupSource?.let(StreamSecretStorage::protect),
            generation = generation,
            groupKey = grouping.key,
            qualityRank = grouping.rank,
        )
    }

    private fun movieEntity(
        pid: Long,
        entry: M3uParser.Entry,
        categories: CategoryCollector,
        index: Int,
        generation: Long,
        stored: (String, Map<String, String>) -> String,
    ) = MovieEntity(
        id = "$pid:M:${stableHash(StableUrl.key(entry.url))}",
        playlistId = pid,
        categoryId = categories.idFor(ContentType.MOVIE, entry.group),
        remoteId = "",
        name = entry.name,
        posterUrl = entry.logo,
        rating = null,
        year = M3uClassifier.year(entry.name),
        streamUrl = stored(entry.url, entry.headers),
        sortIndex = index,
        generation = generation,
    )
}
