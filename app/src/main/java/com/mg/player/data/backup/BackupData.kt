package com.mg.player.data.backup

import com.mg.player.data.db.entity.CategoryPrefEntity
import com.mg.player.data.db.entity.FavoriteEntity
import com.mg.player.data.db.entity.IntroProfileEntity
import com.mg.player.data.db.entity.ProgressEntity
import com.mg.player.domain.model.PlaylistType
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
    private val intros: List<Intro>,
    private val categoryPrefs: List<Pref>,
    val settings: JSONObject,
) {
    class Favorite(val type: String, val itemId: String, val addedAt: Long)
    class Progress(val key: String, val position: Long, val duration: Long, val watched: Boolean, val updatedAt: Long, val seriesId: String)
    class Intro(val seriesId: String, val start: Long, val end: Long)
    class Pref(val categoryId: String, val playlistId: Long, val hidden: Boolean, val locked: Boolean, val pinned: Boolean, val position: Int? = null)

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
        intros.map { IntroProfileEntity(IdRemapper.remapId(it.seriesId, idMap), it.start, it.end) }

    fun prefEntities(idMap: Map<Long, Long>): List<CategoryPrefEntity> = categoryPrefs.map { c ->
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

        /** EPG-Zeitverschiebung je alter Zugangs-ID (ab 3.32). */
        private fun epgShifts(root: JSONObject): Map<Long, Int> = root.optJSONArray("playlists").objects()
            .filter { it.has("epgShiftHours") }
            .associate { it.optLong("id") to it.optInt("epgShiftHours") }

        /** Wirft eine verständliche Meldung, wenn die Datei unvollständig oder keine Sicherung dieser App ist. */
        fun parse(json: String): BackupData {
            val root = try {
                JSONObject(json)
            } catch (e: org.json.JSONException) {
                throw IllegalArgumentException("Die Sicherung ist beschädigt oder unvollständig.", e)
            }
            require(root.optString("format") == FORMAT) { "Das ist keine Sicherung dieser App." }
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
                intros = root.optJSONArray("introProfiles").objects().map {
                    Intro(it.optString("seriesId"), it.optLong("start"), it.optLong("end"))
                }.filter { it.seriesId.isNotEmpty() },
                categoryPrefs = root.optJSONArray("categoryPrefs").objects().map {
                    Pref(
                        it.optString("categoryId"),
                        it.optLong("playlistId"),
                        it.optBoolean("hidden"),
                        it.optBoolean("locked"),
                        it.optBoolean("pinned"),
                        it.optInt("position", -1).takeIf { p -> p >= 0 },
                    )
                }.filter { it.categoryId.isNotEmpty() },
                settings = root.optJSONObject("settings") ?: JSONObject(),
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optJSONObject(it) }
        }
    }
}
