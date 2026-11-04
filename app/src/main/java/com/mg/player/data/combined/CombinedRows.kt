package com.mg.player.data.combined

import com.mg.player.core.util.NameNormalizer
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.db.dao.EpgSlot
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.search.SearchText

/**
 * Zeilen von „Alle Zugänge“. Sie tragen keine eigene Stream-Adresse: Abgespielt wird immer eine Quelle aus
 * `combined_links`. Name, Logo, Gruppe und Reihenfolge kommen vom Original aus dem vorderen Zugang (vertraute
 * Schreibweise), das EPG von der besten Quelle mit Programmdaten.
 */
internal object CombinedRows {

    /** [primary]: Original aus dem vorderen Zugang, [byQuality]: alle Quellen, beste zuerst, [epgSource]: Quelle fürs EPG. */
    class ChannelParts(val primary: ChannelSourceRow, val byQuality: List<ChannelSourceRow>, val epgSource: ChannelSourceRow, val categoryId: String)

    fun channel(id: String, parts: ChannelParts, rank: Map<Long, Int>, generation: Long) = with(parts) { entity(id, rank, generation) }

    private fun ChannelParts.entity(id: String, rank: Map<Long, Int>, generation: Long) = ChannelEntity(
        id = id,
        playlistId = Combined.ID,
        categoryId = categoryId,
        remoteId = "",
        name = primary.name,
        logoUrl = primary.logoUrl ?: byQuality.firstNotNullOfOrNull { it.logoUrl },
        streamUrl = "",
        epgChannelId = epgSource.epgChannelId,
        normalizedName = NameNormalizer.normalize(primary.name),
        epgKey = epgSource.epgKey,
        sortIndex = (rank[primary.playlistId] ?: 0) * CombinedCategories.RANK_STEP + primary.sortIndex,
        catchupDays = byQuality.maxOf { it.catchupDays },
        generation = generation,
        // Eigener Schlüssel: Die Varianten stecken schon in den Quellen, nicht nochmal zusammenfassen.
        groupKey = id,
        qualityRank = byQuality.first().qualityRank,
        epgPlaylistId = epgSource.playlistId,
    )
}

/** Programmdaten für die Zuordnung: Fingerabdrücke zum Verbinden und Sendungstitel zum Gegenprüfen. */
internal class EpgPrints(
    /** Sender ohne Partner über Namen/EPG-ID: die nächsten drei Sendungen (zum Verbinden). */
    val link: Map<String, String> = emptyMap(),
    /**
     * Sender mit möglichen Partnern: Titel der nächsten Sendungen als sortierte Hashwerte (Zeit egal – Anbieter
     * verschieben das EPG). Hashwerte statt Texte: bei 30.000 Sendern wenige hundert KB statt Dutzender MB.
     */
    val titles: Map<String, IntArray> = emptyMap(),
)

/**
 * Programm-Fingerabdruck je Sender: die nächsten drei Sendungen (Start auf 5 Minuten gerundet, Titel vereinheitlicht).
 * Dazu für alle Sender, die einen möglichen Partner in einem anderen Zugang haben, die Titel der nächsten Sendungen:
 * Teilen zwei Kandidaten keinen einzigen Titel, laufen dort verschiedene Programme – sie werden nicht verbunden.
 */
internal object EpgFingerprints {
    private const val WINDOW_MS = 4 * 60 * 60 * 1000L
    private const val ROUND_MS = 5 * 60 * 1000L
    private const val PROGRAMS = 3
    private const val TITLES = 8

    suspend fun of(db: AppDatabase, playlistIds: List<Long>, rows: List<ChannelSourceRow>, now: Long): EpgPrints {
        val out = Collector(unmatched(rows).mapTo(HashSet()) { it.id }, now)
        for (pid in playlistIds) {
            val wanted = rows.filter { it.playlistId == pid && it.epgKey.isNotBlank() }.groupBy { it.epgKey }
            if (wanted.isEmpty()) continue
            val slots = db.combinedDao().epgSlots(pid, now - WINDOW_MS / 2, now + WINDOW_MS).groupBy { it.channelKey }
            for ((key, list) in slots) wanted[key]?.let { out.add(it, list) }
        }
        return EpgPrints(out.link, out.titles)
    }

    /** Sammelt je Sender entweder den Abdruck (ohne Partner) oder die Titel (mit möglichem Partner). */
    private class Collector(private val withoutPartner: Set<String>, private val now: Long) {
        val link = HashMap<String, String>()
        val titles = HashMap<String, IntArray>()

        fun add(owners: List<ChannelSourceRow>, list: List<EpgSlot>) {
            val upcoming = list.filter { it.startMs >= now - ROUND_MS }
            val print = upcoming.takeIf { it.size >= PROGRAMS }?.take(PROGRAMS)
                ?.joinToString("|") { "${it.startMs / ROUND_MS}:${SearchText.compact(it.title)}" }
            val names = list.take(TITLES).map { SearchText.compact(it.title) }.filter { it.length >= 2 }
                .map { it.hashCode() }.distinct().sorted().toIntArray()
            for (row in owners) {
                if (row.id in withoutPartner) {
                    print?.let { link[row.id] = it }
                } else if (names.isNotEmpty()) {
                    titles[row.id] = names
                }
            }
        }
    }

    /** Sender, deren EPG-ID und Name in keinem anderen Zugang vorkommen. */
    private fun unmatched(rows: List<ChannelSourceRow>): List<ChannelSourceRow> {
        val playlistsPerKey = HashMap<String, MutableSet<Long>>()
        rows.forEach { r ->
            (MatchKeys.epgKeys(r.epgChannelId) + listOfNotNull(MatchKeys.channelName(r.groupKey)?.let(MatchKeys::withoutCountry)))
                .forEach { playlistsPerKey.getOrPut(it) { HashSet() } += r.playlistId }
        }
        return rows.filter { r ->
            (MatchKeys.epgKeys(r.epgChannelId) + listOfNotNull(MatchKeys.channelName(r.groupKey)?.let(MatchKeys::withoutCountry)))
                .all { (playlistsPerKey[it]?.size ?: 0) <= 1 }
        }
    }
}
