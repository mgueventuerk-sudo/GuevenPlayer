package com.mg.player.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mg.player.data.db.entity.CombinedLinkEntity
import com.mg.player.data.db.entity.CombinedRuleEntity

/** Schlanke Zeile eines echten Senders für den Aufbau von „Alle Zugänge“ (ohne Stream-Adresse und EPG-Texte). */
data class ChannelSourceRow(
    val id: String,
    val playlistId: Long,
    val categoryId: String,
    val name: String,
    val logoUrl: String?,
    val epgChannelId: String,
    val epgKey: String,
    val groupKey: String,
    val qualityRank: Int,
    val sortIndex: Int,
    val catchupDays: Int,
    /** Interner Cursor-Schlüssel für begrenzte Seiten beim Zusammenführen großer Playlists. */
    val rowId: Long = 0,
)

/** Schlanke Zeile eines Films oder einer Serie für den Aufbau. */
data class VodSourceRow(
    val id: String,
    val playlistId: Long,
    val categoryId: String,
    val name: String,
    val year: String?,
    val tmdbId: String?,
    val sortIndex: Int,
    val posterUrl: String?,
    val rating: Float?,
    val durationSec: Int?,
    /** Interner Cursor-Schlüssel für begrenzte Seiten beim Zusammenführen großer Playlists. */
    val rowId: Long = 0,
)

/** Eine Sendung für den Programm-Fingerabdruck. */
data class EpgSlot(val channelKey: String, val startMs: Long, val title: String)

/** Fortschritt eines Originals, zugeordnet zum zusammengeführten Eintrag. */
data class LinkedProgress(val combinedId: String, val positionMs: Long, val durationMs: Long, val watched: Boolean, val updatedAtMs: Long)

data class CategorySourceRow(val id: String, val playlistId: Long, val type: String, val name: String, val sortIndex: Int)

/** Quelle eines zusammengeführten Eintrags samt Verbindungsdaten für die Rangfolge beim Abspielen. */
data class LinkedSource(
    val itemId: String,
    val playlistId: Long,
    val qualityRank: Int,
    val language: String?,
    val durationSec: Int?,
)

@Dao
abstract class CombinedDao {

    @Query(
        "SELECT c.rowid AS rowId, c.id, c.playlistId, substr(c.categoryId, 1, 512) AS categoryId, " +
            "substr(c.name, 1, 2048) AS name, substr(c.logoUrl, 1, 4096) AS logoUrl, " +
            "substr(c.epgChannelId, 1, 512) AS epgChannelId, substr(c.epgKey, 1, 512) AS epgKey, " +
            "substr(c.groupKey, 1, 1024) AS groupKey, c.qualityRank, c.sortIndex, c.catchupDays " +
            "FROM channels c WHERE c.playlistId = :playlistId AND c.rowid > :afterRowId AND $EXCLUDE_RESTRICTED " +
            "ORDER BY c.rowid LIMIT :limit",
    )
    abstract suspend fun channelSourcePage(playlistId: Long, afterRowId: Long, limit: Int): List<ChannelSourceRow>

    /** Vollständige, aber begrenzte Projektion für kleine Hilfsabfragen und bestehende Tests. */
    @Query(
        "SELECT c.rowid AS rowId, c.id, c.playlistId, substr(c.categoryId, 1, 512) AS categoryId, " +
            "substr(c.name, 1, 2048) AS name, substr(c.logoUrl, 1, 4096) AS logoUrl, " +
            "substr(c.epgChannelId, 1, 512) AS epgChannelId, substr(c.epgKey, 1, 512) AS epgKey, " +
            "substr(c.groupKey, 1, 1024) AS groupKey, c.qualityRank, c.sortIndex, c.catchupDays " +
            "FROM channels c WHERE c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED",
    )
    abstract suspend fun channelSources(playlistId: Long): List<ChannelSourceRow>

    @Query(
        "SELECT c.rowid AS rowId, c.id, c.playlistId, substr(c.categoryId, 1, 512) AS categoryId, " +
            "substr(c.name, 1, 2048) AS name, substr(c.year, 1, 32) AS year, substr(c.tmdbId, 1, 128) AS tmdbId, " +
            "c.sortIndex, substr(c.posterUrl, 1, 4096) AS posterUrl, c.rating, c.durationSec FROM movies c " +
            "WHERE c.playlistId = :playlistId AND c.rowid > :afterRowId AND $EXCLUDE_RESTRICTED " +
            "ORDER BY c.rowid LIMIT :limit",
    )
    abstract suspend fun movieSourcePage(playlistId: Long, afterRowId: Long, limit: Int): List<VodSourceRow>

    @Query(
        "SELECT c.rowid AS rowId, c.id, c.playlistId, substr(c.categoryId, 1, 512) AS categoryId, " +
            "substr(c.name, 1, 2048) AS name, substr(c.year, 1, 32) AS year, substr(c.tmdbId, 1, 128) AS tmdbId, " +
            "c.sortIndex, substr(c.posterUrl, 1, 4096) AS posterUrl, c.rating, c.durationSec FROM movies c " +
            "WHERE c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED",
    )
    abstract suspend fun movieSources(playlistId: Long): List<VodSourceRow>

    @Query(
        "SELECT c.rowid AS rowId, c.id, c.playlistId, substr(c.categoryId, 1, 512) AS categoryId, " +
            "substr(c.name, 1, 2048) AS name, substr(c.year, 1, 32) AS year, substr(c.tmdbId, 1, 128) AS tmdbId, " +
            "c.sortIndex, substr(c.posterUrl, 1, 4096) AS posterUrl, c.rating, NULL AS durationSec FROM series c " +
            "WHERE c.playlistId = :playlistId AND c.rowid > :afterRowId AND $EXCLUDE_RESTRICTED " +
            "ORDER BY c.rowid LIMIT :limit",
    )
    abstract suspend fun seriesSourcePage(playlistId: Long, afterRowId: Long, limit: Int): List<VodSourceRow>

    @Query(
        "SELECT c.rowid AS rowId, c.id, c.playlistId, substr(c.categoryId, 1, 512) AS categoryId, " +
            "substr(c.name, 1, 2048) AS name, substr(c.year, 1, 32) AS year, substr(c.tmdbId, 1, 128) AS tmdbId, " +
            "c.sortIndex, substr(c.posterUrl, 1, 4096) AS posterUrl, c.rating, NULL AS durationSec FROM series c " +
            "WHERE c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED",
    )
    abstract suspend fun seriesSources(playlistId: Long): List<VodSourceRow>

    @Query(
        "SELECT c.id, c.playlistId, c.type, c.name, c.sortIndex FROM categories c WHERE c.playlistId = :playlistId " +
            "AND c.id NOT IN (SELECT categoryId FROM category_prefs WHERE hidden = 1 OR locked = 1)",
    )
    abstract suspend fun categorySources(playlistId: Long): List<CategorySourceRow>

    @Query(
        "SELECT substr(p.channelKey, 1, 512) AS channelKey, p.startMs, " +
            "substr(p.title, 1, 2048) AS title FROM epg_programs p " +
            "WHERE p.playlistId = :playlistId AND p.startMs >= :fromMs " +
            "AND p.startMs < :toMs AND $ACTIVE_EPG ORDER BY p.channelKey, p.startMs",
    )
    abstract suspend fun epgSlots(playlistId: Long, fromMs: Long, toMs: Long): List<EpgSlot>

    /** Quellen eines zusammengeführten Eintrags (beste Qualität zuerst – die Rangfolge beim Abspielen verfeinert das). */
    @Query(
        "SELECT itemId, playlistId, qualityRank, language, durationSec FROM combined_links WHERE combinedId = :combinedId " +
            "ORDER BY qualityRank, itemId",
    )
    abstract suspend fun sources(combinedId: String): List<LinkedSource>

    /** Zusammengeführter Eintrag, zu dem ein Original gehört (für übergreifenden Fortschritt und Favoriten). */
    @Query("SELECT combinedId FROM combined_links WHERE itemId = :itemId LIMIT 1")
    abstract suspend fun combinedFor(itemId: String): String?

    @Query("SELECT COUNT(*) FROM combined_links")
    abstract suspend fun linkCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertLinks(links: List<CombinedLinkEntity>)

    @Query("DELETE FROM combined_links WHERE type = :type")
    abstract suspend fun deleteLinks(type: String)

    @Query("DELETE FROM combined_links WHERE combinedId IN (:combinedIds)")
    abstract suspend fun deleteLinksOf(combinedIds: List<String>)

    @Query("DELETE FROM combined_links")
    abstract suspend fun deleteAllLinks()

    /** Alle Quellen einer Art in einem Schritt austauschen (die Liste ist nie halb leer). */
    @Transaction
    open suspend fun replaceLinks(type: String, links: List<CombinedLinkEntity>) {
        deleteLinks(type)
        links.chunked(CHUNK).forEach { insertLinks(it) }
    }

    @Query("SELECT * FROM combined_rules")
    abstract suspend fun rules(): List<CombinedRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putRule(rule: CombinedRuleEntity)

    @Query("DELETE FROM combined_rules")
    abstract suspend fun deleteAllRules(): Int

    @Query("DELETE FROM combined_rules WHERE type = :type AND keyA = :keyA AND keyB = :keyB")
    abstract suspend fun deleteRule(type: String, keyA: String, keyB: String)

    /** Favoriten der Originale auf die zusammengeführten Einträge übertragen (fehlende ergänzen, nichts löschen). */
    @Query(
        "INSERT OR IGNORE INTO favorites (`key`, type, itemId, addedAtMs) " +
            "SELECT f.type || ':' || l.combinedId, f.type, l.combinedId, MAX(f.addedAtMs) FROM favorites f " +
            "INNER JOIN combined_links l ON l.itemId = f.itemId WHERE l.type = :type GROUP BY l.combinedId",
    )
    abstract suspend fun inheritFavorites(type: String)

    @Query(
        "SELECT l.combinedId, p.positionMs, p.durationMs, p.watched, p.updatedAtMs FROM progress p " +
            "INNER JOIN combined_links l ON l.itemId = substr(p.`key`, length(:prefix) + 1) " +
            "WHERE p.`key` LIKE :prefix || '%' AND l.type = :type",
    )
    abstract suspend fun linkedProgress(prefix: String, type: String): List<LinkedProgress>

    /** Folgen und Zuordnungen von Serien, die es in „Alle Zugänge“ nicht mehr gibt. */
    @Query("DELETE FROM episodes WHERE playlistId = -2 AND seriesId NOT IN (SELECT id FROM series WHERE playlistId = -2)")
    abstract suspend fun deleteOrphanEpisodes()

    @Query("DELETE FROM combined_links WHERE type = 'EPISODE' AND combinedId NOT IN (SELECT id FROM episodes WHERE playlistId = -2)")
    abstract suspend fun deleteOrphanEpisodeLinks()

    private companion object {
        const val CHUNK = 500
    }
}
