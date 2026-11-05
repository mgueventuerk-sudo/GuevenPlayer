package com.mg.player.data.sport

import com.mg.player.core.log.AppLog
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.core.network.HttpStatusException
import com.mg.player.data.db.dao.SportDao
import com.mg.player.data.db.entity.SportEventChannelEntity
import com.mg.player.data.sport.guide.KickoffCheck
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.model.ChannelKeyRow
import com.mg.player.domain.model.MatchedEvent
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.model.SportLeague
import com.mg.player.domain.repository.SettingsRepository
import com.mg.player.domain.sport.SportBoard
import com.mg.player.domain.sport.SportEventFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sport-Events: holt sie IMMER online (ESPN, Sportmonks mit eigenem Schlüssel), ordnet sie Kanälen zu und
 * speichert beides – der Room-Stand dient nur dazu, dass die Sektion beim Start sofort da ist.
 * Takt (nur solange die App sichtbar ist): sofort, dann bei laufenden Events alle 75 s (nur deren Ligen), sonst alle 30 min.
 * Bei Fehlern/429 wartet jede Liga mit wachsendem Abstand; parallele Aufrufe warten auf den laufenden Abruf.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportRepository(
    private val dao: SportDao,
    private val settings: SettingsRepository,
    private val sources: List<SportSource>,
    private val matcher: ChannelEventMatcher,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Ohne Zugang gibt es keine Sender zum Zuordnen – dann wird nichts abgerufen (z. B. während der Einrichtung). */
    private val hasPlaylists: suspend () -> Boolean = { true },
    /** Meldet abgeschlossene Aktualisierungen (Sender/EPG neu): Dann die Zuordnung sofort neu berechnen. */
    private val contentUpdated: Flow<Long> = flowOf(0L),
    /** Kontrolle kurz vor Anstoß beim Anbieter (läuft neben der Aktualisierung). */
    private val kickoff: KickoffCheck? = null,
) {
    private val _scores = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Spielstände aus dem letzten Abruf (Event-ID → „2:1 · 67'“) – nur im Speicher. */
    val scores: StateFlow<Map<String, String>> = _scores.asStateFlow()

    /** Laufendes Spiel auf diesem Sender samt Spielstand (null = keins); für die Einblendung im Player. */
    fun liveOn(channelId: String): Flow<MatchedEvent?> = board.combine(scores) { b, s ->
        b?.live?.firstOrNull { it.channelId == channelId }?.let { m -> m.copy(event = m.event.copy(score = s[m.event.id])) }
    }.distinctUntilChanged()

    /** null = noch nicht entschieden (der Tab bleibt bis dahin ausgeblendet, kein Flackern). */
    val board: StateFlow<SportBoard?> = tick(HOUR)
        .flatMapLatest { now -> dao.observeMatched(now - 12 * HOUR, now + SportEventFilter.HORIZON_MS + 2 * HOUR) }
        .combine(tick(MINUTE)) { rows, now -> SportEventFilter.categorize(rows.map { it.toDomain() }, now) }
        .flowOn(Dispatchers.Default)
        // Nur solange jemand hinschaut (Startseite): Die Takte laufen nicht im Hintergrund weiter.
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Bereich „Sport“ sichtbar, sobald mindestens einer Liga gefolgt wird – auch ohne Treffer im EPG. Dann zeigt er die
     * Spiele ohne Sender und den Weg zum Zuordnen. (Früher nur bei Treffern: Am Fire TV fehlte der Bereich, wenn das EPG
     * dort die Spiele nicht nannte – ohne dass man den Grund sehen konnte.)
     */
    val visible: StateFlow<Boolean> = settings.settings.map { followed(it).isNotEmpty() }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    /** Gibt es überhaupt EPG-Daten? (Ohne EPG findet die Zuordnung nur Sender, deren Name in den Spieldaten steht.) */
    suspend fun hasEpg(): Boolean = withContext(Dispatchers.IO) { dao.epgHorizonMs() != null }

    /**
     * Neuer Bzzoiro-Schlüssel: ESPN-Ligen, die Bzzoiro auch hat, dorthin umstellen (dort gibt es die TV-Sender) und die
     * Süper Lig dazunehmen. Liefert die Meldung für die Einstellungen; ein ungültiger Schlüssel ändert nichts.
     */
    suspend fun adoptBzzoiro(): String = withContext(Dispatchers.IO) {
        val source = sources.filterIsInstance<BzzoiroSource>().firstOrNull() ?: return@withContext "Bzzoiro nicht verfügbar"
        val catalog = try {
            source.leagues()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext "Bzzoiro: ${sportProblem(e)}"
        }
        if (catalog.isEmpty()) return@withContext "Bzzoiro: keine Ligen erhalten – Schlüssel prüfen"
        val adopted = BzzoiroAdoption.adopt(catalog, followed(settings.current()))
        settings.update { s ->
            // Von Hand gewählte Ersatzsender der ersetzten ESPN-Ligen mitnehmen.
            val manual = s.sportManualChannels.toMutableMap()
            adopted.moved.forEach { (from, to) -> manual.remove(from)?.let { manual.putIfAbsent(to, it) } }
            s.copy(sportLeagues = adopted.chosen, sportManualChannels = manual)
        }
        adopted.message
    }


    /**
     * Dieselbe Liga von ESPN und Bzzoiro gefolgt (vor 3.47 bei der Süper Lig möglich): auf Bzzoiro zusammenführen.
     * Fragt die Bzzoiro-Ligenliste nur ab, wenn es überhaupt eine ESPN- und eine Bzzoiro-Liga gibt.
     */
    private suspend fun mergeDuplicateLeagues() {
        val current = settings.current()
        val followed = followed(current)
        val source = sources.filterIsInstance<BzzoiroSource>().firstOrNull() ?: return
        if (followed.none { source.handles(it) } || followed.none { it in BzzoiroSource.DEFAULTS }) return
        val catalog = runCatchingCancellable { source.leagues() }.getOrNull().orEmpty()
        val (leagues, manual) = BzzoiroAdoption.mergeDuplicates(catalog, followed, current.sportManualChannels) ?: return
        AppLog.log("Sport", "Doppelte Ligen zusammengeführt: ${followed - leagues}")
        settings.update { it.copy(sportLeagues = leagues, sportManualChannels = manual) }
    }

    /** Kommende Spiele ohne gefundenen Sender (zum Zuordnen von Hand). */
    val unmatched: Flow<List<SportEvent>> = tick(HOUR)
        .flatMapLatest { now -> dao.observeUnmatched(now - 3 * HOUR, now + SportEventFilter.HORIZON_MS) }
        .map { rows -> rows.map { it.toDomain() } }

    /** Sender für alle Spiele der Liga festlegen ([channelId] = null: Zuordnung entfernen). Löst eine Neuzuordnung aus. */
    suspend fun assign(leagueKey: String, channelId: String?) {
        settings.update { s ->
            val map = if (channelId == null) s.sportManualChannels - leagueKey else s.sportManualChannels + (leagueKey to channelId)
            s.copy(sportManualChannels = map)
        }
    }

    /** Sender zur Auswahl beim Zuordnen. */
    suspend fun searchChannels(query: String): List<ChannelKeyRow> = withContext(Dispatchers.IO) {
        val text = query.trim().replace("%", "").replace("_", "")
        if (text.length < 2) emptyList() else dao.channelsNamed("%$text%")
    }

    suspend fun channelNames(ids: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyMap() else dao.channelsByIds(ids.toList()).associate { it.id to it.name }
    }

    /** Für die Einstellungen: (geladene Spiele bis in 7 Tagen, davon auf eigenen Sendern gefunden); null = noch offen. */
    val summary: StateFlow<Pair<Int, Int>?> = tick(HOUR)
        .flatMapLatest { now -> dao.observeEventCount(now - 12 * HOUR, now + SportEventFilter.HORIZON_MS) }
        .combine(board) { total, b -> b?.let { total to it.size } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val _problem = MutableStateFlow<String?>(null)

    /** Letztes Problem beim Abruf (für die Einstellungen), null = alles in Ordnung. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Ein Abruf zur Zeit (parallele Aufrufe warten auf den laufenden). */
    private val fetchMutex = Mutex()

    /**
     * Nur das Speichern und Zuordnen. Getrennt vom Abruf: Die Neuzuordnung vor Anstoß ([rematch]) muss nicht auf einen
     * langsamen Abruf (bis zu 15 Ligen nacheinander) warten – sonst käme sie womöglich erst nach Anstoß.
     */
    private val storeMutex = Mutex()
    private val backoffUntil = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()

    /** Letzter Fehler je Liga – bleibt stehen, solange die Liga wartet (Backoff). */
    private val problems = HashMap<String, String>()
    @Volatile
    private var lastFullMs = 0L

    /** Startzeit, mit der ein Event zuletzt zugeordnet wurde. */
    private val matchedStart = HashMap<String, Long>()
    private var matchedAtMs = 0L

    /** Abrufe seit dem Start (Messwert für das Fehlerprotokoll). */
    @Volatile var requestCount = 0L
        private set

    fun followed(s: AppSettings): Set<String> = s.sportLeagues ?: EspnSource.DEFAULT_KEYS

    /** Ligen aller Quellen (Sportmonks nur mit Schlüssel – je nach Tarif). */
    suspend fun catalog(): List<SportLeague> = withContext(Dispatchers.IO) {
        sources.flatMap { source ->
            try {
                source.leagues()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _problem.value = "${source.name}: Ligen nicht abrufbar (${sportProblem(e)})"
                emptyList()
            }
        }
    }

    /**
     * Hält die Daten aktuell, solange der Aufrufer läuft (AppRoot: nur im Vordergrund). Ändern sich die gefolgten Ligen,
     * der Schlüssel oder der aktive Zugang oder endet eine Aktualisierung von Sendern/EPG, wird sofort neu geladen und
     * neu zugeordnet.
     */
    suspend fun keepFresh() {
        var first = true
        // Auch beim Wechsel bzw. ersten Anlegen eines Zugangs (aktiver Zugang ändert sich): andere Sender, neu zuordnen.
        val config = settings.settings.map {
            listOf(followed(it), it.sportmonksToken, it.bzzoiroToken, it.activePlaylistId, it.sportManualChannels, it.onlineExtras, it.sportGuide)
        }.distinctUntilChanged()
        combine(config, contentUpdated.distinctUntilChanged()) { c, updated -> c to updated }.collectLatest {
            val force = !first
            first = false
            matcher.channelsChanged()
            coroutineScope {
                // Solange aktualisiert wird, auch die Übersicht aktuell halten (der Takt richtet sich nach laufenden Spielen).
                launch { board.collect { } }
                kickoff?.let { launch { it.loop(::rematch) } }
                refreshLoop(force)
            }
        }
    }

    private suspend fun refreshLoop(force: Boolean) {
        refresh(force = force)
        while (true) {
            val anyLive = board.value?.live?.isNotEmpty() == true
            delay(if (anyLive) LIVE_INTERVAL_MS else IDLE_INTERVAL_MS)
            // Laufende Spiele: nur deren Ligen neu holen; alle 30 min alles.
            val full = clock() - lastFullMs >= IDLE_INTERVAL_MS
            refresh(onlyLeagues = if (full) null else board.value?.live.orEmpty().mapTo(HashSet()) { leagueOfEvent(it.event.id) })
        }
    }

    /** [force]: auch innerhalb des Mindestabstands (z. B. nach dem Ändern der Ligen). */
    suspend fun refresh(force: Boolean = false, onlyLeagues: Set<String>? = null) = withContext(Dispatchers.IO) {
        fetchMutex.withLock {
            val now = clock()
            // „Online-Zusatzinfos aus“: keine einzige Anfrage an Sportdienste.
            if (!hasPlaylists() || !settings.current().onlineExtras) return@withLock
            if (onlyLeagues == null && !force && now - lastFullMs < MIN_INTERVAL_MS) return@withLock
            if (onlyLeagues == null) mergeDuplicateLeagues()
            val current = settings.current()
            val followed = followed(current)
            val wanted = onlyLeagues?.intersect(followed) ?: followed
            if (wanted.isEmpty() && onlyLeagues != null) return@withLock
            val fetched = HashMap<String, List<SportEvent>>()
            for (league in wanted) {
                val source = sources.firstOrNull { it.handles(league) } ?: continue
                if (now < (backoffUntil[league] ?: 0L)) continue
                try {
                    requestCount++
                    fetched[league] = source.fetch(league, now - 12 * HOUR, now + SportEventFilter.HORIZON_MS)
                    failures.remove(league)
                    backoffUntil.remove(league)
                    problems.remove(league)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val n = (failures[league] ?: 0) + 1
                    failures[league] = n
                    // Exponentiell mit ±20 % Zufall: Nach einem Netzausfall fragen nicht alle Ligen (und alle Geräte) im Gleichtakt.
                    val wait = (e as? HttpStatusException)?.retryAfterSec?.times(1000) ?: jittered(30_000L shl n.coerceAtMost(6))
                    backoffUntil[league] = now + wait
                    problems[league] = "${source.name}: ${sportProblem(e)}"
                    AppLog.log("Sport", "$league: ${e.javaClass.simpleName}, nächster Versuch in ${wait / 1000} s")
                }
            }
            if (onlyLeagues == null) lastFullMs = now
            try {
                storeMutex.withLock { store(fetched, followed, current, now, full = onlyLeagues == null, rematchAll = force) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Speichern darf die Aktualisierungsschleife nie beenden (z. B. Datenbank gerade geschlossen).
                AppLog.error("Sport", "Events nicht gespeichert", e)
            }
            problems.keys.retainAll(followed)
            _problem.value = problems.values.distinct().joinToString(" · ").ifEmpty { null }
        }
    }

    private suspend fun store(
        fetched: Map<String, List<SportEvent>>,
        followed: Set<String>,
        current: AppSettings,
        now: Long,
        full: Boolean,
        rematchAll: Boolean,
    ) {
        val events = fetched.values.flatten().distinctBy { it.id }
        val existing = dao.allEventIds()
        // Nicht mehr gefolgte Ligen und Events, die eine erfolgreich abgefragte Liga nicht mehr liefert.
        val fetchedIds = events.mapTo(HashSet()) { it.id }
        val remove = existing.filter { id ->
            val league = leagueOfEvent(id)
            league !in followed || (league in fetched && id !in fetchedIds)
        }
        // Zuordnen ist teurer als der Abruf: nur neue Events bzw. geänderte Anstoßzeiten, bei vollem Abruf alle 30 min alles.
        val stale = rematchAll || (full && now - matchedAtMs >= IDLE_INTERVAL_MS)
        val toMatch = if (stale) events else events.filter { matchedStart[it.id] != it.startMs }
        val matches: List<SportEventChannelEntity> = matcher.match(toMatch, current.activePlaylistId, current.sportManualChannels, all = events)
        dao.replace(
            events = matcher.withBroadcasters(events).map { it.toEntity(now) },
            matchedIds = toMatch.map { it.id },
            matches = matches,
            removeIds = remove,
            nowMs = now,
        )
        val gone = remove.toHashSet()
        _scores.update { old -> old.filterKeys { it !in gone && it !in fetchedIds } + events.mapNotNull { e -> e.score?.let { e.id to it } } }
        toMatch.forEach { matchedStart[it.id] = it.startMs }
        remove.forEach { matchedStart.remove(it) }
        if (stale) matchedAtMs = now
        if (fetched.isNotEmpty()) {
            val matched = matches.map { it.eventId }.distinct().size
            AppLog.log("Sport", "${events.size} Events aus ${fetched.size} Ligen; ${toMatch.size} neu zugeordnet, davon $matched mit Sender")
        }
    }

    /**
     * Spiele neu zuordnen, ohne sie neu abzurufen – nach der Kontrolle der Event-Gruppen vor Anstoß (umbenannte oder neue
     * Event-Sender). Die Senderliste wird dafür frisch gelesen.
     */
    suspend fun rematch(events: List<SportEvent>) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        storeMutex.withLock {
            matcher.channelsChanged()
            val current = settings.current()
            val matches = matcher.match(events, current.activePlaylistId, current.sportManualChannels)
            dao.replace(events = emptyList(), matchedIds = events.map { it.id }, matches = matches, removeIds = emptyList(), nowMs = clock())
            AppLog.log("Sport", "Neu zugeordnet vor Anstoß: ${matches.map { it.eventId }.distinct().size} von ${events.size} Spielen mit Sender")
        }
    }


    private fun tick(periodMs: Long): Flow<Long> = flow {
        while (true) {
            val t = clock()
            emit(t)
            delay((periodMs - t % periodMs).coerceAtLeast(1_000))
        }
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L
        const val MIN_INTERVAL_MS = 60_000L

        /** Wartezeit ±20 % (Backoff mit Jitter). */
        internal fun jittered(ms: Long, random: kotlin.random.Random = kotlin.random.Random.Default): Long =
            (ms * random.nextDouble(JITTER_MIN, JITTER_MAX)).toLong()
        private const val JITTER_MIN = 0.8
        private const val JITTER_MAX = 1.2
        const val LIVE_INTERVAL_MS = 75_000L
        const val IDLE_INTERVAL_MS = 30 * 60_000L
    }
}
