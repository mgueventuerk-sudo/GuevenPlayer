package com.mg.player.data.sport.guide

import com.mg.player.core.log.AppLog
import com.mg.player.data.db.dao.SportDao
import com.mg.player.data.db.entity.SportEventChannelEntity
import com.mg.player.data.search.SearchText
import com.mg.player.data.sport.toDomain
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.MatchSource
import com.mg.player.domain.model.SportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Kontrolle kurz vor Beginn, in zwei Schritten:
 * 1. 15 Minuten vor Anstoß die Event- und Sport-Gruppen beim Anbieter neu abfragen ([refreshGroups]): Ein Event-Sender,
 *    der erst jetzt umbenannt wurde („DAZN EVENT 3: Galatasaray – Fenerbahçe“), wird so noch vor dem Spiel gefunden.
 *    Hat sich etwas geändert, werden die Spiele der nächsten Minuten neu zugeordnet.
 * 2. Was meldet der Anbieter jetzt für die gefundenen Sender (Xtream get_short_epg)? Nennt die Sendung zur Anstoßzeit das
 *    Spiel, gilt der Sender als bestätigt (Score 105). Nennt sie deutlich ein anderes Spiel, rutscht er nach hinten – so
 *    wird ein verschobenes Spiel still korrigiert. Höchstens wenige Anfragen je Durchgang.
 * Beides geht nur bei Xtream-Zugängen (M3U-Listen haben weder Gruppen-Abfrage noch get_short_epg).
 */
class KickoffCheck(
    private val dao: SportDao,
    private val shortEpg: suspend (String) -> List<EpgProgram>,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Event-/Sport-Gruppen neu abfragen; liefert die Zahl geänderter Sender (null = gibt es nicht). */
    private val refreshGroups: (suspend () -> Int)? = null,
) {
    private val checkedAt = HashMap<String, Long>()

    /** Spiele, für die die Gruppen schon neu abgefragt wurden (je Spiel einmal). */
    private val groupsRefreshedFor = HashMap<String, Long>()

    /** Alle 2 Minuten prüfen, solange der Aufrufer läuft (App im Vordergrund). [rematch]: Spiele neu zuordnen. */
    suspend fun loop(rematch: suspend (List<SportEvent>) -> Unit = {}) {
        while (true) {
            try {
                run(rematch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("Sport", "Kontrolle vor Anstoß", e)
            }
            delay(INTERVAL_MS)
        }
    }

    /** Ein Durchgang; liefert die Zahl geänderter Zuordnungen. */
    suspend fun run(rematch: suspend (List<SportEvent>) -> Unit = {}): Int {
        val now = clock()
        checkedAt.entries.removeAll { now - it.value > 3_600_000L }
        groupsRefreshedFor.entries.removeAll { now - it.value > 3_600_000L }
        // Events aus dem Programm selbst („tv:…“) sind schon genau – nur Spiele aus den Spielplänen prüfen.
        val window = dao.eventsStartingBetween(now - 10 * MIN, now + 20 * MIN).map { it.toDomain() }
            .filter { !it.id.startsWith(GuideSeries.KEY_PREFIX) }
        refreshGroupsIfDue(window, now, rematch)
        val events = window.filter { now - (checkedAt[it.id] ?: 0L) >= RECHECK_MS }
        if (events.isEmpty()) return 0
        val matches = dao.matchesFor(events.map { it.id }).groupBy { it.eventId }
        val updates = ArrayList<SportEventChannelEntity>()
        var requests = 0
        for (event in events) {
            if (requests >= MAX_REQUESTS) break
            checkedAt[event.id] = now
            // Angezeigte, noch nicht bestätigte Sender. Event-Sender nicht: Ihr EPG stammt vom selben Anbieter wie ihr Name –
            // das bestätigt nichts (05.11.: „PRIME DE 016 [Alkmaar vs. Juventus]“ zeigte „Kein Event“).
            val candidates = matches[event.id].orEmpty().filter { it.score in MatchSource.DISPLAY_MIN until CONFIRMED }
                .sortedByDescending { it.score }.take(MAX_PER_EVENT)
            for (c in candidates) {
                if (requests++ >= MAX_REQUESTS) break
                val programs = shortEpg(c.channelId)
                when (verdict(event, programs)) {
                    Verdict.CONFIRMED -> if (c.score != CONFIRMED) updates += SportEventChannelEntity(c.eventId, c.channelId, c.playlistId, CONFIRMED)
                    Verdict.OTHER_MATCH -> if (c.score > OTHER) updates += SportEventChannelEntity(c.eventId, c.channelId, c.playlistId, OTHER)
                    Verdict.UNKNOWN -> Unit
                }
            }
        }
        if (updates.isNotEmpty()) {
            dao.upsertMatches(updates)
            AppLog.log("Sport", "Kontrolle vor Anstoß: ${updates.size} Sender angepasst")
        }
        return updates.size
    }

    /**
     * Schritt 1: Beginnt ein Spiel in den nächsten 15 Minuten (oder hat gerade begonnen) und wurden die Gruppen dafür noch
     * nicht abgefragt, jetzt abfragen. Mehrere Spiele zur selben Zeit teilen sich eine Abfrage (Sperre je Zugang).
     */
    private suspend fun refreshGroupsIfDue(window: List<SportEvent>, now: Long, rematch: suspend (List<SportEvent>) -> Unit) {
        val refresh = refreshGroups ?: return
        val due = window.filter { it.startMs <= now + GROUPS_BEFORE_MS && it.id !in groupsRefreshedFor }
        if (due.isEmpty()) return
        due.forEach { groupsRefreshedFor[it.id] = now }
        if (refresh() <= 0) return
        rematch(window)
        // Neu zugeordnet: die Sender gleich danach noch einmal beim Anbieter kontrollieren.
        window.forEach { checkedAt.remove(it.id) }
    }

    enum class Verdict { CONFIRMED, OTHER_MATCH, UNKNOWN }

    companion object {
        private const val MIN = 60_000L
        const val CONFIRMED = 105
        const val OTHER = 30
        private const val INTERVAL_MS = 2 * MIN

        /** So lange vor Anstoß werden die Event- und Sport-Gruppen neu abgefragt. */
        const val GROUPS_BEFORE_MS = 15 * MIN
        private const val RECHECK_MS = 10 * MIN
        private const val MAX_PER_EVENT = 3
        private const val MAX_REQUESTS = 9
        private val NO_EVENT = Regex("""\b(no|kein|keine)\s+events?\b""", RegexOption.IGNORE_CASE)
        private val PAIRING = Regex("""\S\s+(–|-|vs\.?|v)\s+\S""", RegexOption.IGNORE_CASE)

        /** Was läuft laut Anbieter zur Anstoßzeit auf dem Sender – passt es zum Spiel? */
        fun verdict(event: SportEvent, programs: List<EpgProgram>): Verdict {
            val kickoff = event.startMs
            val running = programs.filter { it.startMs <= kickoff + 20 * MIN && it.endMs > kickoff }
            if (running.isEmpty()) return Verdict.UNKNOWN
            val scorer = GuideMatching.scorer(event) ?: return Verdict.UNKNOWN
            var other = false
            for (p in running) {
                val text = listOfNotNull(p.title, p.description).joinToString(" ")
                val words = SearchText.words(text)
                // Platzhalter („No Event“, „Kein Event“): eindeutig nicht das Spiel.
                val placeholder = NO_EVENT.containsMatchIn(text)
                if (!placeholder && (scorer(words) ?: 0) >= GuideMatching.SERIES_ONLY && !GuideMatching.isNotLive(words)) return Verdict.CONFIRMED
                // Nur eine deutlich andere Paarung zählt als Gegenbeweis („Live Event“ o. Ä. sagt nichts).
                if (placeholder || PAIRING.containsMatchIn(p.title)) other = true
            }
            return if (other) Verdict.OTHER_MATCH else Verdict.UNKNOWN
        }
    }
}
