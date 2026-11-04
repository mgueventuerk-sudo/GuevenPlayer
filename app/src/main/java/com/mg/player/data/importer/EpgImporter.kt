package com.mg.player.data.importer

import com.mg.player.core.log.AppLog
import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.util.NameNormalizer
import com.mg.player.core.util.UrlUtils
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.ChannelMatchRow
import com.mg.player.data.db.entity.EpgActiveEntity
import com.mg.player.data.db.entity.EpgProgramEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.parser.XmltvParser
import com.mg.player.data.remote.XtreamApi
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.ImportProgress
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/**
 * 3-Stufen-EPG:
 * 1. Xtream xmltv.php, 2. url-tvg / x-tvg-url aus dem M3U-Header (plus eigene EPG-URL),
 * 3. Fallback auf öffentliche XMLTV-Quellen mit Fuzzy-Matching der bereinigten Sendernamen
 *    (gebremst über [FallbackEpgGate], damit nicht jede Aktualisierung zig MB umsonst lädt).
 *
 * Gespeichert werden nur Sendungen von Sendern dieses Zugangs, die in sichtbaren Gruppen liegen.
 * Ein großer Anbieter mit 20.000 Sendern liefert sonst Millionen Zeilen (mehrere hundert MB) –
 * mit Länderauswahl "Deutschland" bleibt davon nur ein Bruchteil übrig.
 */
class EpgImporter(private val db: AppDatabase, private val fetcher: HttpFetcher, private val api: XtreamApi, private val fallbackGate: FallbackEpgGate) {

    private class SourceIndex {
        val keysWithPrograms = HashSet<String>()
        val byNormalizedName = HashMap<String, String>()
        val byNormalizedId = HashMap<String, String>()
    }

    private data class SourceImport(
        val playlistId: Long,
        val generation: Long,
        val url: String,
        val index: SourceIndex,
        val wanted: Wanted,
        val allowInsecureHttp: Boolean = false,
    )

    /** Welche EPG-Sender übernommen werden: Schlüssel, bereinigte IDs und Namen der eigenen Sender. */
    private class Wanted(rows: List<ChannelMatchRow>) {
        val keys = HashSet<String>()
        val idsAndNames = HashSet<String>()

        init {
            rows.forEach { row ->
                keys += row.epgKey
                idsAndNames += row.normalizedName
                if (row.epgChannelId.isNotBlank()) {
                    keys += row.epgChannelId.lowercase(Locale.ROOT)
                    idsAndNames += NameNormalizer.normalizeEpgId(row.epgChannelId)
                }
            }
        }

        fun accepts(key: String, normalizedId: String, names: List<String>): Boolean =
            key in keys || normalizedId in idsAndNames || names.any { it in idsAndNames }
    }

    /** Liefert true, wenn neues EPG übernommen wurde. */
    suspend fun refresh(playlist: PlaylistEntity, onProgress: (ImportProgress) -> Unit): Boolean {
        val pid = playlist.id
        val dao = db.epgDao()
        val active = dao.activeGeneration(pid) ?: 0L
        // Reste eines abgebrochenen Imports (unsichtbare Generationen) zuerst wegräumen.
        deleteStale(pid, keep = active)
        val rows = db.channelDao().visibleMatchRows(pid)
        if (rows.isEmpty()) return false

        // Neue Generation: Die Sendungen gehen direkt in epg_programs, bleiben aber unsichtbar, bis umgeschaltet wird.
        // Der bisherige EPG bleibt solange sichtbar.
        val generation = maxOf(System.currentTimeMillis(), active + 1)
        val index = SourceIndex()
        val wanted = Wanted(rows)
        val sources = primarySources(playlist)
        // Ohne eigene Quelle (M3U ohne url-tvg) zählt nur der öffentliche Fallback.
        var primaryOk = sources.isEmpty()
        sources.forEach { url ->
            onProgress(ImportProgress("Lade EPG…"))
            primaryOk = importSource(
                SourceImport(pid, generation, url, index, wanted, playlist.allowInsecureHttp),
                onProgress,
            ) || primaryOk
        }

        val updates = HashMap<String, String>()
        val unmatched = ArrayList<ChannelMatchRow>()
        for (row in rows) {
            if (row.epgKey in index.keysWithPrograms) continue
            val match = matchRow(row, index)
            if (match != null) updates[row.id] = match else unmatched += row
        }

        val coverage = 1f - unmatched.size.toFloat() / rows.size
        if (coverage < FALLBACK_THRESHOLD && unmatched.isNotEmpty()) {
            importFallback(pid, generation, unmatched, updates, onProgress)
        }
        val hadEpg = dao.hasPrograms(pid)
        if (dao.countGeneration(pid, generation) == 0 || (!primaryOk && hadEpg)) {
            // Nichts geladen oder die Hauptquelle ist ausgefallen: das bisherige EPG bleibt komplett stehen.
            deleteStale(pid, keep = active)
            val message = if (hadEpg) "EPG-Quelle nicht erreichbar – bisheriges EPG bleibt erhalten" else "Kein EPG gefunden"
            onProgress(ImportProgress(message, warning = if (hadEpg) message else null))
            return false
        }
        // Umschalten: eine Zeile. Danach die alte Generation in kleinen Blöcken löschen (kurze Sperren, wenig WAL).
        dao.setActive(EpgActiveEntity(pid, generation))
        if (updates.isNotEmpty()) db.channelDao().updateEpgKeys(updates)
        deleteStale(pid, keep = generation)
        onProgress(ImportProgress("EPG geladen", rows.size - unmatched.size))
        return true
    }

    /** Alle Sendungen des Zugangs außer Generation [keep] löschen – blockweise über den Primärschlüssel. */
    private suspend fun deleteStale(pid: Long, keep: Long) {
        val dao = db.epgDao()
        val range = dao.staleRange(pid, keep)
        val from = range.fromId ?: return
        val to = range.toId ?: return
        var start = from
        var deleted = 0
        while (start <= to) {
            val end = minOf(start + DELETE_BLOCK - 1, to)
            deleted += dao.deleteStaleBlock(pid, keep, start, end)
            start = end + 1
            yield()
        }
        if (deleted > 0) AppLog.log("EPG", "$deleted alte Sendungen gelöscht")
    }

    /** Stufe 3: öffentliche Quellen für Sender, die bisher kein EPG haben. Entfernt Treffer aus [unmatched]. */
    private suspend fun importFallback(
        pid: Long,
        generation: Long,
        unmatched: MutableList<ChannelMatchRow>,
        updates: MutableMap<String, String>,
        onProgress: (ImportProgress) -> Unit,
    ) {
        val signature = FallbackEpgGate.signature(unmatched.map { it.id })
        val now = System.currentTimeMillis()
        if (!fallbackGate.allowed(pid, signature, now)) {
            AppLog.log("EPG", "Öffentliche Quellen übersprungen (kürzlich geprüft, ${unmatched.size} Sender ohne EPG)")
            return
        }
        val before = unmatched.size
        var reachable = false
        for (url in fallbackSources()) {
            if (unmatched.isEmpty()) break
            onProgress(ImportProgress("Suche EPG in öffentlichen Quellen…"))
            val fallbackIndex = SourceIndex()
            reachable = importSource(SourceImport(pid, generation, url, fallbackIndex, Wanted(unmatched)), onProgress) || reachable
            val iterator = unmatched.iterator()
            while (iterator.hasNext()) {
                val row = iterator.next()
                val match = matchRow(row, fallbackIndex) ?: continue
                updates[row.id] = match
                iterator.remove()
            }
        }
        val matched = before - unmatched.size
        fallbackGate.record(pid, signature, now, if (reachable || matched > 0) matched else null)
    }

    private fun matchRow(row: ChannelMatchRow, index: SourceIndex): String? {
        val byId = row.epgChannelId.takeIf { it.isNotBlank() }
            ?.let { index.byNormalizedId[NameNormalizer.normalizeEpgId(it)] }
        val candidate = byId ?: index.byNormalizedName[row.normalizedName] ?: index.byNormalizedId[row.normalizedName]
        return candidate?.takeIf { it in index.keysWithPrograms }
    }

    /**
     * Importiert eine XMLTV-Quelle in die Zwischentabelle. Übernommen werden nur Sender, die in [wanted]
     * vorkommen. Liefert true, wenn die Quelle vollständig gelesen wurde.
     */
    private suspend fun importSource(source: SourceImport, onProgress: (ImportProgress) -> Unit): Boolean {
        val playlistId = source.playlistId
        val generation = source.generation
        val index = source.index
        val wanted = source.wanted
        val now = System.currentTimeMillis()
        val windowStart = now - 6 * HOUR
        val windowEnd = now + 7 * DAY // 7 Tage: so weit reicht die Sport-Sektion.
        val acceptedIds = HashSet<String>()
        val writer = BatchWriter<EpgProgramEntity>(2_000) { db.epgDao().insertPrograms(it) }
        val ok = try {
            fetcher.openMaybeGzip(source.url, source.allowInsecureHttp).use { input ->
                XmltvParser.parse(
                    input,
                    object : XmltvParser.Callback {
                        override suspend fun onChannel(id: String, displayNames: List<String>) {
                            val key = id.lowercase(Locale.ROOT)
                            val normalizedId = NameNormalizer.normalizeEpgId(id)
                            val names = displayNames.map(NameNormalizer::normalize).filter { it.isNotEmpty() }
                            if (!wanted.accepts(key, normalizedId, names)) return
                            acceptedIds += key
                            // getOrPut statt putIfAbsent: das gibt es erst ab Android 7 (minSdk ist 23).
                            index.byNormalizedId.getOrPut(normalizedId) { key }
                            names.forEach { index.byNormalizedName.getOrPut(it) { key } }
                        }

                        override suspend fun onProgramme(channelId: String, startMs: Long, endMs: Long, title: String, description: String?) {
                            if (endMs < windowStart || startMs > windowEnd) return
                            val key = channelId.lowercase(Locale.ROOT)
                            // Manche Quellen listen <programme> ohne passenden <channel>-Eintrag: dann zählt der Schlüssel.
                            if (key !in acceptedIds && key !in wanted.keys) return
                            index.keysWithPrograms += key
                            writer.add(
                                EpgProgramEntity(
                                    playlistId = playlistId,
                                    channelKey = key,
                                    startMs = startMs,
                                    endMs = endMs,
                                    title = title,
                                    // Gekürzt: Lange Texte blähen die Datenbank auf, angezeigt werden ohnehin nur ein paar Zeilen.
                                    description = description?.let { shortDescription(it) },
                                    generation = generation,
                                ),
                            )
                            if (writer.count % 5_000 == 0) onProgress(ImportProgress("Lade EPG…", writer.count))
                        }
                    },
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Eine defekte Quelle soll die übrigen Stufen nicht verhindern. Zugangsdaten nie ins Log.
            AppLog.log("EPG", "Quelle fehlgeschlagen (${UrlUtils.redact(source.url)}): ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            false
        }
        writer.finish()
        return ok
    }

    private fun primarySources(playlist: PlaylistEntity): List<String> = buildList {
        if (playlist.customEpgUrl.isNotBlank()) add(playlist.customEpgUrl.trim())
        if (playlist.type == PlaylistType.XTREAM.name) {
            add(api.xmltvUrl(api.credentials(playlist)))
        } else if (playlist.headerEpgUrl.isNotBlank()) {
            add(playlist.headerEpgUrl.trim())
        }
    }.distinct()

    private fun fallbackSources(): List<String> {
        val country = Locale.getDefault().country.uppercase(Locale.ROOT)
        val language = Locale.getDefault().language.lowercase(Locale.ROOT)
        val code = when {
            country in setOf("DE", "AT", "CH") || language == "de" -> "DE1"
            country == "GB" || country == "IE" -> "UK1"
            country == "FR" || language == "fr" -> "FR1"
            country == "IT" || language == "it" -> "IT1"
            else -> "US1"
        }
        return listOf("$FALLBACK_BASE/epg_ripper_$code.xml.gz")
    }

    private companion object {
        /** Zeilen je Löschblock (eine kurze Transaktion je Block). */
        const val DELETE_BLOCK = 5_000L

        /** Längere Beschreibungen werden gekürzt (Anzeige: wenige Zeilen). */
        const val MAX_DESCRIPTION = 600

        fun shortDescription(text: String): String =
            if (text.length <= MAX_DESCRIPTION) text else text.substring(0, MAX_DESCRIPTION).substringBeforeLast(' ').trimEnd() + " …"

        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val FALLBACK_THRESHOLD = 0.6f
        const val FALLBACK_BASE = "https://epgshare01.online/epgshare01"
    }
}
