package com.mg.player.data.sport

import com.mg.player.core.util.NameNormalizer
import com.mg.player.data.db.dao.EpgCandidateRow
import com.mg.player.data.db.dao.SportDao
import com.mg.player.data.db.entity.SportEventChannelEntity
import com.mg.player.data.search.SearchText
import com.mg.player.data.sport.guide.SportChannelFinder
import com.mg.player.domain.model.ChannelKeyRow
import com.mg.player.domain.model.SportEvent

/**
 * Ordnet Events Kanälen der Zugänge zu (nur sichtbare, nicht gesperrte Gruppen – das erledigt die DAO). Sicherste zuerst:
 * - Event-Sender des Anbieters, dessen Name das Spiel nennt                               → Score 110 ([SportChannelFinder])
 * - Kontrolle kurz vor Anstoß beim Anbieter bestätigt                                     → Score 105 ([KickoffCheck])
 * - öffentliches Sport-Programm: Sendung am Anstoß nennt beide Teams                     → Score 95  ([SportChannelFinder])
 * - Sendername aus der Online-Quelle bzw. dem Programm                                    → Score 85
 * - EPG des Anbieters: Sendung ±45 min um den Start nennt BEIDE Teams                     → Score 80
 * - ohne Teams (Formel 1, UFC): EPG-Sendung mit dem Stichwort der Serie                  → Score 60
 * - von Hand für die Liga zugeordneter Sender (nur noch Korrektur, wenn sonst nichts passt) → Score 50
 * EPG nur innerhalb seiner Reichweite. Im Zweifel keine Zuordnung – ein falscher Sender ist schlimmer als keiner.
 */
class ChannelEventMatcher(private val dao: SportDao, private val finder: SportChannelFinder? = null) {

    /** Sender laut Sport-Programm aus dem letzten Abgleich (für den Hinweis „läuft auf … – nicht in deinem Zugang“). */
    @Volatile private var lastAnalysis: SportChannelFinder.Analysis? = null

    /** [all]: alle Events des Abrufs – auch die, deren Zuordnung nicht neu berechnet wird (für die Sender-Hinweise). */
    suspend fun match(
        events: List<SportEvent>,
        preferredPlaylistId: Long? = null,
        manual: Map<String, String> = emptyMap(),
        all: List<SportEvent> = events,
    ): List<SportEventChannelEntity> {
        val analysis = if (all.isEmpty()) null else finder?.analyze(all)?.also { lastAnalysis = it }
        if (events.isEmpty()) return emptyList()
        val horizon = dao.epgHorizonMs() ?: 0L
        val stations = if (analysis == null) stationChannels(events) else emptyMap()
        val manualRows = if (manual.isEmpty()) emptyMap() else dao.channelsByIds(manual.values.distinct()).associateBy { it.id }
        // Viele Spiele beginnen gleichzeitig (Samstag 15:30): je Anstoßzeit nur eine EPG-Abfrage.
        val windows = HashMap<Long, List<EpgCandidateRow>>()
        val result = ArrayList<SportEventChannelEntity>()
        for (event in events) {
            val found = ArrayList<Pair<ChannelKeyRow, Int>>()
            event.stations.forEach { station -> stations[NameNormalizer.normalize(station)]?.forEach { found += it to SportChannelFinder.STATION } }
            analysis?.let { found += it.matches(event) }
            manual[leagueOfEvent(event.id)]?.let(manualRows::get)?.let { found += it to MANUAL_SCORE }
            if (event.startMs <= horizon) {
                val rows = windows.getOrPut(event.startMs) { dao.epgStartingBetween(event.startMs - WINDOW_MS, event.startMs + WINDOW_MS) }
                found += epgMatches(event, rows)
            }
            found.groupBy { it.first.id }.values.forEach { list ->
                val (channel, score) = list.maxBy { it.second }
                val bonus = if (channel.playlistId == preferredPlaylistId) 1 else 0
                result += SportEventChannelEntity(event.id, channel.id, channel.playlistId, score + bonus)
            }
        }
        return result
    }

    /** Zugänge wurden aktualisiert: Senderliste beim nächsten Abgleich neu lesen. */
    fun channelsChanged() {
        finder?.invalidate()
    }

    /** Sender laut Sport-Programm ergänzen (Anzeige, wenn der Zugang den Sender nicht hat). */
    fun withBroadcasters(events: List<SportEvent>): List<SportEvent> {
        val analysis = lastAnalysis ?: return events
        return events.map { e ->
            val extra = analysis.broadcasters(e.id)
            if (extra.isEmpty()) e else e.copy(stations = (extra + e.stations).distinct())
        }
    }

    private suspend fun stationChannels(events: List<SportEvent>): Map<String, List<ChannelKeyRow>> {
        val names = events.flatMap { it.stations }.map(NameNormalizer::normalize).filter { it.length >= 2 }.distinct()
        if (names.isEmpty()) return emptyMap()
        val rows = names.chunked(MAX_SQL_ARGS).flatMap { dao.channelsByNormalizedName(it) }
        // Je Name und Zugang nur der beste Kanal (Liste ist nach Qualität sortiert).
        return rows.groupBy { NameNormalizer.normalize(it.name) }.mapValues { (_, list) -> list.distinctBy { it.playlistId } }
    }

    private suspend fun epgMatches(event: SportEvent, rows: List<EpgCandidateRow>): List<Pair<ChannelKeyRow, Int>> {
        val home = event.homeTeam?.let(::teamTokens).orEmpty()
        val away = event.awayTeam?.let(::teamTokens).orEmpty()
        val (score, accepts) = when {
            home.isNotEmpty() && away.isNotEmpty() -> 80 to { words: List<String> -> mentions(words, home) && mentions(words, away) }

            event.homeTeam == null && event.awayTeam == null -> {
                val keys = seriesKeywords(event)
                if (keys.isEmpty()) return emptyList()
                60 to { words: List<String> -> keys.any { key -> containsPhrase(words, key) } }
            }

            else -> return emptyList()
        }
        val hits = rows.filter { accepts(SearchText.words(it.title)) }
        return hits.groupBy { it.playlistId }.flatMap { (playlistId, list) ->
            dao.channelsForEpgKeys(playlistId, list.map { it.channelKey }.distinct().take(MAX_SQL_ARGS)).take(1).map { it to score }
        }
    }

    companion object {
        private const val WINDOW_MS = 45 * 60_000L
        const val MANUAL_SCORE = 50
        private const val MAX_SQL_ARGS = 900

        /** Englische Namen der Quelle → Schreibweise im deutschen EPG (gefaltet, ohne Akzente). */
        private val ALIASES = mapOf(
            "munich" to listOf("munchen"),
            "cologne" to listOf("koln"),
            "nuremberg" to listOf("nurnberg"),
            "hanover" to listOf("hannover"),
            "monchengladbach" to listOf("monchengladbach", "gladbach"),
            "athens" to listOf("athen"),
            "turin" to listOf("turin"),
            "milan" to listOf("mailand", "milan"),
            "rome" to listOf("rom", "roma"),
            "lisbon" to listOf("lissabon"),
            "prague" to listOf("prag"),
            "vienna" to listOf("wien"),
            "copenhagen" to listOf("kopenhagen"),
            "belgrade" to listOf("belgrad"),
            "seville" to listOf("sevilla"),
            // Gängige Kürzel im deutschen EPG („FC Bayern – BVB“).
            "dortmund" to listOf("dortmund", "bvb"),
            "schalke" to listOf("schalke", "s04"),
            "hamburger" to listOf("hamburger", "hamburg", "hsv"),
            "hamburg" to listOf("hamburg", "hsv"),
            "leverkusen" to listOf("leverkusen", "b04"),
            "gladbach" to listOf("gladbach", "bmg"),
            "karlsruher" to listOf("karlsruher", "ksc"),
            "galatasaray" to listOf("galatasaray", "gs"),
            "fenerbahce" to listOf("fenerbahce", "fb"),
            "besiktas" to listOf("besiktas", "bjk"),
        )

        /** Wörter, die viele Vereine tragen – sie sagen nichts darüber, welches Team gemeint ist. */
        private val GENERIC = setOf(
            "fc", "sc", "sv", "vfb", "vfl", "tsg", "fsv", "ssv", "rb", "bsc", "ac", "as", "ss", "cf", "cd", "ud", "sd", "rc",
            "afc", "fk", "sk", "bk", "if", "kv", "club", "borussia", "eintracht", "bayer", "real", "city", "united", "athletic",
            "sporting", "sport", "olympique", "racing", "de", "la", "del", "of", "the", "and", "und", "1",
        )

        /**
         * Markante Wörter eines Teams (gefaltet): „Borussia Dortmund“ → [dortmund], „Bayern Munich“ → [bayern, munchen].
         * Eines davon muss im EPG-Titel stehen.
         */
        fun teamTokens(team: String): Set<String> = SearchText.words(team)
            .filter { it.any(Char::isLetter) && it !in GENERIC }
            .flatMap { ALIASES[it] ?: listOf(it) }
            .filter { it.length >= 4 || ALIASES.values.any { list -> it in list } }
            .toSet()

        /** Wort genau gleich, oder bei langen Namen als Wortanfang („hamburg“ in „Hamburger SV“). */
        internal fun mentions(words: List<String>, tokens: Set<String>): Boolean =
            words.any { w -> tokens.any { t -> w == t || (t.length >= 6 && w.startsWith(t)) } }

        private fun containsPhrase(words: List<String>, phrase: List<String>): Boolean =
            phrase.isNotEmpty() && (0..words.size - phrase.size).any { i -> phrase.indices.all { words[i + it] == phrase[it] } }

        /** Stichwörter für Serien ohne Teams (gefaltet, als Wortfolge). */
        internal fun seriesKeywords(event: SportEvent): List<List<String>> = when (event.sport) {
            "racing" -> listOf(listOf("formel", "1"), listOf("f1"), listOf("formula", "1"))
            "mma" -> listOf(listOf("ufc"))
            else -> emptyList()
        }
    }
}
