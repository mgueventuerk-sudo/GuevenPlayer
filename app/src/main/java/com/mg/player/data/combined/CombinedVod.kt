package com.mg.player.data.combined

import androidx.room.withTransaction
import com.mg.player.core.util.CountryDetector
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.dao.VodSourceRow
import com.mg.player.data.db.entity.CombinedLinkEntity
import com.mg.player.data.db.entity.EpisodeEntity
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.ProgressEntity
import com.mg.player.data.db.entity.SeriesEntity
import com.mg.player.domain.model.ContentType
import kotlinx.coroutines.yield

/** Filme, Serien und Folgen von „Alle Zugänge“. */
internal object CombinedVod {

    const val EPISODE = "EPISODE"

    /** Anzahl der zusammengeführten Einträge. */
    suspend fun build(ctx: BuildContext, rules: MatchRules, isSeries: Boolean, languageOf: (VodSourceRow) -> String?): Int {
        val db = ctx.db
        val generation = ctx.generation
        val dao = db.combinedDao()
        val type = if (isSeries) ContentType.SERIES.name else ContentType.MOVIE.name
        val rows = ArrayList<VodSourceRow>()
        for (playlistId in ctx.ids) {
            var afterRowId = 0L
            while (true) {
                val page = if (isSeries) dao.seriesSourcePage(playlistId, afterRowId, SOURCE_PAGE)
                    else dao.movieSourcePage(playlistId, afterRowId, SOURCE_PAGE)
                if (page.isEmpty()) break
                rows.addAll(page)
                afterRowId = page.last().rowId
                yield()
            }
        }
        val clusters = VodClusterer.cluster(rows, isSeries, rules)
        val links = ArrayList<CombinedLinkEntity>(rows.size)
        val writer = VodWriter(db, isSeries, generation)
        for (cluster in clusters) {
            val id = Combined.itemId(if (isSeries) 'S' else 'M', cluster.key)
            cluster.members.forEach { m ->
                links += CombinedLinkEntity(id, m.id, m.playlistId, type, NEUTRAL_QUALITY, languageOf(m), m.durationSec)
            }
            writer.add(id, VodRow(cluster.members, ctx, type))
        }
        writer.flush()
        db.withTransaction {
            if (isSeries) db.seriesDao().deleteStale(Combined.ID, generation) else db.vodDao().deleteStale(Combined.ID, generation)
            dao.replaceLinks(type, links)
        }
        return clusters.size
    }

    /** Schreibt kombinierte Film- bzw. Serienzeilen blockweise (nicht alle im Speicher sammeln – schwache Geräte). */
    private class VodWriter(private val db: AppDatabase, private val isSeries: Boolean, private val generation: Long) {
        private val movies = ArrayList<MovieEntity>(CombinedBuilder.CHUNK)
        private val series = ArrayList<SeriesEntity>(CombinedBuilder.CHUNK)

        suspend fun add(id: String, row: VodRow) {
            if (isSeries) {
                series += SeriesEntity(
                    id, Combined.ID, row.categoryId, "", row.name, row.poster, row.rating, row.year, null, null, row.sort, row.tmdb, generation,
                )
            } else {
                movies += MovieEntity(
                    id, Combined.ID, row.categoryId, "", row.name, row.poster, row.rating, row.year, "", row.sort,
                    durationSec = row.durationSec, tmdbId = row.tmdb, generation = generation,
                )
            }
            if (movies.size + series.size >= CombinedBuilder.CHUNK) flush()
        }

        suspend fun flush() {
            if (movies.isNotEmpty()) db.vodDao().insertAll(movies)
            if (series.isNotEmpty()) db.seriesDao().insertAll(series)
            movies.clear()
            series.clear()
        }
    }

    /** Gemeinsame Angaben einer kombinierten Film-/Serienzeile: vom Original des vorderen Zugangs, Lücken aus den übrigen. */
    private class VodRow(members: List<VodSourceRow>, ctx: BuildContext, type: String) {
        private val primary = members.minWith(compareBy({ ctx.rank[it.playlistId] ?: 0 }, { it.sortIndex }))
        val name: String = primary.name
        val categoryId: String = ctx.categories.idFor(primary.playlistId, primary.categoryId, type)
        val sort: Int = (ctx.rank[primary.playlistId] ?: 0) * CombinedCategories.RANK_STEP + primary.sortIndex
        val poster: String? = primary.posterUrl ?: members.firstNotNullOfOrNull { it.posterUrl }
        val rating: Float? = members.mapNotNull { it.rating }.maxOrNull()
        val year: String? = primary.year ?: members.firstNotNullOfOrNull { it.year }
        val tmdb: String? = members.firstNotNullOfOrNull { it.tmdbId?.takeIf { t -> t.isNotBlank() && t != "0" } }
        val durationSec: Int? = primary.durationSec
    }

    /** Sprache einer Fassung: aus dem Titel („DE: …“) oder der Gruppe des Anbieters. */
    fun language(name: String, categoryCountry: String?): String? = CountryDetector.detect(name) ?: categoryCountry

    /**
     * Folgen einer zusammengeführten Serie: alle Folgen aller Quellen, je Staffel + Folge ein Eintrag mit allen
     * Fassungen als Quellen. So ergänzt ein Zugang die Staffeln, die einem anderen fehlen.
     */
    suspend fun buildEpisodes(db: AppDatabase, combinedSeriesId: String, rank: (Long) -> Int, episodesOf: suspend (String) -> List<EpisodeEntity>) {
        val dao = db.combinedDao()
        val sources = dao.sources(combinedSeriesId)
        val all = sources.flatMap { s -> episodesOf(s.itemId).map { it to s } }
        val groups = all.groupBy { (e, _) -> e.season to e.episode }.toSortedMap(compareBy({ it.first }, { it.second }))
        val links = ArrayList<CombinedLinkEntity>()
        val entities = groups.map { (se, members) ->
            val (season, number) = se
            val id = Combined.itemId('E', "$combinedSeriesId|$season|$number")
            val ordered = members.sortedBy { (_, s) -> rank(s.playlistId) }
            val first = ordered.first().first
            ordered.forEach { (e, s) -> links += CombinedLinkEntity(id, e.id, e.playlistId, EPISODE, NEUTRAL_QUALITY, s.language, e.durationSec) }
            EpisodeEntity(
                id = id,
                seriesId = combinedSeriesId,
                playlistId = Combined.ID,
                season = season,
                episode = number,
                title = first.title,
                streamUrl = "",
                imageUrl = ordered.firstNotNullOfOrNull { it.first.imageUrl },
                plot = ordered.firstNotNullOfOrNull { it.first.plot },
                durationSec = ordered.firstNotNullOfOrNull { it.first.durationSec },
            )
        }
        val old = db.seriesDao().episodes(combinedSeriesId).map { it.id }
        db.withTransaction {
            if (old.isNotEmpty()) old.chunked(MAX_ARGS).forEach { dao.deleteLinksOf(it) }
            db.seriesDao().replaceEpisodes(combinedSeriesId, entities)
            links.chunked(CombinedBuilder.CHUNK).forEach { dao.insertLinks(it) }
        }
        inherit(db, "episode:", EPISODE, seriesId = combinedSeriesId)
    }

    suspend fun dropOrphanEpisodes(db: AppDatabase) {
        db.combinedDao().deleteOrphanEpisodes()
        db.combinedDao().deleteOrphanEpisodeLinks()
    }

    /** Fortschritt der Originale übernehmen, wenn er neuer ist als der des zusammengeführten Eintrags. */
    suspend fun inheritProgress(db: AppDatabase) = inherit(db, "movie:", ContentType.MOVIE.name, seriesId = null)

    private suspend fun inherit(db: AppDatabase, prefix: String, type: String, seriesId: String?) {
        val latest = db.combinedDao().linkedProgress(prefix, type).groupBy { it.combinedId }.mapValues { (_, l) -> l.maxBy { it.updatedAtMs } }
        for ((combinedId, p) in latest) {
            val key = prefix + combinedId
            val existing = db.progressDao().get(key)
            if (existing == null || existing.updatedAtMs < p.updatedAtMs) {
                db.progressDao().upsert(ProgressEntity(key, p.positionMs, p.durationMs, p.watched, p.updatedAtMs, seriesId ?: existing?.seriesId))
            }
        }
    }

    private const val MAX_ARGS = 900
    private const val SOURCE_PAGE = 400

    /** Filme/Folgen haben keinen Qualitätsrang beim Import; beim Abspielen zählt der aus dem Titel. */
    private const val NEUTRAL_QUALITY = 50
}
