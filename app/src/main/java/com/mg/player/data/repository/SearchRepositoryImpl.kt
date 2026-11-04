package com.mg.player.data.repository

import com.mg.player.core.log.AppLog
import com.mg.player.core.util.MediaTitle
import com.mg.player.data.combined.Combined
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.SeriesEntity
import com.mg.player.data.search.SearchIndexer
import com.mg.player.data.search.SearchText
import com.mg.player.data.search.SourceHits
import com.mg.player.data.search.mergeAcrossPlaylists
import com.mg.player.data.search.searchRelevance
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistHits
import com.mg.player.domain.model.SearchHit
import com.mg.player.domain.model.SearchPages
import com.mg.player.domain.repository.SearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Globale Suche über Live-TV, Filme und Serien. Ablauf je Bereich:
 * 1. Wortanfänge über den Volltext-Index ("zdf" -> "DE: ZDF HD").
 * 2. Findet das wenig: Suche mitten im Wort über die gefalteten Begriffe ("neo" -> "ZDFneo", "ntv" -> "n-tv",
 *    "Z D F" aus der Spracheingabe -> "ZDF").
 * 3. Kein Index (direkt nach dem Update) oder gar nichts gefunden: direkt in den Tabellen suchen. Findet das
 *    etwas, war der Index veraltet – er wird im Hintergrund neu aufgebaut.
 *
 * Treffer werden nach Relevanz sortiert (exakter Titel > Titelanfang > Wortanfang > irgendwo); Sender mit
 * mehreren Qualitäten (ZDF UHD/FHD/HD) erscheinen wie in der Senderliste nur einmal.
 */
class SearchRepositoryImpl(private val db: AppDatabase, private val indexer: SearchIndexer, private val scope: CoroutineScope) : SearchRepository {

    /** Zugänge, deren Index gerade neu aufgebaut wird – ein Set, weil die Suche alle Zugänge parallel abfragt. */
    private val rebuilding: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())

    private class Rows(val channels: List<ChannelEntity>, val movies: List<MovieEntity>, val series: List<SeriesEntity>) {
        val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    }

    override suspend fun search(playlistId: Long, query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val match = SearchText.ftsQuery(q) ?: return@withContext emptyList()
        val fragment = SearchText.compact(q)
        if (fragment.length < MIN_QUERY) return@withContext emptyList()
        // Gleich nach einer Aktualisierung läuft der Aufbau im Hintergrund: kurz auf den neuen Index warten.
        if (indexer.isBusy(playlistId)) indexer.awaitIdle(playlistId, WAIT_FOR_INDEX_MS)
        val indexed = indexer.hasIndex(playlistId)
        if (!indexed) prepare(playlistId)
        var rows = if (indexed) fromIndex(playlistId, match, fragment) else fromTables(playlistId, q)
        if (rows.isEmpty && indexed) {
            rows = fromTables(playlistId, q)
            if (!rows.isEmpty) {
                // Der Index kennt Titel nicht, die es gibt: veraltet oder beschädigt -> neu aufbauen.
                AppLog.log("Suche", "Index unvollständig (${indexer.indexedCount(playlistId)} Einträge) – wird neu aufgebaut")
                rebuildLater(playlistId, force = true)
            }
        }
        if (rows.isEmpty) {
            AppLog.log("Suche", "Keine Treffer für „${q.take(40)}“ (Index bereit: ${if (indexed) "ja" else "nein"})")
        }
        toHits(rows, q)
    }

    override suspend fun searchAll(query: String, activePlaylistId: Long?): List<SearchHit> = searchPages(query, activePlaylistId).all

    override suspend fun searchPages(query: String, activePlaylistId: Long?): SearchPages = withContext(Dispatchers.IO) {
        // „Alle Zugänge“: jeder Inhalt einmal (die Kombination ist schon zusammengeführt).
        if (Combined.isCombined(activePlaylistId)) {
            val hits = search(Combined.ID, query)
            return@withContext SearchPages(hits, listOf(PlaylistHits(Combined.ID, Combined.NAME, hits)))
        }
        val playlists = db.playlistDao().all().sortedBy { if (it.id == activePlaylistId) 0 else 1 }
        // Parallel: Jeder Zugang hat seinen eigenen Index, die Abfragen blockieren sich nicht.
        // Einmal suchen, dann auf die Seiten verteilen – beim Wischen zwischen den Seiten wird nichts neu geladen.
        val sources = coroutineScope {
            playlists.map { p -> async { SourceHits(p.id, p.name, search(p.id, query)) } }.awaitAll()
        }
        val withHits = sources.filter { it.hits.isNotEmpty() }
        val all = when {
            withHits.isEmpty() -> emptyList()
            playlists.size == 1 -> withHits.first().hits
            else -> mergeAcrossPlaylists(withHits, query, LIMIT * 2)
        }
        // Seitenreihenfolge wie die Zugänge (nach ID), nicht nach „aktiv zuerst“ – sonst springen die Tabs beim Wechsel.
        val pages = sources.sortedBy { it.playlistId }.map { PlaylistHits(it.playlistId, it.name, it.hits) }
        SearchPages(all, pages)
    }

    /** Index für diesen Zugang vorbereiten, falls er fehlt (z. B. beim App-Start nach einem Update). */
    override suspend fun prepare(playlistId: Long) {
        if (!indexer.hasIndex(playlistId)) rebuildLater(playlistId)
    }

    private suspend fun fromIndex(playlistId: Long, match: String, fragment: String): Rows {
        val search = db.searchDao()
        val key = indexer.activeKey(playlistId) ?: return Rows(emptyList(), emptyList(), emptyList())
        suspend fun ids(type: ContentType, limit: Int): List<String> {
            val hits = search.matching(match, type.name, key, limit * CANDIDATE_FACTOR)
            if (hits.size >= SPARSE_RESULTS) return hits
            return (hits + search.containing(fragment, type.name, key, limit * CANDIDATE_FACTOR)).distinct()
        }
        return Rows(
            channels = byId(ids(ContentType.LIVE, LIMIT * 3)) { search.channelsById(playlistId, it) }.take(LIMIT * 3),
            movies = byId(ids(ContentType.MOVIE, LIMIT)) { search.moviesById(playlistId, it) }.take(LIMIT),
            series = byId(ids(ContentType.SERIES, LIMIT)) { search.seriesById(playlistId, it) }.take(LIMIT),
        )
    }

    /** Einträge per Primärschlüssel laden – in Blöcken, weil SQLite höchstens 999 Parameter je Abfrage erlaubt. */
    private suspend fun <T> byId(ids: List<String>, load: suspend (List<String>) -> List<T>): List<T> =
        if (ids.isEmpty()) emptyList() else ids.chunked(MAX_SQL_ARGS).flatMap { load(it) }

    private suspend fun fromTables(playlistId: Long, q: String): Rows = Rows(
        db.channelDao().search(playlistId, q, LIMIT * 3),
        db.vodDao().search(playlistId, q, LIMIT),
        db.seriesDao().search(playlistId, q, LIMIT),
    )

    private fun toHits(rows: Rows, q: String): List<SearchHit> {
        val channels = rows.channels
            .groupBy { it.groupKey.ifBlank { it.id } }
            .values
            .map { variants ->
                val best = variants.minBy { it.qualityRank * 1_000_000L + it.sortIndex }
                val info = MediaTitle.parse(best.name)
                SearchHit(
                    id = best.id,
                    type = ContentType.LIVE,
                    title = info.title,
                    imageUrl = best.logoUrl,
                    badges = listOfNotNull(info.resolution),
                    subtitle = if (variants.size > 1) "${variants.size} Quellen" else null,
                )
            }
            .rank(q)
        val movies = rows.movies.map {
            val info = MediaTitle.parse(it.name)
            SearchHit(it.id, ContentType.MOVIE, info.title, it.posterUrl, info.visibleBadges, it.year ?: info.year, it.rating)
        }.rank(q)
        val series = rows.series.map {
            val info = MediaTitle.parse(it.name)
            SearchHit(it.id, ContentType.SERIES, info.title, it.posterUrl, info.visibleBadges, it.year ?: info.year, it.rating)
        }.rank(q)
        return channels.take(LIMIT) + movies + series
    }

    private fun rebuildLater(playlistId: Long, force: Boolean = false) {
        if (indexer.isBusy(playlistId) || !rebuilding.add(playlistId)) return
        scope.launch {
            try {
                indexer.rebuild(playlistId, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("Suche", "Suchindex konnte nicht aufgebaut werden", e)
            } finally {
                rebuilding.remove(playlistId)
            }
        }
    }

    /** Stabile Sortierung: innerhalb gleicher Relevanz bleibt die Reihenfolge des Anbieters erhalten. */
    private fun List<SearchHit>.rank(query: String): List<SearchHit> = sortedBy { searchRelevance(it.title, query) }

    private companion object {
        const val LIMIT = 60
        const val SPARSE_RESULTS = 10
        const val MIN_QUERY = 2

        /** Aus dem Index mehr Kandidaten holen als angezeigt: Ausgeblendete Gruppen fallen danach noch weg. */
        const val CANDIDATE_FACTOR = 4
        const val MAX_SQL_ARGS = 900

        /** So lange wartet eine Suche höchstens auf den Index-Aufbau nach einer Aktualisierung (Plan B4, 3.25). */
        const val WAIT_FOR_INDEX_MS = 3_000L
    }
}
