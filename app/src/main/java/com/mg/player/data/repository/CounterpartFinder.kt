package com.mg.player.data.repository

import com.mg.player.core.util.MediaTitle
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.data.combined.Combined
import com.mg.player.data.combined.MatchKeys
import com.mg.player.data.combined.NameRules
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.search.SearchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Derselbe Inhalt in einem anderen Zugang. [qualityRank]: kleiner = besser (nur bei Sendern). */
data class Counterpart(val playlistId: Long, val playlistName: String, val itemId: String, val title: String, val qualityRank: Int = 0)

/**
 * Findet zu einem Sender, Film, einer Serie oder Folge denselben Inhalt in den anderen Zugängen.
 * - Sender: gleiche EPG-ID oder gleicher bereinigter Name („ZDF HD“ = „ZDF FHD“, aber nicht „ZDFneo“).
 * - Filme/Serien: gleiche TMDB-ID, sonst gleicher bereinigter Titel UND gleiches Jahr.
 * - Folgen: über die Serie, dann gleiche Staffel und Folge.
 * Gesperrte und ausgeblendete Gruppen werden nie geliefert. Im Zweifel: kein Treffer statt eines falschen.
 */
class CounterpartFinder(
    private val db: AppDatabase,
    /** Lädt fehlende Folgen einer Serie nach (Xtream lädt sie erst bei Bedarf). */
    private val loadEpisodes: suspend (seriesId: String) -> Unit = {},
) {
    /** Ergebnisse je Eintrag zwischenspeichern – der Fortschritt wird alle paar Sekunden gespeichert. */
    private val cache = ConcurrentHashMap<String, List<Counterpart>>()

    fun clear() = cache.clear()

    suspend fun forItem(itemId: String): List<Counterpart> {
        cache[itemId]?.let { return it }
        val found = withContext(Dispatchers.IO) {
            // „Alle Zugänge“: die Quellen sind die Gegenstücke; ein Original kennt umgekehrt seinen kombinierten Eintrag.
            if (Combined.isCombinedItem(itemId)) return@withContext combinedSources(itemId)
            val direct = when (itemId.substringAfter(':', "").substringBefore(':')) {
                "L" -> channel(itemId)
                "M" -> movie(itemId)
                "S" -> series(itemId)
                "E" -> episode(itemId)
                else -> emptyList()
            }
            direct + combinedOf(itemId)
        }
        cache[itemId] = found
        return found
    }

    private suspend fun names(): Map<Long, String> = db.playlistDao().all().associate { it.id to it.name }

    private suspend fun combinedSources(id: String): List<Counterpart> {
        val names = names()
        val sources = db.combinedDao().sources(id)
        // Folgen einer kombinierten Serie entstehen erst beim Öffnen – dann über Serie + Staffel/Folge.
        if (sources.isEmpty() && ":E:" in id) return episode(id)
        return sources.map { Counterpart(it.playlistId, names[it.playlistId].orEmpty(), it.itemId, "", it.qualityRank) }
    }

    private suspend fun combinedOf(itemId: String): List<Counterpart> =
        db.combinedDao().combinedFor(itemId)?.let { listOf(Counterpart(Combined.ID, Combined.NAME, it, "")) }.orEmpty()

    /**
     * Derselbe Sender in anderen Zugängen. Gibt es „Alle Zugänge“, gilt deren Zuordnung (eine Quelle der Wahrheit).
     * Sonst gleiche EPG-ID oder gleicher Name – aber nur, wenn die Namen nach [NameRules] zusammenpassen: Anbieter geben
     * TRT 2 gern die EPG-ID von TRT 1 (gemeldet nach 3.36: TRT 1 erschien als Quelle von TRT 2, auch bei Favoriten).
     */
    private suspend fun channel(id: String): List<Counterpart> {
        val c = db.channelDao().entity(id) ?: return emptyList()
        val names = names()
        val combinedId = db.combinedDao().combinedFor(id)
        val found = if (combinedId != null) {
            val ids = db.combinedDao().sources(combinedId).map { it.itemId }.filter { it != id }
            val restricted = db.categoryPrefDao().all().filter { it.hidden || it.locked }.mapTo(HashSet()) { it.categoryId }
            db.channelDao().entities(ids).filter { it.categoryId !in restricted }
        } else {
            val own = MatchKeys.channelName(c.groupKey, c.name)
            db.channelDao().counterparts(c.playlistId, c.epgChannelId, c.normalizedName)
                .filter { other -> sameChannel(own, MatchKeys.channelName(other.groupKey, other.name)) }
        }
        return found.filter { it.playlistId != c.playlistId }
            .sortedBy { it.qualityRank }
            .distinctBy { it.playlistId }
            .map { Counterpart(it.playlistId, names[it.playlistId].orEmpty(), it.id, it.name, it.qualityRank) }
    }

    /** Namen (Land:Name) passen: gleiches oder unbekanntes Land, kein Widerspruch nach [NameRules]. */
    private fun sameChannel(a: String?, b: String?): Boolean {
        if (a == null || b == null) return true
        val ca = MatchKeys.countryOf(a)
        val cb = MatchKeys.countryOf(b)
        val countryOk = ca.isEmpty() || cb.isEmpty() || ca == cb
        return countryOk && NameRules.compatible(MatchKeys.withoutCountry(a), MatchKeys.withoutCountry(b))
    }

    private suspend fun movie(id: String): List<Counterpart> {
        val m = db.vodDao().get(id) ?: return emptyList()
        val key = TitleKey.of(m.name, m.year, m.tmdbId) ?: return emptyList()
        val names = names()
        return names.keys.filter { it != m.playlistId }.mapNotNull { pid ->
            db.vodDao().search(pid, key.searchTerm, SEARCH_LIMIT)
                .firstOrNull { key.matches(TitleKey.of(it.name, it.year, it.tmdbId)) }
                ?.let { Counterpart(pid, names[pid].orEmpty(), it.id, it.name) }
        }
    }

    private suspend fun series(id: String): List<Counterpart> {
        val s = db.seriesDao().get(id) ?: return emptyList()
        val key = TitleKey.of(s.name, s.year, s.tmdbId, yearRequired = false) ?: return emptyList()
        val names = names()
        return names.keys.filter { it != s.playlistId }.mapNotNull { pid ->
            db.seriesDao().search(pid, key.searchTerm, SEARCH_LIMIT)
                .firstOrNull { key.matches(TitleKey.of(it.name, it.year, it.tmdbId, yearRequired = false)) }
                ?.let { Counterpart(pid, names[pid].orEmpty(), it.id, it.name) }
        }
    }

    private suspend fun episode(id: String): List<Counterpart> {
        val e = db.seriesDao().episode(id) ?: return emptyList()
        return forItem(e.seriesId).mapNotNull { other ->
            var episodes = db.seriesDao().episodes(other.itemId)
            if (episodes.isEmpty()) {
                runCatchingCancellable { loadEpisodes(other.itemId) }
                episodes = db.seriesDao().episodes(other.itemId)
            }
            episodes.firstOrNull { it.season == e.season && it.episode == e.episode }
                ?.let { Counterpart(other.playlistId, other.playlistName, it.id, it.title) }
        }
    }

    private companion object {
        const val SEARCH_LIMIT = 30
    }
}

/** Vergleichsschlüssel für Filme/Serien: TMDB-ID, sonst bereinigter Titel (+ Jahr). */
internal data class TitleKey(val tmdbId: String?, val title: String, val year: String?, val searchTerm: String) {

    fun matches(other: TitleKey?): Boolean {
        if (other == null) return false
        if (tmdbId != null && other.tmdbId != null) return tmdbId == other.tmdbId
        if (title != other.title) return false
        // Jahr nur vergleichen, wenn beide eins haben (bei Pflicht-Jahr wurde ohne Jahr gar kein Schlüssel gebaut).
        return year == null || other.year == null || year == other.year
    }

    companion object {
        fun of(name: String, year: String?, tmdbId: String?, yearRequired: Boolean = true): TitleKey? {
            val info = MediaTitle.parse(name)
            val title = SearchText.compact(info.title)
            if (title.length < 2) return null
            val y = (year ?: info.year)?.take(4)?.takeIf { it.length == 4 }
            val tmdb = tmdbId?.takeIf { it.isNotBlank() && it != "0" }
            // Ohne TMDB-ID und ohne Jahr ist ein Film zu unsicher (Remakes) – dann lieber kein Gegenstück.
            if (tmdb == null && y == null && yearRequired) return null
            return TitleKey(tmdb, title, y, info.title)
        }
    }
}
