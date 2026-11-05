package com.mg.player.domain.model

import androidx.compose.runtime.Immutable

enum class EventStatus { PRE, IN, POST, UNKNOWN }

/** Ein Sport-Event aus einer Online-Quelle (ESPN, Sportmonks). [endMs] null = Ende unbekannt (wird geschätzt). */
@Immutable
data class SportEvent(
    val id: String,
    val sport: String,
    val league: String,
    val title: String,
    val homeTeam: String?,
    val awayTeam: String?,
    val startMs: Long,
    val endMs: Long?,
    val status: EventStatus,
    val stations: List<String>,
    /** Spielstand laufender/beendeter Spiele, z. B. „2:1 · 67'“ – nur im Speicher, nicht in der Datenbank. */
    val score: String? = null,
)

/** „2:1 · 67'“ aus den Toren und der Spielminute bzw. dem Spielstand-Text; null, wenn kein Stand bekannt ist. */
fun sportScore(home: String?, away: String?, detail: String?): String? {
    val h = home?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    val a = away?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    val d = detail?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    return if (d != null) "$h:$a · $d" else "$h:$a"
}

/** Event mit dem besten passenden Kanal aus den Zugängen. */
@Immutable
data class MatchedEvent(
    val event: SportEvent,
    val channelId: String,
    val channelName: String,
    val categoryId: String,
    /** Woher die Zuordnung stammt (Anzeige „laut TV-Programm“ usw.). */
    val source: MatchSource = MatchSource.STATION,
) {
    /** Sender nur vermutet (Ersatzsender, unsichere Quelle) – nicht durch Sendeliste, Programm oder EPG belegt. */
    val guessed: Boolean get() = source.guessed
}

/** Eine Liga, der man folgen kann. [key] z. B. „espn:soccer/ger.1“ oder „sm:271“. */
@Immutable
data class SportLeague(val key: String, val name: String, val source: String, val group: String = "Fußball ($source)")

/** Gruppen der Ligenauswahl (nach Sportart statt nach Quelle). */
object SportGroups {
    const val SOCCER = "Fußball"
    const val FIGHT = "Kampfsport"
    const val MOTOR = "Motorsport"
    const val US = "US-Sport"
    const val TENNIS = "Tennis"
    const val OTHER = "Weitere Sportarten"

    /** Reihenfolge der Abschnitte. */
    val ORDER = listOf(SOCCER, FIGHT, MOTOR, US, TENNIS, OTHER)

    fun of(sport: String): String = when (sport) {
        "soccer" -> SOCCER
        "mma", "boxing" -> FIGHT
        "racing", "motogp" -> MOTOR
        "football", "basketball", "hockey" -> US
        "tennis" -> TENNIS
        else -> OTHER
    }

    /** Einträge nach Sportart (Reihenfolge wie [ORDER]); innerhalb einer Sportart bleibt die Reihenfolge erhalten. */
    fun <T> split(items: List<T>, sport: (T) -> String): List<Pair<String, List<T>>> {
        val byGroup = items.groupBy { of(sport(it)) }
        return ORDER.mapNotNull { group -> byGroup[group]?.let { group to it } }
    }
}
