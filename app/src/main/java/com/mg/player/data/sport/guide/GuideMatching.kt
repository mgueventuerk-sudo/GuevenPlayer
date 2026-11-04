package com.mg.player.data.sport.guide

import com.mg.player.data.search.SearchText
import com.mg.player.data.sport.ChannelEventMatcher.Companion.mentions
import com.mg.player.data.sport.ChannelEventMatcher.Companion.teamTokens
import com.mg.player.domain.model.SportEvent

/**
 * Findet ein Spiel bzw. Event im öffentlichen Sport-Programm: Sendung beginnt nahe am Anstoß und nennt die Teams
 * (bzw. Serie und Kämpfer/Turnier). Wiederholungen und Zusammenfassungen zählen nicht.
 */
object GuideMatching {

    data class Hit(val program: GuideProgram, val score: Int)

    const val BOTH_TEAMS = 95
    const val ONE_TEAM = 72
    const val SERIES_SPECIFIC = 92
    const val SERIES_ONLY = 70
    const val SAME_PROGRAM = 96

    private const val MIN = 60_000L

    /** Wörter, die eine Wiederholung oder Zusammenfassung kennzeichnen (gefaltet). */
    private val NOT_LIVE = setOf(
        "highlights", "zusammenfassung", "zusammenfassungen", "ozet", "ozetler", "ozetleri", "goller", "golleri", "rewind",
        "replay", "wiederholung", "vorschau", "preview", "magazin", "magazine", "kompakt", "90in30", "classics", "klassiker",
        "reloaded", "recap", "tekrar", "best",
    )

    fun find(event: SportEvent, guide: SportGuide): List<Hit> {
        if (guide.isEmpty) return emptyList()
        if (event.id.startsWith(GuideSeries.KEY_PREFIX)) return sameProgram(event, guide)
        val (before, after) = window(event)
        val candidates = guide.startingBetween(event.startMs - before, event.startMs + after)
            .map { it to SearchText.words(it.text) }
            .filter { (_, words) -> !isNotLive(words) }
        val scorer = scorer(event) ?: return emptyList()
        return candidates.mapNotNull { (p, words) -> scorer(words)?.let { Hit(p, it + adjustment(p, words, guide)) } }
            .groupBy { it.program.channelId }.map { (_, hits) -> hits.maxBy { it.score } }
    }

    /** Als live gekennzeichnet: etwas sicherer. Nachrichtensender (Sky Sport News, beIN Haber): nur Bericht, kein Spiel. */
    private fun adjustment(p: GuideProgram, words: List<String>, guide: SportGuide): Int {
        val name = guide.channels[p.channelId]?.name.orEmpty().lowercase()
        return when {
            "news" in name || "haber" in name || "nachrichten" in name -> -15
            "live" in words || "canli" in words -> 2
            else -> 0
        }
    }

    /** Bewertet den Text einer Sendung für dieses Event (null = passt nicht); null, wenn das Event nicht prüfbar ist. */
    fun scorer(event: SportEvent): ((List<String>) -> Int?)? {
        val home = event.homeTeam?.let(::teamTokens).orEmpty()
        val away = event.awayTeam?.let(::teamTokens).orEmpty()
        if (home.isNotEmpty() && away.isNotEmpty()) {
            val league = leagueTokens(event.league)
            return fun(words: List<String>): Int? {
                val h = mentions(words, home)
                val a = mentions(words, away)
                return when {
                    h && a -> BOTH_TEAMS
                    (h || a) && league.isNotEmpty() && mentions(words, league) && words.size < 40 -> ONE_TEAM
                    else -> null
                }
            }
        }
        if (event.homeTeam != null || event.awayTeam != null) return null
        val series = seriesPhrases(event)
        if (series.isEmpty()) return null
        val specific = specificTokens(event)
        // Nennt das Event einen Ort bzw. Kämpfer („Mexico City Grand Prix“), muss die Sendung ihn auch nennen –
        // sonst wäre jede Formel-1-Sendung (auch eine Wiederholung eines anderen Rennens) ein Treffer.
        return fun(words: List<String>): Int? = when {
            series.none { containsPhrase(words, it) } -> null
            specific.isEmpty() -> SERIES_ONLY
            mentions(words, specific) -> SERIES_SPECIFIC
            else -> null
        }
    }

    /** Wiederholung oder Zusammenfassung? */
    fun isNotLive(words: List<String>): Boolean = words.any { it in NOT_LIVE }

    /** Events aus dem Programm selbst („tv:…“): genau diese Sendung (gleicher Titel, gleicher Beginn) auf allen Sendern. */
    private fun sameProgram(event: SportEvent, guide: SportGuide): List<Hit> = guide.startingBetween(event.startMs - 5 * MIN, event.startMs + 5 * MIN)
        .filter { GuideSeries.cleanTitle(it.title) == event.title }
        .map { Hit(it, SAME_PROGRAM) }

    /** Wie weit vor bzw. nach dem Anstoß die Übertragung beginnen darf (Vorberichte, Hauptkarte bei UFC). */
    internal fun window(event: SportEvent): Pair<Long, Long> = when (event.sport) {
        "mma" -> 90 * MIN to 240 * MIN
        "racing" -> 60 * MIN to 30 * MIN
        "tennis" -> 60 * MIN to ((event.endMs ?: event.startMs) - event.startMs).coerceIn(0, 14 * 24 * 60 * MIN)
        else -> 75 * MIN to 20 * MIN
    }

    /** Stichwörter der Serie (gefaltet, als Wortfolge). */
    internal fun seriesPhrases(event: SportEvent): List<List<String>> = when (event.sport) {
        "racing" -> listOf(listOf("formel", "1"), listOf("f1"), listOf("formula", "1"))
        "mma" -> listOf(listOf("ufc"))
        "tennis" -> majorTennis(event.title)
        else -> emptyList()
    }

    private fun majorTennis(title: String): List<List<String>> {
        val words = SearchText.words(title)
        return when {
            "wimbledon" in words -> listOf(listOf("wimbledon"))
            "australian" in words -> listOf(listOf("australian", "open"))
            "garros" in words || "french" in words -> listOf(listOf("roland", "garros"), listOf("french", "open"))
            "us" in words && "open" in words -> listOf(listOf("us", "open"))
            "finals" in words -> listOf(listOf("atp", "finals"), listOf("wta", "finals"))
            else -> emptyList()
        }
    }

    /** Markante Wörter eines Events ohne Teams: „UFC Fight Night: Moicano vs. Nolan“ → moicano, nolan. */
    internal fun specificTokens(event: SportEvent): Set<String> = SearchText.words(event.title)
        .filter { it.length >= 4 && it !in GENERIC_EVENT_WORDS && it.any(Char::isLetter) }
        .flatMap { PLACES[it] ?: listOf(it) }
        .toSet()

    /** Englische Orte der Quelle → Schreibweise im deutschen bzw. türkischen Programm. */
    private val PLACES = mapOf(
        "mexico" to listOf("mexico", "mexiko", "meksika"),
        "brazil" to listOf("brazil", "brasilien", "brezilya"),
        "paulo" to listOf("paulo", "brasilien", "brezilya"),
        "qatar" to listOf("qatar", "katar"),
        "abu" to listOf("abu"),
        "dhabi" to listOf("dhabi"),
        "vegas" to listOf("vegas"),
        "japan" to listOf("japan", "japonya"),
        "spain" to listOf("spain", "spanien", "ispanya"),
        "italy" to listOf("italy", "italien", "italya"),
        "austria" to listOf("austria", "osterreich", "avusturya"),
        "britain" to listOf("britain", "grossbritannien", "silverstone"),
        "belgium" to listOf("belgium", "belgien", "belcika"),
        "netherlands" to listOf("netherlands", "niederlande", "hollanda"),
        "hungary" to listOf("hungary", "ungarn", "macaristan"),
        "monaco" to listOf("monaco"),
        "canada" to listOf("canada", "kanada"),
        "china" to listOf("china", "cin"),
        "australia" to listOf("australia", "australien", "avustralya"),
        "bahrain" to listOf("bahrain", "bahreyn"),
        "saudi" to listOf("saudi", "saudi-arabien", "suudi"),
        "azerbaijan" to listOf("azerbaijan", "aserbaidschan", "azerbaycan"),
        "singapore" to listOf("singapore", "singapur"),
        "miami" to listOf("miami"),
        "imola" to listOf("imola"),
        "emilia" to listOf("emilia"),
    )

    private val GENERIC_EVENT_WORDS = setOf(
        "fight", "night", "grand", "prix", "formula", "formel", "open", "championship", "finals", "final", "race", "rennen",
        "qualifying", "practice", "sprint", "tennis", "event", "main", "card", "prelims", "city", "united", "states", "arabian",
    )

    /** Liga-Wörter für „ein Team + Liga“ (z. B. „Premier League – Chelsea“). */
    private fun leagueTokens(league: String): Set<String> = SearchText.words(league).filter { it.length >= 5 && it != "league" && it != "liga" }.toSet()

    private fun containsPhrase(words: List<String>, phrase: List<String>): Boolean =
        phrase.isNotEmpty() && (0..words.size - phrase.size).any { i -> phrase.indices.all { words[i + it] == phrase[it] } }
}
