package com.mg.player.domain.sport

import androidx.compose.runtime.Immutable
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.MatchedEvent
import com.mg.player.domain.model.SportGroups
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class UpcomingGroup(val label: String) { TODAY("Heute"), TOMORROW("Morgen"), THIS_WEEK("Diese Woche") }

/** Spiele einer Sportart innerhalb eines Abschnitts; [sport] leer = keine Unterüberschrift (nur eine Sportart). */
@Immutable
data class SportGroupEvents(val sport: String, val events: List<MatchedEvent>)

/** Ein Abschnitt der Übersicht („Jetzt live“, „Heute“ …), darin die Spiele nach Sportart. */
@Immutable
data class SportSection(val key: String, val title: String, val live: Boolean, val groups: List<SportGroupEvents>)

@Immutable
data class SportBoard(val live: List<MatchedEvent>, val upcoming: Map<UpcomingGroup, List<MatchedEvent>>) {
    val isEmpty: Boolean get() = live.isEmpty() && upcoming.values.all { it.isEmpty() }
    val size: Int get() = live.size + upcoming.values.sumOf { it.size }

    /** Mehr als eine Sportart dabei? Nur dann gibt es Unterüberschriften („Fußball“, „Motorsport“ …). */
    val mixed: Boolean
        get() = (live.asSequence() + upcoming.values.asSequence().flatten()).map { SportGroups.of(it.event.sport) }.distinct().take(2).count() > 1

    /**
     * Abschnitte in Anzeige-Reihenfolge: „Jetzt live“, „Heute“, „Morgen“, „Diese Woche“ – darin nach Sportart
     * (Reihenfolge wie in der Ligenauswahl), je Sportart nach Anstoß.
     */
    fun sections(): List<SportSection> {
        val bySport = mixed
        fun groups(list: List<MatchedEvent>): List<SportGroupEvents> =
            if (bySport) SportGroups.split(list) { it.event.sport }.map { (g, events) -> SportGroupEvents(g, events) } else listOf(SportGroupEvents("", list))
        return buildList {
            if (live.isNotEmpty()) add(SportSection("live", "Jetzt live", true, groups(live)))
            UpcomingGroup.entries.forEach { g ->
                val list = upcoming[g].orEmpty()
                if (list.isNotEmpty()) add(SportSection(g.name, g.label, false, groups(list)))
            }
        }
    }

    /** Fokus beim Öffnen: das oberste angezeigte Spiel (erst live, sonst das nächste anstehende). */
    val firstFocus: MatchedEvent?
        get() = sections().firstOrNull()?.groups?.firstOrNull()?.events?.firstOrNull()
}

/**
 * Kategorie A (live): Status „in“ ODER (Status nicht „post“ UND jetzt ∈ [Start − 15 min, Ende + 15 min]).
 * Kategorie B (demnächst): Start > jetzt UND Start ≤ jetzt + 7 Tage, nicht in A.
 * Status „post“ hat Vorrang: Ein beendetes Event ist nie live, auch nicht im Zeitfenster.
 * Ohne Android-Bezug (Calendar statt java.time, minSdk 23) – mit festen Zeitpunkten testbar.
 */
object SportEventFilter {
    const val PRE_ROLL_MS = 15 * 60_000L
    const val POST_ROLL_MS = 15 * 60_000L
    const val HORIZON_MS = 7 * 24 * 3_600_000L

    /** Ein laut Quelle laufendes Spiel gilt höchstens so lange nach dem Start als live (falls der Status hängt). */
    private const val MAX_LIVE_MS = 8 * 3_600_000L

    fun estimatedEndMs(e: MatchedEvent): Long = e.event.endMs ?: (e.event.startMs + defaultDurationMs(e.event.sport))

    /** Ohne Ende aus der Quelle: typische Dauer inkl. Pause und Nachspielzeit. */
    fun defaultDurationMs(sport: String): Long = when (sport) {
        "soccer" -> 120 * 60_000L
        "mma" -> 300 * 60_000L
        "racing" -> 150 * 60_000L
        else -> 180 * 60_000L
    }

    fun isLive(e: MatchedEvent, now: Long): Boolean = when (e.event.status) {
        EventStatus.POST -> false
        EventStatus.IN -> now - e.event.startMs < MAX_LIVE_MS
        else -> now >= e.event.startMs - PRE_ROLL_MS && now <= estimatedEndMs(e) + POST_ROLL_MS
    }

    fun isUpcoming(e: MatchedEvent, now: Long): Boolean = e.event.status != EventStatus.POST && e.event.startMs > now && e.event.startMs <= now + HORIZON_MS

    fun categorize(events: List<MatchedEvent>, now: Long, zone: TimeZone = TimeZone.getDefault()): SportBoard {
        val live = events.filter { isLive(it, now) }.sortedBy { it.event.startMs }
        val liveIds = live.mapTo(HashSet()) { it.event.id }
        val today = dayIndex(now, zone)
        val upcoming = events
            .filter { it.event.id !in liveIds && isUpcoming(it, now) }
            .sortedBy { it.event.startMs }
            .groupBy { e ->
                when (dayIndex(e.event.startMs, zone) - today) {
                    0L -> UpcomingGroup.TODAY
                    1L -> UpcomingGroup.TOMORROW
                    else -> UpcomingGroup.THIS_WEEK
                }
            }
        return SportBoard(live, upcoming)
    }

    /** Fortlaufende Tagesnummer in der Zeitzone (auch über Monats-/Jahreswechsel und Zeitumstellung korrekt). */
    internal fun dayIndex(ms: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        val y = c.get(Calendar.YEAR).toLong()
        val d = c.get(Calendar.DAY_OF_YEAR).toLong()
        // Tage bis zum 1.1. des Jahres (gregorianisch) + Tag im Jahr.
        val prev = y - 1
        return prev * 365 + prev / 4 - prev / 100 + prev / 400 + d
    }
}

/** „in 2 T 14 Std 23 Min“ · „in 4 Std 12 Min“ · unter 1 h sekundengenau „in 23:05“ · danach „jetzt“. */
fun formatCountdown(startMs: Long, nowMs: Long): String {
    val left = startMs - nowMs
    if (left <= 0) return "jetzt"
    val totalMin = left / 60_000
    val days = totalMin / (24 * 60)
    val hours = (totalMin / 60) % 24
    val minutes = totalMin % 60
    return when {
        days > 0 -> "in $days T $hours Std $minutes Min"
        hours > 0 -> "in $hours Std $minutes Min"
        else -> String.format(Locale.ROOT, "in %d:%02d", left / 60_000, (left / 1_000) % 60)
    }
}
