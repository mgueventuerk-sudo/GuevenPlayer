package com.mg.player.data.sport.guide

import com.mg.player.data.db.dao.ChannelIndexRow
import com.mg.player.data.db.dao.SportDao
import com.mg.player.data.sport.ChannelEventMatcher.Companion.teamTokens
import com.mg.player.domain.model.ChannelKeyRow
import com.mg.player.domain.model.SportEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Findet die Sender zu Spielen und Events – in dieser Reihenfolge der Sicherheit:
 * Event-Sender des Anbieters (Name nennt das Spiel) → öffentliches Sport-Programm → Sendernamen aus den Online-Quellen.
 * (Das EPG des Anbieters und die Zuordnung von Hand prüft [com.mg.player.data.sport.ChannelEventMatcher].)
 */
class SportChannelFinder(
    private val dao: SportDao,
    private val store: SportGuideStore,
    /** „Online-Zusatzinfos“ und „Sport-Programm aus dem Internet“ an? */
    private val guideEnabled: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Nur Sport- und Event-Sender (klein) – für die Länder des Programms und die Event-Sender. */
    @Volatile private var index: Pair<Long, SportChannelIndex>? = null

    /** Sport-Sender plus die Sender, die Programm und Online-Quellen nennen (z. B. „Das Erste“ beim Pokal). */
    @Volatile private var matchIndex: Triple<Long, Int, SportChannelIndex>? = null

    /** Sport- und Event-Sender (höchstens 5 Minuten alt – nach einer Aktualisierung der Zugänge neu). */
    suspend fun index(): SportChannelIndex = withContext(Dispatchers.IO) {
        index?.takeIf { clock() - it.first < INDEX_TTL_MS }?.second
            ?: SportChannelIndex(scan { SportChannelIndex.isSportRow(it) }).also { index = clock() to it }
    }

    fun invalidate() {
        index = null
        matchIndex = null
    }

    /** Öffentliches Sport-Programm der Länder, aus denen der Zugang Sportsender hat (leer, wenn ausgeschaltet). */
    suspend fun guide(): SportGuide {
        if (!guideEnabled()) return SportGuide.EMPTY
        val countries = GuideCatalog.countriesToLoad(index().sportCountries)
        return store.guide(countries)
    }

    suspend fun analyze(events: List<SportEvent>): Analysis {
        val sport = index()
        val guide = guide()
        val wanted = SportChannelIndex.Wanted.of(guide.channels.values, events.flatMap { it.stations })
        val index = matchIndexFor(sport, wanted)
        val hits = events.associate { it.id to GuideMatching.find(it, guide) }.filterValues { it.isNotEmpty() }
        return Analysis(index, guide, hits)
    }

    /**
     * Sport-Sender plus die übrigen Sender, deren Schlüssel gebraucht werden. Die Senderliste wird dafür blockweise
     * durchgegangen – im Speicher bleibt nur, was passt (bei 100.000 Sendern statt rund 40 MB wenige MB).
     */
    private suspend fun matchIndexFor(sport: SportChannelIndex, wanted: SportChannelIndex.Wanted): SportChannelIndex = withContext(Dispatchers.IO) {
        val signature = wanted.keys.hashCode() * 31 + wanted.tvgIds.hashCode()
        matchIndex?.takeIf { clock() - it.first < INDEX_TTL_MS && it.second == signature }?.third?.let { return@withContext it }
        val sportIds = sport.rows.mapTo(HashSet()) { it.id }
        val extra = scan { it.id !in sportIds && wanted.matches(it) }
        SportChannelIndex(sport.rows + extra).also { matchIndex = Triple(clock(), signature, it) }
    }

    /** Alle sichtbaren Sender blockweise durchgehen und nur die behalten, für die [keep] gilt. */
    private suspend fun scan(keep: (ChannelIndexRow) -> Boolean): List<ChannelIndexRow> {
        val result = ArrayList<ChannelIndexRow>()
        var after = Long.MIN_VALUE
        while (true) {
            val chunk = dao.channelIndexChunk(after, CHUNK)
            if (chunk.isEmpty()) break
            chunk.forEach { c -> c.toIndexRow().takeIf(keep)?.let(result::add) }
            after = chunk.last().rowId
        }
        return result
    }

    class Analysis(private val index: SportChannelIndex, private val guide: SportGuide, private val hits: Map<String, List<GuideMatching.Hit>>) {

        /** Sender laut Programm („Sky Sport Premier League“) – auch wenn der Zugang ihn nicht hat (Hinweis in der Übersicht). */
        fun broadcasters(eventId: String): List<String> =
            hits[eventId].orEmpty().sortedByDescending { it.score }.mapNotNull { guide.channels[it.program.channelId]?.name }.distinct()

        /** Alle gefundenen Sender mit Sicherheit (Score). */
        fun matches(event: SportEvent): List<Pair<ChannelKeyRow, Int>> = buildList {
            eventChannelGroups(event)?.let { groups -> index.eventChannels(groups).forEach { add(it to EVENT_CHANNEL) } }
            hits[event.id].orEmpty().forEach { hit ->
                val channel = guide.channels[hit.program.channelId] ?: return@forEach
                index.forGuide(channel).forEach { add(it to hit.score) }
            }
            event.stations.forEach { station -> index.forStation(station).forEach { add(it to STATION) } }
        }

        /** Gibt es einen Kanal zu diesem Sendernamen der Online-Quelle (Diagnose in den Einstellungen)? */
        fun hasStation(station: String): Boolean = index.forStation(station).isNotEmpty()

        /** Wörter, die ein Event-Sender nennen muss: beide Teams bzw. Serie + Kämpfer/Turnier. */
        private fun eventChannelGroups(event: SportEvent): List<Set<String>>? {
            if (event.id.startsWith(GuideSeries.KEY_PREFIX)) return null
            val home = event.homeTeam?.let(::teamTokens).orEmpty()
            val away = event.awayTeam?.let(::teamTokens).orEmpty()
            if (home.isNotEmpty() && away.isNotEmpty()) return listOf(home, away)
            val series = GuideMatching.seriesPhrases(event).filter { it.size == 1 }.map { it.first() }.toSet()
            val specific = GuideMatching.specificTokens(event)
            return if (series.isNotEmpty() && specific.isNotEmpty()) listOf(series, specific) else null
        }
    }

    companion object {
        const val EVENT_CHANNEL = 110
        const val STATION = 85
        private const val INDEX_TTL_MS = 5 * 60_000L
        private const val CHUNK = 2_000
    }
}
