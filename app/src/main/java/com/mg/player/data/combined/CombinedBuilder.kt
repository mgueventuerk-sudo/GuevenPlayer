package com.mg.player.data.combined

import androidx.room.withTransaction
import com.mg.player.core.log.AppLog
import com.mg.player.core.util.CountryDetector
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.dao.CategorySourceRow
import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.db.dao.VodSourceRow
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.CombinedLinkEntity
import com.mg.player.data.repository.ChannelGroupIndex
import com.mg.player.data.search.SearchIndexer
import com.mg.player.domain.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Baut „Alle Zugänge“ neu auf – nach jeder Aktualisierung, jedem gelöschten Zugang und jeder geänderten Einstellung
 * (im Hintergrund, mehrere Anlässe kurz hintereinander ergeben einen Aufbau). Wie beim Import: Die neuen Zeilen
 * bekommen eine neue Generation, erst danach verschwinden die alten – die Listen werden dabei nie leer.
 * Ab zwei einbezogenen Zugängen; sonst wird die Kombination entfernt.
 */
class CombinedBuilder(
    private val db: AppDatabase,
    private val settings: CombinedSettings,
    private val scope: CoroutineScope,
    private val groupIndex: ChannelGroupIndex,
    private val searchIndex: SearchIndexer,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Läuft gerade eine Aktualisierung? Dann erst danach aufbauen (sonst bei „Alle Zugänge aktualisieren“ mehrfach). */
    private val syncBusy: () -> Boolean = { false },
    /** Senderkatalog für die automatische Zuordnung (wird je Aufbau geladen und danach wieder freigegeben). */
    private val catalog: () -> ChannelCatalog = { ChannelCatalog.EMPTY },
    /** Vor einem Aufbau im Hintergrund (z. B. Senderkatalog mit dem Internet abgleichen, wenn fällig). */
    private val beforeRebuild: suspend () -> Unit = {},
) {
    private val mutex = Mutex()

    private val _stats = MutableStateFlow<CombinedStats?>(null)

    /** Ergebnis des letzten Aufbaus (für die Einstellungen „Alle Zugänge“). */
    val stats: StateFlow<CombinedStats?> = _stats.asStateFlow()
    private var pending: Job? = null

    /** Gibt es die Kombination gerade (mindestens zwei Zugänge einbezogen)? */
    @Volatile var available: Boolean = false
        private set

    fun rebuildLater(reason: String, delayMs: Long = DEBOUNCE_MS) {
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch(Dispatchers.IO) {
                delay(delayMs)
                while (syncBusy()) delay(SYNC_POLL_MS)
                try {
                    beforeRebuild()
                    rebuild(reason)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(TAG, "Aufbau fehlgeschlagen", e)
                } catch (e: OutOfMemoryError) {
                    // Sehr große Listen auf schwachen Geräten: lieber ohne neue Kombination als ein Absturz.
                    AppLog.error(TAG, "Zu wenig Arbeitsspeicher für den Aufbau – Kombination bleibt beim letzten Stand", e)
                }
            }
        }
    }

    /** Noch nie aufgebaut (erster Start nach dem Update, gerade gewählt): jetzt aufbauen. */
    fun ensureLater() {
        scope.launch(Dispatchers.IO) {
            val built = runCatching { db.combinedDao().linkCount() > 0 }.getOrDefault(false)
            when {
                !built -> rebuildLater("Erster Aufbau", delayMs = 0)
                settings.builtWithMatcher != MATCHER_VERSION -> {
                    available = true
                    rebuildLater("Neue Zuordnungsregeln", delayMs = 0)
                }
                else -> available = true
            }
        }
    }

    /** true = Kombination steht (≥ 2 Zugänge). */
    suspend fun rebuild(reason: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val started = clock()
            val config = settings.current()
            val playlists = db.playlistDao().all()
            val names = playlists.associate { it.id to it.name }
            val ids = config.included(playlists.map { it.id })
            if (ids.size < 2) {
                if (available || db.combinedDao().linkCount() > 0) clear()
                available = false
                return@withLock false
            }
            val generation = started
            val rank = ids.withIndex().associate { it.value to it.index }
            val sources = ids.flatMap { db.combinedDao().categorySources(it) }
            val categories = CombinedCategories.build(sources, config.byAccount, rank, names, generation)
            db.categoryDao().insertAll(categories.entities)
            val countryOfCategory = sources.associate { (it.playlistId to it.id) to CountryDetector.detect(it.name) }
            val language = { r: VodSourceRow -> CombinedVod.language(r.name, countryOfCategory[r.playlistId to r.categoryId]) }
            if (settings.takeLegacyRuleReset()) {
                val removed = db.combinedDao().deleteAllRules()
                if (removed > 0) AppLog.log(TAG, "$removed Korrekturen aus der früheren Prüfliste verworfen – die Zuordnung ist jetzt automatisch")
            }
            val rules = loadRules()
            val matchStats = MatchStats()
            val ctx = BuildContext(db, ids, rank, categories, generation)
            val channels = buildChannels(ctx, rules[ContentType.LIVE.name] ?: MatchRules(), matchStats)
            val movies = CombinedVod.build(ctx, rules[ContentType.MOVIE.name] ?: MatchRules(), false, language)
            val series = CombinedVod.build(ctx, rules[ContentType.SERIES.name] ?: MatchRules(), true, language)
            db.categoryDao().insertAll(categories.fallbackEntities())
            db.categoryDao().deleteStale(Combined.ID, generation)
            CombinedVod.dropOrphanEpisodes(db)
            listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES).forEach { db.combinedDao().inheritFavorites(it.name) }
            CombinedVod.inheritProgress(db)
            groupIndex.rebuild(Combined.ID)
            searchIndex.rebuildInBackground(scope, Combined.ID)
            available = true
            settings.builtWithMatcher = MATCHER_VERSION
            _stats.value = CombinedStats(
                ids.size, channels, movies, series, matchStats.identified, matchStats.refused, clock(),
                matchStats.catalogSize, matchStats.sources,
            )
            AppLog.log(
                TAG,
                "Aufgebaut ($reason): ${ids.size} Zugänge → $channels Sender, $movies Filme, $series Serien, " +
                    "${categories.entities.size} Gruppen; ${matchStats.identified} von ${matchStats.sources} Quellen über den Senderkatalog erkannt, " +
                    "${matchStats.refused} Verwechslungen verhindert, ${matchStats.merged} Sender aus mehreren Zugängen " +
                    "(${matchStats.confirmed} durch das laufende Programm bestätigt) (${clock() - started} ms)",
            )
            true
        }
    }

    private suspend fun loadRules(): Map<String, MatchRules> = db.combinedDao().rules().groupBy { it.type }.mapValues { (_, list) ->
        MatchRules(list.filter { it.merge }.map { it.keyA to it.keyB }, list.filterNot { it.merge }.map { it.keyA to it.keyB })
    }

    private suspend fun buildChannels(ctx: BuildContext, rules: MatchRules, stats: MatchStats): Int {
        val ids = ctx.ids
        val rank = ctx.rank
        val categories = ctx.categories
        val generation = ctx.generation
        val rows = ids.flatMap { db.combinedDao().channelSources(it) }
        val prints = EpgFingerprints.of(db, ids, rows, clock())
        val catalog = catalog()
        stats.catalogSize = catalog.size
        val clusters = ChannelClusterer.cluster(rows, rules, prints, catalog, stats)
        val hasEpg = ids.associateWith { db.epgDao().hasPrograms(it) }
        val links = ArrayList<CombinedLinkEntity>(rows.size)
        // Zeilen blockweise schreiben statt alle im Speicher zu sammeln (schwache Geräte, große Listen).
        val batch = ArrayList<ChannelEntity>(CHUNK)
        for (cluster in clusters) {
            val id = Combined.itemId('L', cluster.key)
            val byQuality = cluster.members.sortedWith(compareBy<ChannelSourceRow>({ it.qualityRank }, { rank[it.playlistId] ?: 0 }, { it.sortIndex }))
            val primary = cluster.members.minWith(compareBy({ rank[it.playlistId] ?: 0 }, { it.sortIndex }))
            val epgSource = byQuality.firstOrNull { it.epgKey.isNotBlank() && hasEpg[it.playlistId] == true } ?: primary
            cluster.members.forEach { m ->
                val country = MatchKeys.channelName(m.groupKey)?.let(MatchKeys::countryOf)
                links += CombinedLinkEntity(id, m.id, m.playlistId, ContentType.LIVE.name, m.qualityRank, country)
            }
            val categoryId = categories.idFor(primary.playlistId, primary.categoryId, ContentType.LIVE.name)
            batch += CombinedRows.channel(id, CombinedRows.ChannelParts(primary, byQuality, epgSource, categoryId), rank, generation)
            if (batch.size >= CHUNK) {
                db.channelDao().insertAll(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) db.channelDao().insertAll(batch)
        db.withTransaction {
            db.channelDao().deleteStale(Combined.ID, generation)
            db.combinedDao().replaceLinks(ContentType.LIVE.name, links)
        }
        return clusters.size
    }

    /** Kombination entfernen (weniger als zwei Zugänge). */
    private suspend fun clear() {
        db.categoryDao().deleteForPlaylist(Combined.ID)
        db.channelDao().deleteForPlaylist(Combined.ID)
        db.vodDao().deleteForPlaylist(Combined.ID)
        db.seriesDao().deleteForPlaylist(Combined.ID)
        db.seriesDao().deleteEpisodesForPlaylist(Combined.ID)
        db.combinedDao().deleteAllLinks()
        groupIndex.clear(Combined.ID)
        searchIndex.clear(Combined.ID)
        AppLog.log(TAG, "Entfernt (weniger als zwei Zugänge einbezogen)")
    }

    companion object {
        /** Bei jeder Änderung der Zuordnungsregeln erhöhen: erzwingt einen Neuaufbau nach dem Update. */
        const val MATCHER_VERSION = 344
        const val TAG = "Alle Zugänge"
        private const val DEBOUNCE_MS = 3_000L
        private const val SYNC_POLL_MS = 2_000L
        internal const val CHUNK = 500
    }
}

/** Was jeder Teil des Aufbaus braucht: Datenbank, einbezogene Zugänge samt Rang, Gruppen, Generation. */
class BuildContext(
    val db: AppDatabase,
    val ids: List<Long>,
    val rank: Map<Long, Int>,
    val categories: CombinedCategories,
    val generation: Long,
)

/** Kategorien von „Alle Zugänge“: zusammengelegt (Land + Thema) oder nach Zugang getrennt. */
class CombinedCategories private constructor(
    val entities: List<CategoryEntity>,
    private val map: Map<Pair<Long, String>, String>,
    private val generation: Long,
) {

    private val usedFallback = HashSet<String>()

    /** Kategorie der kombinierten Zeile zu der Gruppe [categoryId] des Originals aus [playlistId] ([type] für den Ersatz). */
    fun idFor(playlistId: Long, categoryId: String, type: String): String = map[playlistId to categoryId]
        ?: Combined.categoryId(type, "$type|misc").also { synchronized(usedFallback) { usedFallback += type } }

    /** Sammelgruppen „Weitere“ für Einträge ohne bekannte Gruppe – nur die, die gebraucht wurden. */
    fun fallbackEntities(): List<CategoryEntity> = synchronized(usedFallback) { usedFallback.toList() }.map { type ->
        CategoryEntity(Combined.categoryId(type, "$type|misc"), Combined.ID, type, "", "Weitere", Int.MAX_VALUE, generation)
    }

    companion object {
        fun build(sources: List<CategorySourceRow>, byAccount: Boolean, rank: Map<Long, Int>, names: Map<Long, String>, generation: Long): CombinedCategories {
            val map = HashMap<Pair<Long, String>, String>()
            val targets = LinkedHashMap<String, CombinedGroup>()
            for (c in sources.sortedWith(compareBy({ rank[it.playlistId] ?: 0 }, { it.sortIndex }))) {
                val r = rank[c.playlistId] ?: 0
                val sort = r * RANK_STEP + c.sortIndex
                val group = if (byAccount) {
                    CombinedGroup("${c.type}|P${c.playlistId}|${c.id}", c.type, "${names[c.playlistId].orEmpty()} · ${c.name}", sort)
                } else {
                    CategoryTopics.classify(c.type, c.name).let {
                        CombinedGroup(it.key, c.type, it.label, sort, it.country, CategoryTopics.displayRank(it.topic))
                    }
                }
                val id = Combined.categoryId(c.type, group.key)
                map[c.playlistId to c.id] = id
                val existing = targets[id]
                if (existing == null || sort < existing.sort) targets[id] = group.copy(key = id, name = existing?.name ?: group.name)
            }
            val ordered = if (byAccount) targets.values.sortedBy { it.sort } else CombinedGroup.ordered(targets.values)
            val entities = ordered.mapIndexed { i, t -> CategoryEntity(t.key, Combined.ID, t.type, "", t.name, i, generation) }
            return CombinedCategories(entities, map, generation)
        }

        internal const val RANK_STEP = 1_000_000
    }
}

/**
 * Eine Gruppe von „Alle Zugänge“ vor dem Speichern. [sort] = Position beim wichtigsten Zugang (für die Länder-
 * Reihenfolge), [topicRank] = Rang der Rubrik ([CategoryTopics.displayRank]).
 */
internal data class CombinedGroup(
    val key: String,
    val type: String,
    val name: String,
    val sort: Int,
    val country: String? = null,
    val topicRank: Int = 0,
) {
    companion object {
        /**
         * Einheitliche Ordnung (ab 3.44): Länder in der Reihenfolge, in der sie beim wichtigsten Zugang zuerst
         * vorkommen (meist das eigene Land oben), Gruppen ohne Land danach; innerhalb eines Landes nach Rubrik
         * (Neuheiten, Vollprogramm, Unterhaltung, Nachrichten, Sport …), gleiche Rubrik bzw. ohne Rubrik alphabetisch.
         */
        fun ordered(groups: Collection<CombinedGroup>): List<CombinedGroup> {
            val countryFirst = HashMap<String, Int>()
            for (g in groups) g.country?.let { c -> countryFirst[c] = minOf(countryFirst[c] ?: Int.MAX_VALUE, g.sort) }
            return groups.sortedWith(
                compareBy<CombinedGroup>(
                    { it.country?.let(countryFirst::get) ?: Int.MAX_VALUE },
                    { it.country.orEmpty() },
                    { it.topicRank },
                    { it.name.lowercase(java.util.Locale.ROOT) },
                ),
            )
        }
    }
}

/** Ergebnis eines Aufbaus von „Alle Zugänge“. */
data class CombinedStats(
    val accounts: Int,
    val channels: Int,
    val movies: Int,
    val series: Int,
    /** Quellen, die der Senderkatalog eindeutig einem echten Sender zugeordnet hat. */
    val identified: Int,
    /** Verbindungen, die die Schutzregeln verhindert haben (anderer Sender, anderes Land, anderes Programm). */
    val refused: Int,
    val atMs: Long,
    /** Sender im verwendeten Katalog. */
    val catalogSize: Int = 0,
    /** Alle Senderquellen der Zugänge (Bezug für [identified]). */
    val sources: Int = 0,
)
