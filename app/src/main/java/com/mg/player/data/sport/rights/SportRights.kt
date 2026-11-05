package com.mg.player.data.sport.rights

import com.mg.player.data.search.SearchText
import com.mg.player.domain.model.SportEvent

/**
 * Rechte-Tabelle (Plan „Spiel-Finder“ B, Saison 2026/27): wer welchen Wettbewerb in welchem Land überträgt. Recherchiert
 * am 05.11.2026, Quellen je Eintrag. Kommt mit den App-Updates; zur neuen Saison zeigt die Diagnose Abweichungen.
 *
 * Bewusst KEIN harter Filter: Rechte sind oft geteilt (Europa League: RTL, Nitro, RTL+ und seit der Sky-Übernahme durch RTL
 * auch Sky) und Tabellen veralten. Ein Sender gegen die Tabelle wird nur als „widerspricht den Senderechten“ markiert.
 * Länder ohne Eintrag: keine Aussage.
 */
object SportRights {

    /** Ein Wettbewerb: ESPN-Schlüssel und Namen, unter denen ihn andere Quellen (Bzzoiro, Sportmonks) führen. */
    data class Competition(val id: String, val espnKey: String?, val names: List<String>)

    /** Rechteinhaber eines Wettbewerbs in einem Land: Senderfamilien (siehe [FAMILIES]). */
    data class Entry(val competition: String, val country: String, val families: Set<String>, val source: String)

    val COMPETITIONS = listOf(
        Competition("premier-league", "espn:soccer/eng.1", listOf("premier league")),
        Competition("laliga", "espn:soccer/esp.1", listOf("laliga", "la liga")),
        Competition("bundesliga", "espn:soccer/ger.1", listOf("bundesliga")),
        Competition("serie-a", "espn:soccer/ita.1", listOf("serie a")),
        Competition("ligue-1", "espn:soccer/fra.1", listOf("ligue 1")),
        Competition("super-lig", "espn:soccer/tur.1", listOf("super lig", "trendyol super lig")),
        Competition("champions-league", "espn:soccer/uefa.champions", listOf("champions league", "uefa champions league")),
        Competition("europa-league", "espn:soccer/uefa.europa", listOf("europa league", "uefa europa league")),
        Competition("ufc", "espn:mma/ufc", listOf("ufc")),
    )

    val ENTRIES = listOf(
        Entry(
            "bundesliga", "DE", setOf("sky", "dazn", "sat1"),
            "tvmovie.de/ran.de 2025: Sky Fr 20:30 + Sa 15:30/18:30, DAZN Sa-Konferenz + So, Sat.1 9 Spiele (gilt bis 2028/29)",
        ),
        Entry("champions-league", "DE", setOf("dazn", "prime"), "test.de/tvmovie.de 2025/26: DAZN 186 Spiele, Prime 17 (bis 2026/27)"),
        Entry("europa-league", "DE", setOf("rtl", "sky"), "sportbusiness.com, ran.de 2026/27: RTL/Nitro/RTL+, Sky bis zu 10 Spiele je Spieltag"),
        Entry("premier-league", "DE", setOf("sky", "dazn"), "fussball.tv, tv-angebote.de: Sky (große Auswahl) und DAZN"),
        Entry("laliga", "DE", setOf("dazn"), "fussball.tv, tv-angebote.de: DAZN"),
        Entry("serie-a", "DE", setOf("dazn"), "fussball.tv: DAZN"),
        Entry("ufc", "DE", setOf("dazn"), "sportcal.com: DAZN verlängert UFC-Rechte für Deutschland bis 2027"),
        Entry("ufc", "AT", setOf("dazn"), "advanced-television.com: DAZN verlängert UFC in DACH"),
        Entry("super-lig", "TR", setOf("bein"), "beinsports.com.tr / gzt.com 2026/27: beIN Sports"),
        Entry("premier-league", "TR", setOf("bein"), "gzt.com 2026/27: beIN verlängert bis 2027/28"),
        Entry("laliga", "TR", setOf("ssport"), "gzt.com/teknoblog 2026/27: S Sport / S Sport Plus"),
        Entry("serie-a", "TR", setOf("ssport"), "gzt.com/teknoblog 2026/27: S Sport / S Sport Plus"),
        Entry("bundesliga", "TR", setOf("ssport"), "gzt.com/teknoblog 2026/27: S Sport / S Sport Plus"),
        Entry("champions-league", "TR", setOf("trt", "tabii"), "gzt.com 2026/27: TRT und tabii"),
    )

    /** Senderfamilien und die Wörter, an denen man sie im Kanalnamen erkennt (gefaltet, als Wortfolge). */
    val FAMILIES: Map<String, List<List<String>>> = mapOf(
        "sky" to listOf(listOf("sky")),
        "dazn" to listOf(listOf("dazn")),
        "sat1" to listOf(listOf("sat", "1"), listOf("sat1")),
        "prime" to listOf(listOf("prime"), listOf("amazon")),
        "rtl" to listOf(listOf("rtl"), listOf("nitro"), listOf("rtlplus")),
        "magenta" to listOf(listOf("magenta")),
        "ard" to listOf(listOf("ard"), listOf("das", "erste")),
        "zdf" to listOf(listOf("zdf")),
        "bein" to listOf(listOf("bein")),
        "ssport" to listOf(listOf("s", "sport")),
        "trt" to listOf(listOf("trt")),
        "tabii" to listOf(listOf("tabii")),
        "tod" to listOf(listOf("tod")),
        "exxen" to listOf(listOf("exxen")),
    )

    /** Ergebnis der Prüfung eines Senders. */
    enum class Verdict { ALLOWED, CONFLICT, UNKNOWN }

    /** Wettbewerb eines Spiels: über den ESPN-Schlüssel, sonst über den Liganamen (ohne Akzente, Klein). */
    fun competitionOf(event: SportEvent): Competition? {
        val league = event.id.substringBefore('#')
        COMPETITIONS.firstOrNull { it.espnKey == league }?.let { return it }
        val name = SearchText.words(event.league).joinToString(" ")
        return COMPETITIONS.firstOrNull { c -> c.names.any { it == name } }
    }

    /** Senderfamilie eines Kanalnamens, null = keine bekannte Familie. */
    fun familyOf(channelName: String): String? {
        val words = SearchText.words(channelName)
        return FAMILIES.entries.firstOrNull { (_, phrases) -> phrases.any { containsPhrase(words, it) } }?.key
    }

    /**
     * Passt der Sender [channelName] (Land [country], z. B. „de“) zu den Rechten des Spiels? UNKNOWN, wenn Wettbewerb,
     * Land oder Familie nicht in der Tabelle stehen – dann gibt es keine Aussage.
     */
    fun verdict(event: SportEvent, channelName: String, country: String?): Verdict {
        val entry = entryFor(event, country)
        val family = familyOf(channelName)
        return when {
            entry == null || family == null -> Verdict.UNKNOWN
            family in entry.families -> Verdict.ALLOWED
            else -> Verdict.CONFLICT
        }
    }

    private fun entryFor(event: SportEvent, country: String?): Entry? {
        val competition = competitionOf(event) ?: return null
        val code = country?.uppercase()?.let { if (it == "UK") "GB" else it } ?: return null
        return ENTRIES.firstOrNull { it.competition == competition.id && it.country == code }
    }

    /** Senderfamilien mit Rechten für das Spiel in diesen Ländern (Reihenfolge der Suche, Plan D). */
    fun familiesFor(event: SportEvent, countries: Set<String>): Set<String> {
        val competition = competitionOf(event) ?: return emptySet()
        return ENTRIES.filter { it.competition == competition.id && it.country in countries }.flatMapTo(HashSet()) { it.families }
    }

    private fun containsPhrase(words: List<String>, phrase: List<String>): Boolean =
        phrase.isNotEmpty() && (0..words.size - phrase.size).any { i -> phrase.indices.all { words[i + it] == phrase[it] } }
}
