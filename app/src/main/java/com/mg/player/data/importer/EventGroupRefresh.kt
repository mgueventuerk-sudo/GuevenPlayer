package com.mg.player.data.importer

import android.net.Uri
import com.mg.player.core.log.AppLog
import com.mg.player.core.security.SecretCipher
import com.mg.player.core.util.CountryDetector
import com.mg.player.core.util.NameNormalizer
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.parser.forEachObject
import com.mg.player.data.parser.jsonReader
import com.mg.player.data.parser.str
import com.mg.player.data.remote.XtreamApi
import com.mg.player.data.search.SearchText
import com.mg.player.domain.model.PlaylistType
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kurz vor Anstoß die Event- und Sport-Gruppen der Xtream-Zugänge neu abfragen (get_live_streams mit category_id).
 * Anbieter benennen ihre Event-Sender oft erst kurz vor dem Spiel um („DAZN EVENT 3“ → „DAZN EVENT 3: Galatasaray –
 * Fenerbahçe“) – bis zur nächsten vollen Aktualisierung des Zugangs sähe die App das sonst nicht.
 *
 * Übernommen werden nur neue Namen, tvg-ids, Gruppen und neue Sender. Gelöscht wird hier nie etwas (ein Panel, das gerade
 * nichts oder Unsinn liefert, kostet so keinen Sender) – das bleibt der vollen Aktualisierung vorbehalten.
 * Je Zugang höchstens alle 10 Minuten und höchstens [MAX_GROUPS] Gruppen; M3U-Zugänge haben keine solche Abfrage.
 */
class EventGroupRefresh(
    private val db: AppDatabase,
    private val api: XtreamApi,
    /** Läuft gerade eine volle Aktualisierung? Dann nichts tun – sie holt ohnehin alles neu. */
    private val busy: () -> Boolean = { false },
    /** Nach Änderungen: Sender-Gruppierung und Suchindex des Zugangs nachziehen (id → neuer Name). */
    private val onChanged: suspend (playlistId: Long, changed: List<ChannelEntity>) -> Unit = { _, _ -> },
    /** Für Tests: Antwort statt Netz. */
    private val open: (XtreamApi.Credentials, String) -> InputStream = { c, categoryRemoteId ->
        api.openAction(c, "get_live_streams", "&category_id=${Uri.encode(categoryRemoteId)}")
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val lastRun = HashMap<Long, Long>()

    private val _running = MutableStateFlow(false)

    /** true während der Abfrage: Senderlisten sammeln ihre Neuladungen bis danach (kein Springen, Fokus bleibt). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Alle Xtream-Zugänge; liefert die Zahl geänderter bzw. neuer Sender. */
    suspend fun refresh(): Int = refreshFor(null)

    /** Scope test refreshes to their fixture account; production callers still refresh every configured account. */
    internal suspend fun refresh(playlistIds: Set<Long>): Int = refreshFor(playlistIds)

    private suspend fun refreshFor(playlistIds: Set<Long>?): Int = withContext(Dispatchers.IO) {
        if (busy()) return@withContext 0
        mutex.withLock {
            _running.value = true
            try {
                refreshAll(playlistIds)
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun refreshAll(playlistIds: Set<Long>?): Int {
        var changed = 0
        val playlists = db.playlistDao().all().filter {
            (playlistIds == null || it.id in playlistIds) && it.type == PlaylistType.XTREAM.name && SecretCipher.isReadable(it.password)
        }
        for (playlist in playlists.take(MAX_PLAYLISTS)) {
            val now = clock()
            if (now - (lastRun[playlist.id] ?: Long.MIN_VALUE / 2) < COOLDOWN_MS) continue
            lastRun[playlist.id] = now
            changed += try {
                refreshPlaylist(playlist.id, api.credentials(playlist))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.log("Sport", "Event-Gruppen vor Anstoß (Zugang ${playlist.id}): ${e.javaClass.simpleName}")
                0
            }
        }
        return changed
    }

    private suspend fun refreshPlaylist(playlistId: Long, creds: XtreamApi.Credentials): Int {
        val groups = eventGroups(db.categoryDao().openLiveCategories(playlistId))
        if (groups.isEmpty()) return 0
        val wanted = groups.associateBy { it.remoteId }
        val fresh = LinkedHashMap<String, Map<String, String?>>()
        for (group in groups) {
            if (busy()) return 0
            var foreign = false
            open(creds, group.remoteId).use { input ->
                jsonReader(input).forEachObject { item ->
                    val streamId = item.str("stream_id") ?: return@forEachObject
                    val category = item.str("category_id") ?: group.remoteId
                    if (category in wanted) fresh[streamId] = item else foreign = true
                }
            }
            // Manche Panels ignorieren category_id und schicken alle Sender: Dann sind alle Gruppen schon in dieser Antwort.
            if (foreign) break
        }
        if (fresh.isEmpty()) return 0

        val existing = fresh.keys.map { XtreamImporter.liveChannelId(playlistId, it) }.chunked(MAX_SQL_ARGS)
            .flatMap { db.channelDao().entities(it) }.associateBy { it.id }
        val generation = db.channelDao().latestGeneration(playlistId) ?: 0L
        val nextSort = HashMap<String, Int>()
        val updates = ArrayList<ChannelEntity>()
        var renamed = 0
        for ((_, item) in fresh) {
            val remoteCategory = item.str("category_id") ?: continue
            val country = CountryDetector.detect(wanted[remoteCategory]?.name ?: "")
            val built = XtreamImporter.liveChannel(api, playlistId, item, country, 0, generation) ?: continue
            val old = existing[built.id]
            if (old == null) {
                val sort = nextSort.getOrPut(built.categoryId) { (db.channelDao().lastSortIndex(playlistId, built.categoryId) ?: 0) } + 1
                nextSort[built.categoryId] = sort
                updates += built.copy(sortIndex = sort)
            } else {
                merge(old, built)?.let {
                    updates += it
                    if (it.name != old.name) renamed++
                }
            }
        }
        if (updates.isEmpty()) return 0
        db.channelDao().insertAll(updates)
        onChanged(playlistId, updates)
        val added = updates.count { it.id !in existing }
        AppLog.log("Sport", "Event-Gruppen vor Anstoß: $renamed umbenannt, $added neu, ${updates.size - renamed - added} geändert (Zugang $playlistId)")
        return updates.size
    }

    companion object {
        private const val COOLDOWN_MS = 10 * 60_000L
        const val MAX_GROUPS = 12
        private const val MAX_PLAYLISTS = 4
        private const val MAX_SQL_ARGS = 900

        // Gefaltete Schreibweise (wie SearchText): „MAÇ“ → „mac“, „ꜱᴘᴏʀᴛ“ → „sport“, „Fußball“ → „fussball“.
        private val EVENT_WORDS = listOf("event", "ppv", "pay per view", "match", "mac", "canli")
        private val SPORT_WORDS = listOf(
            "sport", "spor", "dazn", "bein", "eurosport", "fight", "ufc", "boxing", "boxen", "f1", "formula", "formel",
            "nfl", "nba", "nhl", "mlb", "football", "fussball", "futbol", "soccer", "liga", "league", "tennis",
            "eleven", "tivibu", "exxen", "tabii", "setanta", "viaplay", "espn",
        )

        private fun words(name: String): String = " " + SearchText.words(name).joinToString(" ") + " "

        private fun mentions(name: String, list: List<String>): Boolean {
            val text = words(name)
            // Ganze Wörter bzw. Wortanfänge („Sports“, „Spor“), damit „Skyline“ oder „Transport“ nicht zählen.
            return list.any { w -> " $w" in text && (w.length >= 4 || " $w " in text || " ${w}s " in text) }
        }

        /** Event-Gruppen zuerst, dann Sport-Gruppen (Reihenfolge des Anbieters), höchstens [MAX_GROUPS]. */
        internal fun eventGroups(categories: List<CategoryEntity>): List<CategoryEntity> {
            val events = categories.filter { mentions(it.name, EVENT_WORDS) }
            val sport = categories.filter { it !in events && mentions(it.name, SPORT_WORDS) }
            return (events + sport).take(MAX_GROUPS)
        }

        /**
         * Neuer Stand eines vorhandenen Senders, null = nichts geändert. Die EPG-Zuordnung bleibt, solange sich weder die
         * tvg-id noch (bei Zuordnung über den Namen) der Name geändert hat – sonst wird sie wie beim Import neu gebildet.
         */
        internal fun merge(old: ChannelEntity, fresh: ChannelEntity): ChannelEntity? {
            val same = old.name == fresh.name && old.epgChannelId == fresh.epgChannelId && old.categoryId == fresh.categoryId &&
                old.logoUrl == fresh.logoUrl && old.catchupDays == fresh.catchupDays
            if (same) return null
            val keyFromName = old.epgKey == "name:${old.normalizedName}"
            val epgKey = if (old.epgChannelId != fresh.epgChannelId || (keyFromName && old.normalizedName != NameNormalizer.normalize(fresh.name))) {
                fresh.epgKey
            } else {
                old.epgKey
            }
            return old.copy(
                name = fresh.name,
                normalizedName = fresh.normalizedName,
                epgChannelId = fresh.epgChannelId,
                epgKey = epgKey,
                categoryId = fresh.categoryId,
                logoUrl = fresh.logoUrl,
                catchupDays = fresh.catchupDays,
                catchupType = fresh.catchupType,
                groupKey = fresh.groupKey,
                qualityRank = fresh.qualityRank,
            )
        }
    }
}
