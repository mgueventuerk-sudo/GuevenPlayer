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
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

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
    private val access: Access,
    private val inputs: Inputs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Verbindungen der Playlists (Regel „Freie Playlist“). */
    class Access(
        val connections: () -> Map<Long, ConnectionStatus>,
        val recheck: suspend (playlistId: Long) -> ConnectionStatus?,
        val usedHere: (playlistId: Long) -> Boolean,
        /** Kennung des Zugangs einer Playlist (gleich auf allen Geräten), für „Ich brauche diese Playlist“ (Plan O). */
        val accountOf: suspend (playlistId: Long) -> String? = { null },
    )

    /** Was über die Spiele bekannt ist. */
    class Inputs(
        val scores: () -> Map<String, String>,
        val countries: suspend () -> Set<String>,
        /** Weitere Sender für die Suche (Sport-Sender der Familien mit Rechten, Plan D), nach den Kandidaten geprüft. */
        val extra: suspend (SportEvent) -> List<ChannelKeyRow> = { emptyList() },
    )

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

    /** Zugänge, die ein anderes Gerät gerade zum Schauen braucht (Kennung → bis wann). */
    private val released = ConcurrentHashMap<String, Long>()

    /** Regel „Freie Playlist“ 4: Ein anderes Gerät braucht diesen Zugang – 2 Minuten nicht darüber prüfen. */
    fun release(account: String) {
        val now = clock()
        // Abgelaufene Einträge gleich mit weg: Sonst wächst die Tabelle mit jeder gemeldeten Kennung weiter.
        released.entries.removeIf { it.value <= now }
        released[account] = now + RELEASE_MS
    }

    /** Eine Runde zur Zeit (App im Vordergrund und Hintergrund-Dienst teilen sich den Sucher). */
    private val runLock = Mutex()

    /** Eine Runde; liefert die Zahl der Spiele mit neuem Ergebnis. */
    suspend fun run(rematch: suspend (List<SportEvent>) -> Unit): Int {
        if (!runLock.tryLock()) return 0
        return try {
            round(rematch)
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun round(rematch: suspend (List<SportEvent>) -> Unit): Int {
        val now = clock()
        val events = dao.eventsStartingBetween(now - LIVE_WINDOW_MS, now + BEFORE_MS).map { it.toDomain() }
            .filter { it.startMs <= now + BEFORE_MS && !proofs.hasProof(it.id) }
        if (events.isEmpty()) return 0
        val matches = dao.matchesFor(events.map { it.id }).groupBy { it.eventId }
        val channels = dao.channelsByIds(matches.values.flatten().map { it.channelId }.distinct()).associateBy { it.id }
        val wanted = inputs.countries()
        val changed = ArrayList<SportEvent>()
        var budget = MAX_PROBES
        for (event in events.sortedBy { it.startMs }) {
            if (budget <= 0) break
            val (used, newResult) = hunt(event, order(event, matches[event.id].orEmpty(), channels, wanted) + sweep(event, matches[event.id].orEmpty()), budget)
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

    /** Suche über die Sport-Gruppen (Plan D): Sender ohne bisherige Quelle, ohne Ergebnis, nach den Kandidaten. */
    private suspend fun sweep(event: SportEvent, known: List<EventMatchRow>): List<Pair<EventMatchRow, ChannelKeyRow>> {
        val knownIds = known.mapTo(HashSet()) { it.channelId }
        return inputs.extra(event).filter { it.id !in knownIds && proofs.verdictFor(event.id, keysOf(it)) == null }
            .map { EventMatchRow(event.id, it.id, it.playlistId, 0) to it }
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
        val read = ProofEvaluator.evaluate(seen.texts, gameOf(event), seen.staticPicture)
        // Logo-Prüfung (Plan E) erst nach Anpfiff – davor laufen oft Werbung und Hinweise auf andere Sender – und nur für
        // diesen einen Kanal: Ein Logo-Fehlschluss soll nicht denselben Sender in allen Playlists verstecken.
        val wrongLogo = read == ProofEvaluator.Verdict.UNKNOWN && clock() >= event.startMs &&
            ProofEvaluator.foreignLogo(seen.texts, channel.name, SportRights::familyOf)
        // Vor Anpfiff zählt nur ein Beweis: Platzhalter („Kein Event“), Standbild oder das vorige Spiel mit Stand im Bild
        // sind dann normal – gespeichert würden sie den Sender für das ganze Spiel verstecken (nie neu geprüft).
        val beforeKickoff = clock() < event.startMs
        val verdict = when {
            wrongLogo -> ProofEvaluator.Verdict.OTHER
            beforeKickoff && read != ProofEvaluator.Verdict.PROVEN -> ProofEvaluator.Verdict.UNKNOWN
            else -> read
        }
        // Event-Kanäle gibt es nur bei einem Anbieter – ihr Ergebnis gilt nur für diesen Kanal (Regel „Freie Playlist“ 3).
        // Score 0 = gefunden über die Suche in den Sport-Gruppen (Plan D): ein normaler Sport-Sender.
        val eventChannel = row.score in 1..SportChannelFinder.EVENT_CHANNEL + 1
        val key = if (eventChannel || wrongLogo) channel.id else GuideCatalog.nameKey(channel.name)
        proofs.record(event.id, key, channel.name, verdict, BY_PICTURE)
        if (verdict == ProofEvaluator.Verdict.PROVEN && !eventChannel) proofs.record(event.id, channel.id, channel.name, verdict, BY_PICTURE)
        AppLog.log("Sport", "Bildbeweis ${event.title} · ${channel.name}: $verdict${if (wrongLogo) " (fremdes Logo)" else ""} (${seen.texts.size} Textzeilen)")
        return verdict
    }

    /** Frei: Anbieter meldet freie Verbindung, dieses Gerät nutzt die Playlist nicht, und kurz vorher noch einmal gefragt. */
    private suspend fun isFree(playlistId: Long): Boolean {
        if (access.usedHere(playlistId) || !hasRoom(access.connections()[playlistId])) return false
        val account = access.accountOf(playlistId)
        if (account != null && (released[account] ?: 0L) > clock()) return false
        return hasRoom(access.recheck(playlistId))
    }

    private fun keysOf(channel: ChannelKeyRow) = listOf(channel.id, GuideCatalog.nameKey(channel.name))

    private fun gameOf(event: SportEvent): ProofEvaluator.Game {
        val score = inputs.scores()[event.id] ?: event.score
        return ProofEvaluator.Game(event.homeTeam, event.awayTeam, goalsOf(score), minuteOf(score))
    }

    companion object {
        const val BY_PICTURE = "Bild"
        const val BY_WATCHING = "Zuschauen"
        const val BY_CHOICE = "deine Wahl"
        const val BY_WRONG = "„Falscher Sender“"
        const val BY_HUB = "Prüfgerät"
        private const val MIN = 60_000L
        private const val INTERVAL_MS = MIN
        private const val BEFORE_MS = 2 * MIN
        private const val LIVE_WINDOW_MS = 120 * MIN
        private const val MAX_PROBES = 3
        private const val RELEASE_MS = 2 * MIN
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
