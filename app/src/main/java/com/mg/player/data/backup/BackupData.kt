package com.mg.player.data.backup

import com.mg.player.data.db.entity.CategoryPrefEntity
import com.mg.player.data.db.entity.FavoriteEntity
import com.mg.player.data.db.entity.IntroProfileEntity
import com.mg.player.data.db.entity.ProgressEntity
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.Recording
import com.mg.player.domain.model.RecordingStatus
import com.mg.player.domain.model.Reminder
import com.mg.player.domain.repository.PlaylistInput
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vollständig gelesene und geprüfte Sicherung. Erst wenn das ganze JSON fehlerfrei gelesen ist,
 * wird überhaupt etwas an der Datenbank geändert – eine kaputte Datei kann so nichts mehr löschen.
 */
internal class BackupData(
    val playlists: List<Pair<Long, PlaylistInput>>,
    /** EPG-Zeitverschiebung je alter Zugangs-ID (fehlt in Sicherungen bis 3.31). */
    val epgShiftHours: Map<Long, Int>,
    private val favorites: List<Favorite>,
    private val progress: List<Progress>,
    private val playlistExtras: PlaylistExtras,
    private val scheduled: ScheduledItems,
    val settings: JSONObject,
) {
    class Favorite(val type: String, val itemId: String, val addedAt: Long)
    class Progress(val key: String, val position: Long, val duration: Long, val watched: Boolean, val updatedAt: Long, val seriesId: String)
    class Intro(val seriesId: String, val start: Long, val end: Long)
    class Pref(val categoryId: String, val playlistId: Long, val hidden: Boolean, val locked: Boolean, val pinned: Boolean, val position: Int? = null)
    class RecordingItem(val channelId: String, val channelName: String, val title: String, val startedAt: Long, val plannedEnd: Long, val programStart: Long)
    class ReminderItem(val channelId: String, val channelName: String, val title: String, val startMs: Long)
    /** Geplante Aufnahmen + Erinnerungen - gebündelt, damit der BackupData-Konstruktor nicht zu viele Parameter bekommt. */
    class ScheduledItems(val recordings: List<RecordingItem>, val reminders: List<ReminderItem>)
    /** Gelernte Intro-Zeiten + Kategorie-Einstellungen je Zugang - aus demselben Grund gebündelt. */
    class PlaylistExtras(val intros: List<Intro>, val categoryPrefs: List<Pref>)

    fun favoriteEntities(idMap: Map<Long, Long>): List<FavoriteEntity> = favorites.map { f ->
        val itemId = IdRemapper.remapId(f.itemId, idMap)
        FavoriteEntity("${f.type}:$itemId", f.type, itemId, f.addedAt)
    }

    fun progressEntities(idMap: Map<Long, Long>): List<ProgressEntity> = progress.map { p ->
        ProgressEntity(
            key = IdRemapper.remapKey(p.key, idMap),
            positionMs = p.position,
            durationMs = p.duration,
            watched = p.watched,
            updatedAtMs = p.updatedAt,
            seriesId = p.seriesId.takeIf { it.isNotEmpty() }?.let { IdRemapper.remapId(it, idMap) },
        )
    }

    fun introEntities(idMap: Map<Long, Long>): List<IntroProfileEntity> =
        playlistExtras.intros.map { IntroProfileEntity(IdRemapper.remapId(it.seriesId, idMap), it.start, it.end) }

    fun recordingEntities(idMap: Map<Long, Long>): List<Recording> = scheduled.recordings.map { r ->
        val channelId = IdRemapper.remapId(r.channelId, idMap)
        Recording(
            id = "restore" + r.startedAt.toString(ID_RADIX) + channelId.hashCode().toUInt().toString(ID_RADIX),
            channelId = channelId,
            channelName = r.channelName,
            title = r.title,
            startedAtMs = r.startedAt,
            plannedEndMs = r.plannedEnd,
            endedAtMs = 0,
            filePath = "",
            status = RecordingStatus.SCHEDULED,
            bytes = 0,
            programStartMs = r.programStart,
        )
    }

    fun reminderEntities(idMap: Map<Long, Long>): List<Reminder> =
        scheduled.reminders.map { Reminder(IdRemapper.remapId(it.channelId, idMap), it.channelName, it.title, it.startMs) }

    fun prefEntities(idMap: Map<Long, Long>): List<CategoryPrefEntity> = playlistExtras.categoryPrefs.map { c ->
        CategoryPrefEntity(
            categoryId = IdRemapper.remapId(c.categoryId, idMap),
            playlistId = idMap[c.playlistId] ?: c.playlistId,
            hidden = c.hidden,
            locked = c.locked,
            pinned = c.pinned,
            position = c.position,
        )
    }

    companion object {
        const val FORMAT = "mg-player-backup-1"
        private const val ID_RADIX = 36

        /** EPG-Zeitverschiebung je alter Zugangs-ID (ab 3.32). */
        private fun epgShifts(root: JSONObject): Map<Long, Int> = root.optJSONArray("playlists").objects()
            .filter { it.has("epgShiftHours") }
            .associate { it.optLong("id") to it.optInt("epgShiftHours") }

        private fun playlistExtras(root: JSONObject): PlaylistExtras {
            val intros = root.optJSONArray("introProfiles").objects().map {
                Intro(it.optString("seriesId"), it.optLong("start"), it.optLong("end"))
            }.filter { it.seriesId.isNotEmpty() }
            val categoryPrefs = root.optJSONArray("categoryPrefs").objects().map {
                Pref(
                    it.optString("categoryId"),
                    it.optLong("playlistId"),
                    it.optBoolean("hidden"),
                    it.optBoolean("locked"),
                    it.optBoolean("pinned"),
                    it.optInt("position", -1).takeIf { p -> p >= 0 },
                )
            }.filter { it.categoryId.isNotEmpty() }
            return PlaylistExtras(intros, categoryPrefs)
        }

        /** Geplante Aufnahmen + Erinnerungen (ab 3.33) - beides optional, fehlt in älteren Sicherungen. */
        private fun scheduledItems(root: JSONObject): ScheduledItems {
            val recordings = root.optJSONArray("recordings").objects().map {
                RecordingItem(
                    it.optString("channelId"),
                    it.optString("channelName"),
                    it.optString("title"),
                    it.optLong("startedAt"),
                    it.optLong("plannedEnd"),
                    it.optLong("programStart"),
                )
            }.filter { it.channelId.isNotEmpty() && it.title.isNotEmpty() }
            val reminders = root.optJSONArray("reminders").objects().map {
                ReminderItem(it.optString("channelId"), it.optString("channelName"), it.optString("title"), it.optLong("startMs"))
            }.filter { it.channelId.isNotEmpty() }
            return ScheduledItems(recordings, reminders)
        }

        private fun parsePlaylists(root: JSONObject): List<Pair<Long, PlaylistInput>> {
            val playlists = root.optJSONArray("playlists").objects().map { p ->
                val type = PlaylistType.entries.firstOrNull { it.name == p.optString("type") } ?: PlaylistType.M3U
                val input = PlaylistInput(
                    name = p.optString("name"),
                    type = type,
                    serverUrl = p.optString("serverUrl"),
                    username = p.optString("username"),
                    password = p.optString("password"),
                    m3uUrl = p.optString("m3uUrl"),
                    epgUrl = p.optString("epgUrl"),
                    // Alte Sicherungen besitzen kein explizites HTTP-Opt-in und bleiben sicher gesperrt.
                    allowInsecureHttp = p.optBoolean("allowInsecureHttp", false),
                )
                val complete = when (type) {
                    PlaylistType.XTREAM -> input.serverUrl.isNotBlank() && input.username.isNotBlank()
                    PlaylistType.M3U -> input.m3uUrl.isNotBlank()
                }
                require(complete) { "Die Sicherung enthält einen unvollständigen Zugang (\"${input.name}\")." }
                p.optLong("id") to input
            }
            // Ohne Zugänge würde das Wiederherstellen alle bisherigen löschen und nichts Neues anlegen.
            require(playlists.isNotEmpty()) { "Die Sicherung enthält keine Zugänge." }
            return playlists
        }

        /** Wirft eine verständliche Meldung, wenn die Datei unvollständig oder keine Sicherung dieser App ist. */
        fun parse(json: String): BackupData {
            val root = try {
                JSONObject(json)
            } catch (e: org.json.JSONException) {
                throw IllegalArgumentException("Die Sicherung ist beschädigt oder unvollständig.", e)
            }
            require(root.optString("format") == FORMAT) { "Das ist keine Sicherung dieser App." }
            val playlists = parsePlaylists(root)
            return BackupData(
                playlists = playlists,
                epgShiftHours = epgShifts(root),
                favorites = root.optJSONArray("favorites").objects().map {
                    Favorite(it.optString("type"), it.optString("itemId"), it.optLong("addedAt"))
                }.filter { it.type.isNotEmpty() && it.itemId.isNotEmpty() },
                progress = root.optJSONArray("progress").objects().map {
                    Progress(
                        it.optString("key"),
                        it.optLong("position"),
                        it.optLong("duration"),
                        it.optBoolean("watched"),
                        it.optLong("updatedAt"),
                        it.optString("seriesId"),
                    )
                }.filter { it.key.isNotEmpty() },
                playlistExtras = playlistExtras(root),
                scheduled = scheduledItems(root),
                settings = root.optJSONObject("settings") ?: JSONObject(),
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optJSONObject(it) }
        }
    }
}
