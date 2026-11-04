package com.mg.player.data.combined

import com.mg.player.core.log.AppLog
import com.mg.player.data.db.AppDatabase

/**
 * Eine Zuordnung für jede Senderliste: Innerhalb eines Zugangs werden alle Quellen desselben Senders unter einem
 * gemeinsamen Gruppenschlüssel zusammengefasst – mit derselben Logik wie „Alle Zugänge“ ([ChannelClusterer]).
 *
 * Bis 3.37 galten in einem Zugang nur exakt gleiche bereinigte Namen als derselbe Sender. „AL JAZERA HD“ und
 * „AL JAZEERA LQ“ oder „SKY SPORT F1 4K“ und „SKY SPORTS F1 HD“ standen dann zweimal in der Liste, und beim Umschalten
 * kam derselbe Sender aus einer anderen Quelle. Liste, Quellen-Wahl, Umschalten und gemerkte Quelle hängen alle am
 * Gruppenschlüssel – deshalb wird er hier vereinheitlicht.
 */
object VariantUnifier {
    private const val CHUNK = 500

    /** Liefert die Zahl der Quellen, die einer anderen Gruppe zugeschlagen wurden. */
    suspend fun unify(db: AppDatabase, playlistId: Long, catalog: ChannelCatalog): Int {
        val rows = db.combinedDao().channelSources(playlistId).filter { it.groupKey.isNotBlank() }
        if (rows.size < 2) return 0
        var moved = 0
        for (cluster in ChannelClusterer.cluster(rows, MatchRules(), EpgPrints(), catalog)) {
            val keys = cluster.members.map { it.groupKey }
            if (keys.distinct().size < 2) continue
            val target = representative(keys)
            val ids = cluster.members.filter { it.groupKey != target }.map { it.id }
            ids.chunked(CHUNK).forEach { db.channelDao().setGroupKey(it, target) }
            moved += ids.size
        }
        if (moved > 0) AppLog.log("Gruppen", "$moved Quellen gleicher Sender zusammengefasst (Zugang $playlistId)")
        return moved
    }

    /** Häufigster Schlüssel; bei Gleichstand der mit Land, dann der kürzere, dann alphabetisch (stabil). */
    internal fun representative(keys: List<String>): String = keys.groupingBy { it }.eachCount().entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { if (it.key.substringBefore(':', "").isEmpty()) 1 else 0 }
                .thenBy { it.key.length }
                .thenBy { it.key },
        ).first().key
}
