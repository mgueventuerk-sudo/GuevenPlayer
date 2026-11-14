package com.mg.player.data.filter

import androidx.compose.runtime.Immutable
import com.mg.player.core.log.AppLog
import com.mg.player.core.util.CountryDetector
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.CategoryPrefEntity
import com.mg.player.domain.model.ContentType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Welche Länder ein Zugang zeigen soll; [keepUnknown] = Gruppen ohne Länderangabe ("Sport", "Kinder" …). */
@Immutable
data class CountrySelection(val countries: Set<String>, val keepUnknown: Boolean = true)

/** Ein erkanntes Land mit der Zahl seiner Gruppen je Bereich. */
@Immutable
data class CountryCount(val code: String, val label: String, val live: Int, val movies: Int, val series: Int) {
    val total: Int get() = live + movies + series
}

@Immutable
data class CountrySummary(val countries: List<CountryCount>, val unknownGroups: Int)

/**
 * Länderfilter pro Zugang: blendet Gruppen nicht gewählter Länder über die vorhandenen
 * Kategorie-Einstellungen aus. Die Auswahl wird gespeichert, damit neue Gruppen nach einer
 * Aktualisierung automatisch genauso behandelt werden.
 */
class CountryFilter(
    private val db: AppDatabase,
    private val file: File,
    /** Gruppen wurden ein- oder ausgeblendet (Sender-Gruppierung neu aufbauen). */
    private val onPrefsChanged: (playlistId: Long) -> Unit = {},
) {

    private val mutex = Mutex()

    suspend fun summary(playlistId: Long): CountrySummary = withContext(Dispatchers.IO) {
        val counts = HashMap<String, IntArray>()
        var unknown = 0
        db.categoryDao().allForPlaylist(playlistId).forEach { category ->
            val code = CountryDetector.detect(category.name)
            if (code == null) {
                unknown++
                return@forEach
            }
            val slot = when (category.type) {
                ContentType.LIVE.name -> 0
                ContentType.MOVIE.name -> 1
                else -> 2
            }
            counts.getOrPut(code) { IntArray(AREAS) }[slot]++
        }
        val list = counts.map { (code, c) -> CountryCount(code, CountryDetector.info(code).label, c[0], c[1], c[2]) }
            .sortedByDescending { it.total }
        CountrySummary(list, unknown)
    }

    suspend fun selection(playlistId: Long): CountrySelection? = withContext(Dispatchers.IO) {
        mutex.withLock { readAll()[playlistId] }
    }

    /** Speichert die Auswahl und blendet alle Gruppen entsprechend ein oder aus. */
    suspend fun apply(playlistId: Long, selection: CountrySelection) {
        val previous = withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readAll()
                writeAll(all + (playlistId to selection))
                all[playlistId]
            }
        }
        update(playlistId, selection, previous, onlyNew = false)
    }

    /** Hebt den Filter auf: alle vom Filter ausgeblendeten Gruppen sind wieder sichtbar, von Hand ausgeblendete bleiben aus. */
    suspend fun clear(playlistId: Long) {
        val previous = withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readAll()
                writeAll(all - playlistId)
                all[playlistId]
            }
        } ?: return
        update(playlistId, CountrySelection(CountryDetector.countries.keys, keepUnknown = true), previous, onlyNew = false)
    }

    /** Nach einer Aktualisierung: Neue Gruppen gemäß gespeicherter Auswahl ein- oder ausblenden. */
    suspend fun applyToNewGroups(playlistId: Long) {
        val selection = selection(playlistId) ?: return
        update(playlistId, selection, selection, onlyNew = true)
    }

    private suspend fun update(playlistId: Long, selection: CountrySelection, previous: CountrySelection?, onlyNew: Boolean) = withContext(Dispatchers.IO) {
        val prefs = db.categoryPrefDao().forPlaylist(playlistId).associateBy { it.categoryId }
        val changes = db.categoryDao().allForPlaylist(playlistId).mapNotNull { category ->
            val existing = prefs[category.id]
            // Selbst gewählte Favoriten-Gruppen bleiben immer sichtbar.
            if (existing?.pinned == true) return@mapNotNull null
            val code = CountryDetector.detect(category.name)
            val hidden = CountryFilterRules.hiddenAfter(code, existing?.hidden, selection, previous, onlyNew)
                ?: return@mapNotNull null
            CategoryPrefEntity(
                categoryId = category.id,
                playlistId = playlistId,
                hidden = hidden,
                locked = existing?.locked ?: false,
                pinned = false,
                position = existing?.position,
            )
        }
        if (changes.isNotEmpty()) {
            db.categoryPrefDao().upsertAll(changes)
            onPrefsChanged(playlistId)
        }
        AppLog.log("Länder", "Filter für Zugang $playlistId angewendet: ${changes.size} Gruppen geändert")
    }

    private companion object {
        /** Live, Filme, Serien. */
        const val AREAS = 3
    }

    private fun readAll(): Map<Long, CountrySelection> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            root.keys().asSequence().associate { key ->
                val item = root.getJSONObject(key)
                val codes = item.optJSONArray("countries") ?: JSONArray()
                key.toLong() to CountrySelection(
                    countries = (0 until codes.length()).map { codes.getString(it) }.toSet(),
                    keepUnknown = item.optBoolean("keepUnknown", true),
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(all: Map<Long, CountrySelection>) {
        val root = JSONObject()
        all.forEach { (id, selection) ->
            root.put(
                id.toString(),
                JSONObject().put("countries", JSONArray(selection.countries.toList())).put("keepUnknown", selection.keepUnknown),
            )
        }
        file.writeText(root.toString())
    }
}
