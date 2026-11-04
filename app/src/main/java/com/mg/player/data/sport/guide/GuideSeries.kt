package com.mg.player.data.sport.guide

import com.mg.player.data.search.SearchText
import com.mg.player.data.sport.SportSource
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.model.SportGroups
import com.mg.player.domain.model.SportLeague

/**
 * Sportarten ohne Spielplan-Quelle (MotoGP, Boxen, Darts, Handball, EuroLeague, Olympia, Türkiye Kupası): Die Events
 * kommen direkt aus dem öffentlichen Sport-Programm. Es zählt jeweils die erste Ausstrahlung (Wiederholungen folgen
 * später), Zusammenfassungen und kurze Beiträge nicht.
 */
class GuideSeries(private val guide: suspend () -> SportGuide) : SportSource {

    data class Series(val slug: String, val label: String, val sport: String, val group: String, val phrases: List<List<String>>) {
        val key: String get() = "$KEY_PREFIX$slug"
    }

    override val name = "TV-Programm"

    override suspend fun leagues(): List<SportLeague> = SERIES.map { SportLeague(it.key, it.label, name, it.group) }

    override fun handles(leagueKey: String) = leagueKey.startsWith(KEY_PREFIX)

    override suspend fun fetch(leagueKey: String, fromMs: Long, toMs: Long): List<SportEvent> {
        val series = SERIES.firstOrNull { it.key == leagueKey } ?: return emptyList()
        return events(series, guide(), fromMs, toMs)
    }

    companion object {
        const val KEY_PREFIX = "tv:"
        private const val MIN = 60_000L

        val SERIES = listOf(
            Series("motogp", "MotoGP", "motogp", SportGroups.MOTOR, listOf(listOf("motogp"), listOf("moto", "gp"))),
            Series("boxing", "Boxen (große Kämpfe)", "boxing", SportGroups.FIGHT, listOf(listOf("boxen"), listOf("boxing"), listOf("boks"))),
            Series("darts", "Darts", "darts", SportGroups.OTHER, listOf(listOf("darts"))),
            Series("handball", "Handball", "handball", SportGroups.OTHER, listOf(listOf("handball"), listOf("hentbol"))),
            Series("euroleague", "Basketball: EuroLeague", "basketball-eu", SportGroups.OTHER, listOf(listOf("euroleague"), listOf("euroliga"))),
            Series(
                "olympia",
                "Olympische Spiele",
                "olympics",
                SportGroups.OTHER,
                listOf(listOf("olympia"), listOf("olympische"), listOf("olympics"), listOf("olimpiyat")),
            ),
            Series("tur.cup", "Türkiye Kupası", "soccer", SportGroups.SOCCER, listOf(listOf("turkiye", "kupasi"), listOf("ztk"))),
        )

        private fun isMarkedLive(title: String): Boolean = title.trimStart().lowercase().let { it.startsWith("live") || it.startsWith("canlı") }

        /** „Live: MotoGP“ → „MotoGP“ (der Vorsatz sagt nichts über das Event). */
        fun cleanTitle(title: String): String = title.trim().removePrefix("Live:").removePrefix("LIVE:").removePrefix("Canlı:").trim()

        private val NOT_EVENT = setOf(
            "highlights", "zusammenfassung", "ozet", "ozetler", "ozetleri", "goller", "golleri", "magazin", "news", "nachrichten",
            "vorschau", "preview", "replay", "wiederholung", "tekrar", "rewind", "best", "classics", "doku", "dokumentation",
        )

        /**
         * Events: Sendung auf einem Sportsender, deren TITEL die Sportart nennt, mindestens 25 Minuten lang, nicht aus dem
         * Archiv (Jahreszahl der Vorjahre) – und entweder als live gekennzeichnet oder die erste Ausstrahlung einer Paarung
         * („Fenerbahçe - Real Madrid“). So bleiben Wiederholungen, Magazine und alte Kämpfe draußen.
         */
        internal fun events(series: Series, guide: SportGuide, fromMs: Long, toMs: Long): List<SportEvent> {
            val seen = HashSet<String>()
            val result = ArrayList<SportEvent>()
            val year = java.util.Calendar.getInstance().apply { timeInMillis = fromMs }.get(java.util.Calendar.YEAR)
            // Etwas früher anfangen: Eine Wiederholung im Zeitraum soll nicht als „erste“ Ausstrahlung zählen.
            guide.startingBetween(fromMs - 2 * 24 * 60 * MIN, toMs).forEach { p ->
                val channel = guide.channels[p.channelId] ?: return@forEach
                if (!GuideCatalog.isSportChannel(channel.id, listOf(channel.name))) return@forEach
                val titleWords = SearchText.words(p.title)
                if (series.phrases.none { containsPhrase(titleWords, it) }) return@forEach
                val words = SearchText.words(p.text)
                if (words.any { it in NOT_EVENT } || p.endMs - p.startMs < 25 * MIN) return@forEach
                if (words.any { it.length == 4 && it.toIntOrNull()?.let { y -> y in 1950 until year } == true }) return@forEach
                val title = cleanTitle(p.title)
                val identity = words.joinToString(" ")
                // Gleiche Sendung zur gleichen Zeit auf mehreren Sendern: ein Event; dieselbe später nochmal: Wiederholung.
                val sameSlot = result.any { it.title == title && kotlin.math.abs(it.startMs - p.startMs) <= 10 * MIN }
                if (sameSlot) return@forEach
                val live = isMarkedLive(p.title) || "live" in words || "canli" in words
                if (!live && !(PAIRING.containsMatchIn(p.text) && seen.add(identity))) return@forEach
                seen += identity
                if (p.startMs !in fromMs..toMs) return@forEach
                result += SportEvent(
                    id = "${series.key}#${p.startMs / MIN}-${identity.hashCode().toUInt().toString(36)}",
                    sport = series.sport,
                    league = series.label,
                    title = title,
                    homeTeam = null,
                    awayTeam = null,
                    startMs = p.startMs,
                    endMs = p.endMs,
                    status = EventStatus.UNKNOWN,
                    stations = listOf(channel.name),
                )
            }
            return result
        }

        private val PAIRING = Regex("""\S\s+(–|-|vs\.?|v)\s+\S""", RegexOption.IGNORE_CASE)

        private fun containsPhrase(words: List<String>, phrase: List<String>): Boolean =
            phrase.isNotEmpty() && (0..words.size - phrase.size).any { i -> phrase.indices.all { words[i + it] == phrase[it] } }
    }
}
