package com.mg.player.data.search

import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.SearchHit

/** Treffer eines Zugangs – in der Reihenfolge, in der die Zugänge Vorrang haben (aktiver zuerst). */
internal class SourceHits(val playlistId: Long, val name: String, val hits: List<SearchHit>)

/** Relevanz eines Titels für die Suche: 0 = exakt, 1 = Titelanfang, 2 = Wortanfang, 3 = irgendwo. */
internal fun searchRelevance(title: String, query: String): Int {
    val q = SearchText.comparable(query)
    val compactQ = q.replace(" ", "")
    val t = SearchText.comparable(title)
    return when {
        t == q || t.replace(" ", "") == compactQ -> 0
        t.startsWith(q) -> 1
        t.split(' ').any { it.startsWith(q) } -> 2
        else -> 3
    }
}

/**
 * Suche über alle Zugänge: Derselbe Titel aus mehreren Zugängen steht direkt nebeneinander, der beste zuerst
 * (höchste Auflösung, bei Gleichstand der aktive Zugang). Jeder Treffer nennt seinen Zugang in der zweiten Zeile.
 * Gruppen sortieren nach Relevanz, dann nach Fundstelle – Treffer des aktiven Zugangs kommen bei gleicher
 * Relevanz zuerst.
 */
internal fun mergeAcrossPlaylists(sources: List<SourceHits>, query: String, limitPerType: Int): List<SearchHit> {
    if (sources.size == 1) return sources.first().hits
    class Entry(val hit: SearchHit, val sourceIndex: Int, val position: Int, val originalSubtitle: String?)
    val entries = sources.flatMapIndexed { sourceIndex, source ->
        source.hits.mapIndexed { position, hit ->
            Entry(
                hit.copy(
                    playlistId = source.playlistId,
                    source = source.name,
                    subtitle = listOfNotNull(hit.subtitle, source.name).joinToString(" · "),
                ),
                sourceIndex,
                position,
                hit.subtitle,
            )
        }
    }
    return listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES).flatMap { type ->
        entries.filter { it.hit.type == type }
            // Filme/Serien gleichen Namens aus verschiedenen Jahren bleiben getrennt (Jahr steht im Untertitel).
            .groupBy { SearchText.compact(it.hit.title) + "|" + if (type == ContentType.LIVE) "" else it.originalSubtitle.orEmpty() }
            .values
            .sortedWith(
                compareBy<List<Entry>> { group -> searchRelevance(group.first().hit.title, query) }
                    .thenBy { group -> group.minOf { it.sourceIndex } }
                    .thenBy { group -> group.minOf { it.position } },
            )
            .flatMap { group -> group.sortedWith(compareBy({ qualityRank(it.hit) }, { it.sourceIndex })).map { it.hit } }
            .take(limitPerType)
    }
}

/** Kleinere Zahl = bessere Bildqualität; ohne Angabe zwischen HD und SD. */
internal fun qualityRank(hit: SearchHit): Int = when (hit.badges.firstOrNull { it in QUALITY_ORDER }) {
    "8K" -> 0
    "4K" -> 1
    "FHD" -> 2
    "HD" -> 3
    "SD" -> 5
    else -> 4
}

private val QUALITY_ORDER = setOf("8K", "4K", "FHD", "HD", "SD")
