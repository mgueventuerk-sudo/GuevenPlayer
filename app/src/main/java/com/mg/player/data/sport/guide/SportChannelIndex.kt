package com.mg.player.data.sport.guide

import com.mg.player.data.db.dao.ChannelIndexRow
import com.mg.player.data.search.SearchText
import com.mg.player.domain.model.ChannelKeyRow
import java.util.Locale

/**
 * Die eigenen Sender, auffindbar über tvg-id und Namen: findet zu „Sky.Sport.Premier.League.de“ bzw. „beIN SPORTS 1“
 * den passenden Sender im Zugang, z. B. „DE| SKY SPORT PREMIER LEAGUE UHD“ bzw. „TR| BEIN SPORTS 1 4K“.
 * Je Zugang nur der beste Sender (die Liste kommt nach Qualität sortiert).
 */
class SportChannelIndex(val rows: List<ChannelIndexRow>) {

    private class Entry(val row: ChannelIndexRow, val country: String?, val key: String)

    private val entries = rows.map {
        Entry(
            it,
            GuideCatalog.countryOfName(it.name) ?: it.epgChannelId.takeIf(String::isNotBlank)?.let(GuideCatalog::countryOfId),
            GuideCatalog.nameKey(it.name),
        )
    }
    private val byTvgId: Map<String, List<Entry>> = entries.filter { it.row.epgChannelId.isNotBlank() }.groupBy { it.row.epgChannelId.lowercase(Locale.ROOT) }
    private val byKey: Map<String, List<Entry>> = entries.filter { it.key.length >= 2 }.groupBy { it.key }
    private val byLoose: Map<String, List<Entry>> = entries.groupBy { GuideCatalog.looseKey(it.key) }.filterKeys { it.length >= 6 }

    /** Wörter der Sendernamen → Sender (für Event-Sender wie „DAZN EVENT 3: Galatasaray – Fenerbahçe“). */
    private val byWord: Map<String, List<Int>> by lazy {
        val map = HashMap<String, MutableList<Int>>()
        entries.forEachIndexed { i, e -> SearchText.words(e.row.name).filter { it.length >= 3 }.toSet().forEach { map.getOrPut(it) { ArrayList() } += i } }
        map
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Länder der Sportsender im Zugang (je Sender einmal) – bestimmt, welche Programmdateien geladen werden. */
    val sportCountries: List<String> by lazy {
        entries.filter { isSportRow(it.row) }.mapNotNull { it.country }
    }

    /** Sender zum Programm-Sender: erst tvg-id, dann Name (gleiches Land bevorzugt), dann locker (nur eindeutig). */
    fun forGuide(channel: GuideChannel): List<ChannelKeyRow> {
        byTvgId[channel.id.lowercase(Locale.ROOT)]?.let { return best(it) }
        val keys = listOf(GuideCatalog.idKey(channel.id), GuideCatalog.nameKey(channel.name)).filter { it.length >= 2 }.distinct()
        return forKeys(keys, channel.country)
    }

    /** Sender zu einem Sendernamen aus einer Online-Quelle („Sky Sport Bundesliga 1“, „DAZN 1“). */
    fun forStation(name: String, country: String? = null): List<ChannelKeyRow> {
        val key = GuideCatalog.nameKey(name)
        return if (key.length < 2) emptyList() else forKeys(listOf(key), country ?: GuideCatalog.countryOfName(name))
    }

    private fun forKeys(keys: List<String>, country: String?): List<ChannelKeyRow> {
        val exact = keys.flatMap { byKey[it].orEmpty() }
        if (exact.isNotEmpty()) return best(preferCountry(exact, country))
        // Locker nur, wenn genau ein Sendername dazu passt (z. B. „Sky Bundesliga 1“ ↔ „Sky Sport Bundesliga 1“).
        val loose = keys.map(GuideCatalog::looseKey).flatMap { byLoose[it].orEmpty() }
        if (loose.isNotEmpty() && loose.map { it.key }.distinct().size == 1) return best(preferCountry(loose, country))
        return emptyList()
    }

    /** Je Zugang: Sender aus dem Land des Programms, sonst einer ohne Länderangabe – nie aus einem anderen Land („CH“ statt „DE“). */
    private fun preferCountry(list: List<Entry>, country: String?): List<Entry> {
        if (country == null) return list
        return list.groupBy { it.row.playlistId }.flatMap { (_, own) -> own.filter { it.country == country }.ifEmpty { own.filter { it.country == null } } }
    }

    /** Je Zugang der beste Sender (Qualität, dann Sortierung; bei Gleichstand die Reihenfolge der Liste). */
    private fun best(list: List<Entry>): List<ChannelKeyRow> =
        list.sortedWith(compareBy({ it.row.qualityRank }, { it.row.sortIndex })).distinctBy { it.row.playlistId }
            .map { ChannelKeyRow(it.row.id, it.row.name, it.row.playlistId) }

    /**
     * Event-Sender des Anbieters, deren Name alle [groups] nennt (je Gruppe ein Wort, z. B. die Teams):
     * „PPV 2: UFC Fight Night Moicano vs Nolan“ für [[ufc], [moicano, nolan]].
     */
    fun eventChannels(groups: List<Set<String>>): List<ChannelKeyRow> {
        if (groups.isEmpty() || groups.any { it.isEmpty() }) return emptyList()
        val candidates = groups.map { tokens -> tokens.flatMap { byWord[it].orEmpty() }.toSet() }
        val hits = candidates.reduce { a, b -> a intersect b }
        return best(hits.sorted().map { entries[it] })
    }

    /** Schlüssel, die ein Abgleich braucht: tvg-ids, Namensschlüssel und lockere Schlüssel (für das Vorfiltern der Sender). */
    class Wanted(val tvgIds: Set<String>, val keys: Set<String>, val looseKeys: Set<String>) {
        /** Wird dieser Sender für einen der Schlüssel gebraucht? */
        fun matches(row: ChannelIndexRow): Boolean {
            if (row.epgChannelId.isNotBlank() && row.epgChannelId.lowercase(Locale.ROOT) in tvgIds) return true
            val key = GuideCatalog.nameKey(row.name)
            return key in keys || GuideCatalog.looseKey(key).let { it.length >= 6 && it in looseKeys }
        }

        companion object {
            /** Aus den Sendern des Programms und den Sendernamen der Online-Quellen. */
            fun of(guideChannels: Collection<GuideChannel>, stations: Collection<String>): Wanted {
                val tvg = HashSet<String>()
                val keys = HashSet<String>()
                guideChannels.forEach { c ->
                    tvg += c.id.lowercase(Locale.ROOT)
                    keys += GuideCatalog.idKey(c.id)
                    keys += GuideCatalog.nameKey(c.name)
                }
                stations.forEach { keys += GuideCatalog.nameKey(it) }
                keys.removeAll { it.length < 2 }
                return Wanted(tvg, keys, keys.mapTo(HashSet()) { GuideCatalog.looseKey(it) })
            }
        }
    }

    companion object {
        /** Sport- oder Event-Sender: am Namen („Sky Sport 1“, „DAZN EVENT 3“) oder an der Gruppe („DE | LIVE EVENTS“). */
        fun isSportRow(row: ChannelIndexRow): Boolean =
            GuideCatalog.isSportChannel(row.name, if (row.categoryName.isBlank()) emptyList() else listOf(row.categoryName))
    }
}
