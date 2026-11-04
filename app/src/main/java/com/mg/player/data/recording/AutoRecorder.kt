package com.mg.player.data.recording

import android.content.Context
import androidx.core.content.edit
import com.mg.player.core.log.AppLog
import com.mg.player.data.db.AppDatabase
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Recording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * „Automatisch aufnehmen“: Stichwörter (Mannschaft, Sendung). Kommende Sendungen der nächsten 24 Stunden, deren Titel
 * ein Stichwort enthält, werden eingeplant – nach jedem EPG-Abruf und alle 6 Stunden, solange die App läuft.
 * Jede Sendung wird nur einmal eingeplant: Löscht man sie, kommt sie nicht wieder.
 */
class AutoRecorder(
    context: Context,
    private val db: AppDatabase,
    private val manager: RecordingManager,
    private val activePlaylistId: suspend () -> Long?,
    /** EPG-Zeitverschiebung je Zugang in ms. */
    private val epgShift: suspend (playlistId: Long) -> Long,
    private val channelLookup: suspend (String) -> Channel?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _rules = MutableStateFlow(prefs.getString(KEY_RULES, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty())
    val rules: StateFlow<List<String>> = _rules.asStateFlow()
    private val mutex = Mutex()

    /** Liefert eine Meldung, wenn das Stichwort nicht übernommen wurde. */
    fun add(keyword: String): String? {
        val k = AutoRecordPlanner.normalize(keyword) ?: return "Bitte mindestens ${AutoRecordPlanner.MIN_CHARS} Zeichen eingeben"
        if (_rules.value.any { it.equals(k, ignoreCase = true) }) return "„$k“ ist schon dabei"
        if (_rules.value.size >= AutoRecordPlanner.MAX_RULES) return "Höchstens ${AutoRecordPlanner.MAX_RULES} Stichwörter"
        save(_rules.value + k)
        return null
    }

    fun remove(keyword: String) = save(_rules.value - keyword)

    private fun save(list: List<String>) {
        _rules.value = list
        prefs.edit { putString(KEY_RULES, list.joinToString("\n")) }
    }

    /** Hält die Planung aktuell, solange der Aufrufer läuft: bei neuen Stichwörtern, nach EPG-Abrufen und alle 6 h. */
    suspend fun keepPlanned(epgUpdated: Flow<Long>) {
        combine(rules, epgUpdated.distinctUntilChanged()) { r, _ -> r }.collectLatest { list ->
            if (list.isEmpty()) return@collectLatest
            while (true) {
                runCatching { plan() }.onFailure { if (it is CancellationException) throw it else AppLog.error("Aufnahme", "Automatik", it) }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Plant passende Sendungen ein; liefert die Zahl neu eingeplanter Aufnahmen. */
    suspend fun plan(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val keywords = _rules.value
            val playlistId = activePlaylistId() ?: return@withLock 0
            if (keywords.isEmpty()) return@withLock 0
            val now = clock()
            val shift = epgShift(playlistId)
            val dao = db.epgDao()
            val candidates = keywords.flatMap { k ->
                dao.upcomingMatching(playlistId, AutoRecordPlanner.pattern(k), now - shift, now - shift + HORIZON_MS)
                    .map { AutoRecordPlanner.Candidate(it.channelId, it.title, it.description, it.startMs + shift, it.endMs + shift) }
            }
            manager.awaitReady()
            val busy = manager.recordings.value.filter { it.isScheduled || it.isActive }.map { it.startedAtMs..it.plannedEndMs }
            val done = plannedKeys(now)
            val picked = AutoRecordPlanner.pick(candidates, busy, done, now)
            var count = 0
            for (c in picked) {
                val channel = channelLookup(c.channelId) ?: continue
                val problem = manager.schedule(channel, EpgProgram(c.title, c.description, c.startMs, c.endMs))
                done[Recording.programKey(c.channelId, c.startMs)] = c.endMs
                if (problem == null) count++
            }
            prefs.edit { putString(KEY_PLANNED, done.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
            if (count > 0) AppLog.log("Aufnahme", "Automatisch eingeplant: $count")
            count
        }
    }

    /** Schon einmal eingeplante Sendungen (Schlüssel → Ende); Vergangenes fällt heraus. */
    private fun plannedKeys(now: Long): MutableMap<String, Long> = prefs.getString(KEY_PLANNED, null).orEmpty().split('\n')
        .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { (k, end) -> end.toLongOrNull()?.let { k to it } } }
        .filter { it.second > now }
        .toMap(HashMap())

    private companion object {
        const val FILE = "auto_record"
        const val KEY_RULES = "rules"
        const val KEY_PLANNED = "planned"
        const val HORIZON_MS = 24 * 3_600_000L
        const val INTERVAL_MS = 6 * 3_600_000L
    }
}

/** Reine Auswahl-Logik (testbar ohne Android). */
object AutoRecordPlanner {
    const val MIN_CHARS = 3
    const val MAX_RULES = 20
    private const val MAX_PER_RUN = 10
    private const val PRE_MS = 60_000L
    private const val POST_MS = 120_000L

    data class Candidate(val channelId: String, val title: String, val description: String?, val startMs: Long, val endMs: Long)

    fun normalize(keyword: String): String? = keyword.trim().replace(Regex("\\s+"), " ").takeIf { it.length in MIN_CHARS..60 }

    /** LIKE-Muster; Platzhalterzeichen aus der Eingabe werden entfernt. */
    fun pattern(keyword: String): String = "%" + keyword.replace("%", "").replace("_", "") + "%"

    /**
     * Je Sendung (Titel + Beginn) einmal, nur Zukünftiges, nichts schon Eingeplantes ([done]) und nichts, was sich mit
     * einer anderen Aufnahme überschneidet – viele Anbieter erlauben nur eine Verbindung gleichzeitig.
     */
    fun pick(candidates: List<Candidate>, busy: List<LongRange>, done: Map<String, Long>, now: Long): List<Candidate> {
        val taken = busy.toMutableList()
        val result = ArrayList<Candidate>()
        candidates
            .filter { it.startMs - PRE_MS > now && it.endMs > it.startMs }
            .distinctBy { it.title.lowercase(Locale.ROOT) to it.startMs }
            .sortedBy { it.startMs }
            .forEach { c ->
                if (result.size >= MAX_PER_RUN) return@forEach
                if (Recording.programKey(c.channelId, c.startMs) in done) return@forEach
                val range = (c.startMs - PRE_MS)..(c.endMs + POST_MS)
                if (taken.any { it.first < range.last && range.first < it.last }) return@forEach
                taken += range
                result += c
            }
        return result
    }
}
