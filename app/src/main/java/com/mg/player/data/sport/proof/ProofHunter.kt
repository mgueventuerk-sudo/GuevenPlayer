package com.mg.player.data.sport.proof

import com.mg.player.core.log.AppLog
import com.mg.player.data.db.dao.EventMatchRow
import com.mg.player.data.db.dao.SportDao
import com.mg.player.data.sport.guide.GuideCatalog
import com.mg.player.data.sport.guide.SportChannelFinder
import com.mg.player.data.sport.rights.SportRights
import com.mg.player.data.sport.toDomain
import com.mg.player.domain.model.ChannelKeyRow
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.SportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Sucht für laufende Spiele den Beweis im Bild (Plan „Spiel-Finder“ C/D, Regel „Freie Playlist“): von 2 Minuten vor bis
 * 2 Stunden nach Anpfiff, je Runde höchstens [MAX_PROBES] Blicke. Reihenfolge: Sender mit Rechten (Rechte-Tabelle), dann
 * nach Sicherheit der Quelle; Event-Kanäle sind dabei – sie zählen nur mit Beweis.
 *
 * Verbindungen: Jede Playlist hat oft nur eine. Geprüft wird nur über eine Playlist, die laut Anbieter frei ist und die
 * dieses Gerät in den letzten Minuten nicht genutzt hat; kurz davor wird beim Anbieter noch einmal nachgefragt.
 */
class ProofHunter(
    private val dao: SportDao,
    private val proofs: ProofStore,
    private val look: suspend (channelId: String) -> ProofLook?,
    private val connections: () -> Map<Long, ConnectionStatus>,
    private val recheck: suspend (playlistId: Long) -> ConnectionStatus?,
    private val usedHere: (playlistId: Long) -> Boolean,
    private val scores: () -> Map<String, String>,
    private val countries: suspend () -> Set<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun loop(rematch: suspend (List<SportEvent>) -> Unit) {
        while (true) {
            try {
                run(rematch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.log("Sport", "Bildbeweis: ${e.javaClass.simpleName}")
            }
            delay(INTERVAL_MS)
        }
    }

    /** Eine Runde; liefert die Zahl der Spiele mit neuem Ergebnis. */
    suspend fun run(rematch: suspend (List<SportEvent>) -> Unit): Int {
        val now = clock()
        val events = dao.eventsStartingBetween(now - LIVE_WINDOW_MS, now + BEFORE_MS).map { it.toDomain() }
            .filter { it.startMs <= now + BEFORE_MS && !proofs.hasProof(it.id) }
        if (events.isEmpty()) return 0
        val matches = dao.matchesFor(events.map { it.id }).groupBy { it.eventId }
        val channels = dao.channelsByIds(matches.values.flatten().map { it.channelId }.distinct()).associateBy { it.id }
        val wanted = countries()
        val changed = ArrayList<SportEvent>()
        var budget = MAX_PROBES
        for (event in events.sortedBy { it.startMs }) {
            if (budget <= 0) break
            val (used, newResult) = hunt(event, order(event, matches[event.id].orEmpty(), channels, wanted), budget)
            budget -= used
            if (newResult) changed += event
        }
        if (changed.isNotEmpty()) rematch(changed.distinct())
        return changed.distinct().size
    }

    /** Kandidaten eines Spiels der Reihe nach prüfen, bis bewiesen oder [budget] verbraucht. Liefert (Blicke, neues Ergebnis). */
    private suspend fun hunt(event: SportEvent, candidates: List<Pair<EventMatchRow, ChannelKeyRow>>, budget: Int): Pair<Int, Boolean> {
        var used = 0
        var changed = false
        var proven = false
        val iterator = candidates.iterator()
        while (used < budget && !proven && iterator.hasNext()) {
            val (row, channel) = iterator.next()
            if (isFree(row.playlistId)) {
                used++
                val verdict = check(event, row, channel)
                if (verdict != null && verdict != ProofEvaluator.Verdict.UNKNOWN) changed = true
                proven = verdict == ProofEvaluator.Verdict.PROVEN
            }
        }
        return used to changed
    }

    /** Kandidaten ohne Ergebnis: Rechte-Familien zuerst, dann nach Score. */
    private fun order(
        event: SportEvent,
        rows: List<EventMatchRow>,
        channels: Map<String, ChannelKeyRow>,
        countries: Set<String>,
    ): List<Pair<EventMatchRow, ChannelKeyRow>> {
        val families = SportRights.familiesFor(event, countries)
        return rows.mapNotNull { row -> channels[row.channelId]?.let { row to it } }
            .filter { (_, channel) -> proofs.verdictFor(event.id, keysOf(channel)) == null }
            .sortedWith(compareBy({ if (SportRights.familyOf(it.second.name) in families) 0 else 1 }, { -it.first.score }))
            .take(MAX_PER_EVENT)
    }

    private suspend fun check(event: SportEvent, row: EventMatchRow, channel: ChannelKeyRow): ProofEvaluator.Verdict? {
        val seen = look(channel.id) ?: return null
        val verdict = ProofEvaluator.evaluate(seen.texts, gameOf(event), seen.staticPicture)
        // Event-Kanäle gibt es nur bei einem Anbieter – ihr Ergebnis gilt nur für diesen Kanal (Regel „Freie Playlist“ 3).
        val eventChannel = row.score <= SportChannelFinder.EVENT_CHANNEL + 1
        val key = if (eventChannel) channel.id else GuideCatalog.nameKey(channel.name)
        proofs.record(event.id, key, channel.name, verdict, BY_PICTURE)
        if (verdict == ProofEvaluator.Verdict.PROVEN && !eventChannel) proofs.record(event.id, channel.id, channel.name, verdict, BY_PICTURE)
        AppLog.log("Sport", "Bildbeweis ${event.title} · ${channel.name}: $verdict (${seen.texts.size} Textzeilen)")
        return verdict
    }

    /** Frei: Anbieter meldet freie Verbindung, dieses Gerät nutzt die Playlist nicht, und kurz vorher noch einmal gefragt. */
    private suspend fun isFree(playlistId: Long): Boolean {
        if (usedHere(playlistId) || !hasRoom(connections()[playlistId])) return false
        return hasRoom(recheck(playlistId))
    }

    private fun keysOf(channel: ChannelKeyRow) = listOf(channel.id, GuideCatalog.nameKey(channel.name))

    private fun gameOf(event: SportEvent): ProofEvaluator.Game {
        val score = scores()[event.id] ?: event.score
        return ProofEvaluator.Game(event.homeTeam, event.awayTeam, goalsOf(score), minuteOf(score))
    }

    companion object {
        const val BY_PICTURE = "Bild"
        const val BY_WATCHING = "Zuschauen"
        const val BY_CHOICE = "deine Wahl"
        const val BY_WRONG = "„Falscher Sender“"
        private const val MIN = 60_000L
        private const val INTERVAL_MS = MIN
        private const val BEFORE_MS = 2 * MIN
        private const val LIVE_WINDOW_MS = 120 * MIN
        private const val MAX_PROBES = 3
        private const val MAX_PER_EVENT = 6
        private val GOALS = Regex("""(\d{1,2})\s*:\s*(\d{1,2})""")
        private val MINUTE = Regex("""(\d{1,3})'""")

        /** Freie Verbindung nur mit bekannter Grenze (unbekannt/unbegrenzt: nicht riskieren). */
        internal fun hasRoom(status: ConnectionStatus?): Boolean = status != null && status.max > 0 && status.active < status.max

        internal fun goalsOf(score: String?): Pair<Int, Int>? =
            score?.let(GOALS::find)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        internal fun minuteOf(score: String?): Int? = score?.let(MINUTE::find)?.groupValues?.get(1)?.toIntOrNull()
    }
}
