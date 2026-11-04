package com.mg.player.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.db.entity.CategoryPrefEntity
import com.mg.player.data.db.entity.EpgActiveEntity
import com.mg.player.data.db.entity.EpgProgramEntity
import com.mg.player.data.db.entity.FavoriteEntity
import com.mg.player.data.db.entity.IntroProfileEntity
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.db.entity.ProgressEntity
import com.mg.player.data.db.entity.TmdbCacheEntity
import com.mg.player.domain.model.FavoriteName
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET headerEpgUrl = :url WHERE id = :id")
    suspend fun updateHeaderEpg(id: Long, url: String)

    @Query("UPDATE playlists SET lastUpdatedMs = :timeMs WHERE id = :id")
    suspend fun updateLastUpdated(id: Long, timeMs: Long)

    @Query("UPDATE playlists SET serverTimezone = :timezone WHERE id = :id")
    suspend fun updateTimezone(id: Long, timezone: String)

    @Query("UPDATE playlists SET allowInsecureHttp = :allowed WHERE id = :id")
    suspend fun setAllowInsecureHttp(id: Long, allowed: Boolean)

    @Query("UPDATE playlists SET autoRefreshDays = :days WHERE id = :id")
    suspend fun setAutoRefreshDays(id: Long, days: Int)

    @Query("UPDATE playlists SET epgShiftHours = :hours WHERE id = :id")
    suspend fun setEpgShiftHours(id: Long, hours: Int)

    @Query("UPDATE playlists SET epgShiftHours = :hours")
    suspend fun setEpgShiftHoursForAll(hours: Int)

    @Query("SELECT epgShiftHours FROM playlists WHERE id = :id")
    suspend fun epgShiftHours(id: Long): Int?

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Kategorie inkl. persönlicher Einstellungen. */
data class CategoryWithPref(@Embedded val category: CategoryEntity, val hidden: Boolean, val locked: Boolean, val pinned: Boolean, val position: Int?)

private const val CATEGORY_SELECT =
    "SELECT c.*, COALESCE(p.hidden, 0) AS hidden, COALESCE(p.locked, 0) AS locked, COALESCE(p.pinned, 0) AS pinned, " +
        "p.position AS position FROM categories c LEFT JOIN category_prefs p ON p.categoryId = c.id"

/** Angeheftete Gruppen oben, darin und darunter die eigene Reihenfolge; neue Gruppen des Anbieters hinten an. */
private const val CATEGORY_ORDER = "ORDER BY pinned DESC, (p.position IS NULL), p.position, c.sortIndex"

@Dao
interface CategoryDao {
    @Query("$CATEGORY_SELECT WHERE c.playlistId = :playlistId AND c.type = :type AND COALESCE(p.hidden, 0) = 0 $CATEGORY_ORDER")
    fun observeVisible(playlistId: Long, type: String): Flow<List<CategoryWithPref>>

    @Query("$CATEGORY_SELECT WHERE c.playlistId = :playlistId AND c.type = :type $CATEGORY_ORDER")
    fun observeAll(playlistId: Long, type: String): Flow<List<CategoryWithPref>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId")
    suspend fun allForPlaylist(playlistId: Long): List<CategoryEntity>

    /** Sichtbare, nicht gesperrte Live-Gruppen in der Reihenfolge des Anbieters. */
    @Query(
        "SELECT * FROM categories WHERE playlistId = :playlistId AND type = 'LIVE' " +
            "AND id NOT IN (SELECT categoryId FROM category_prefs WHERE hidden = 1 OR locked = 1) ORDER BY sortIndex",
    )
    suspend fun openLiveCategories(playlistId: Long): List<CategoryEntity>

    @Query("DELETE FROM categories WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId AND generation != :generation")
    suspend fun deleteStale(playlistId: Long, generation: Long)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId AND type = :type AND generation != :generation")
    suspend fun deleteStaleOfType(playlistId: Long, type: String, generation: Long)
}

@Dao
interface CategoryPrefDao {
    @Query("SELECT * FROM category_prefs WHERE categoryId = :categoryId LIMIT 1")
    suspend fun get(categoryId: String): CategoryPrefEntity?

    @Query("SELECT * FROM category_prefs")
    suspend fun all(): List<CategoryPrefEntity>

    @Upsert
    suspend fun upsert(pref: CategoryPrefEntity)

    @Upsert
    suspend fun upsertAll(prefs: List<CategoryPrefEntity>)

    @Query("SELECT * FROM category_prefs WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: Long): List<CategoryPrefEntity>

    @Query("DELETE FROM category_prefs WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM category_prefs")
    suspend fun deleteAll()
}

/**
 * Nur die sichtbare EPG-Generation des Zugangs (fehlt der Eintrag in epg_active, gilt 0). Für Abfragen mit Alias `p`.
 * Ein laufender Import schreibt unter einer neuen Generation und bleibt bis zum Umschalten unsichtbar.
 */
internal const val ACTIVE_EPG = "p.generation = COALESCE((SELECT a.generation FROM epg_active a WHERE a.playlistId = p.playlistId), 0)"

@Dao
interface EpgDao {
    @Insert
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)

    /** Sichtbare Generation (null = noch nie umgeschaltet, also 0). */
    @Query("SELECT generation FROM epg_active WHERE playlistId = :playlistId")
    suspend fun activeGeneration(playlistId: Long): Long?

    @Upsert
    suspend fun setActive(active: EpgActiveEntity)

    @Query("SELECT COUNT(*) FROM epg_programs WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun countGeneration(playlistId: Long, generation: Long): Int

    /** Bereich der Zeilennummern aller nicht sichtbaren Generationen (zum Löschen in Blöcken). */
    @Query("SELECT MIN(id) AS fromId, MAX(id) AS toId FROM epg_programs WHERE playlistId = :playlistId AND generation != :keep")
    suspend fun staleRange(playlistId: Long, keep: Long): IdRange

    /** Ein Block alter Sendungen (über den Primärschlüssel – schnell, kurze Sperre). */
    @Query("DELETE FROM epg_programs WHERE id BETWEEN :fromId AND :toId AND playlistId = :playlistId AND generation != :keep")
    suspend fun deleteStaleBlock(playlistId: Long, keep: Long, fromId: Long, toId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM epg_programs p WHERE p.playlistId = :playlistId AND $ACTIVE_EPG LIMIT 1)")
    suspend fun hasPrograms(playlistId: Long): Boolean

    @Query("DELETE FROM epg_programs")
    suspend fun deleteAll()

    @Query("DELETE FROM epg_active")
    suspend fun deleteAllActive()

    @Query("DELETE FROM epg_programs WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM epg_active WHERE playlistId = :playlistId")
    suspend fun deleteActive(playlistId: Long)

    @Query(
        "SELECT * FROM epg_programs p WHERE p.playlistId = :playlistId AND p.channelKey = :channelKey " +
            "AND p.endMs > :fromMs AND p.startMs < :toMs AND $ACTIVE_EPG ORDER BY p.startMs LIMIT 200",
    )
    suspend fun programs(playlistId: Long, channelKey: String, fromMs: Long, toMs: Long): List<EpgProgramEntity>

    /** Laufende Sendungen für eine ganze Listen-Seite auf einmal (statt drei Unterabfragen je Sender). */
    @Query(
        "SELECT * FROM epg_programs p WHERE p.playlistId = :playlistId AND p.channelKey IN (:channelKeys) " +
            "AND p.startMs <= :now AND p.endMs > :now AND $ACTIVE_EPG",
    )
    suspend fun nowPlaying(playlistId: Long, channelKeys: List<String>, now: Long): List<EpgProgramEntity>

    /** Kommende Sendungen mit [pattern] im Titel samt einem passenden Sender („Automatisch aufnehmen“), ohne gesperrte Gruppen. */
    @Query(
        "SELECT p.title, p.description, p.startMs, p.endMs, MIN(c.id) AS channelId FROM epg_programs p " +
            "INNER JOIN channels c ON c.playlistId = p.playlistId AND c.epgKey = p.channelKey " +
            "WHERE p.playlistId = :playlistId AND p.startMs BETWEEN :fromMs AND :toMs AND p.title LIKE :pattern AND $ACTIVE_EPG " +
            "AND c.categoryId NOT IN (SELECT categoryId FROM category_prefs WHERE locked = 1) " +
            "GROUP BY p.id ORDER BY p.startMs LIMIT 60",
    )
    suspend fun upcomingMatching(playlistId: Long, pattern: String, fromMs: Long, toMs: Long): List<EpgMatchRow>
}

data class IdRange(val fromId: Long?, val toId: Long?)

data class EpgMatchRow(val title: String, val description: String?, val startMs: Long, val endMs: Long, val channelId: String)

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE seriesId = :seriesId")
    fun observeSeries(seriesId: String): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE seriesId = :seriesId ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun lastInSeries(seriesId: String): ProgressEntity?

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress")
    suspend fun deleteAll()
}

@Dao
interface FavoriteDao {
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND itemId = :itemId)")
    fun observeExists(type: String, itemId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND itemId = :itemId)")
    suspend fun exists(type: String, itemId: String): Boolean

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE type = :type AND itemId = :itemId")
    suspend fun delete(type: String, itemId: String)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()

    /** Einzeln gemerkte Favoriten eines Zugangs mit Namen, in Anzeigereihenfolge (neueste bzw. oben einsortierte zuerst). */
    @Query(
        "SELECT f.itemId AS id, COALESCE(c.name, m.name, s.name) AS name FROM favorites f " +
            "LEFT JOIN channels c ON f.type = 'LIVE' AND c.id = f.itemId " +
            "LEFT JOIN movies m ON f.type = 'MOVIE' AND m.id = f.itemId " +
            "LEFT JOIN series s ON f.type = 'SERIES' AND s.id = f.itemId " +
            "WHERE f.type = :type AND COALESCE(c.playlistId, m.playlistId, s.playlistId) = :playlistId ORDER BY f.addedAtMs DESC",
    )
    fun observeNamed(type: String, playlistId: Long): Flow<List<FavoriteName>>

    /** Die Reihenfolge der Favoriten hängt am Zeitstempel (neuester oben) – so lässt sie sich ohne Schema-Änderung setzen. */
    @Query("UPDATE favorites SET addedAtMs = :addedAtMs WHERE type = :type AND itemId = :itemId")
    suspend fun setAddedAt(type: String, itemId: String, addedAtMs: Long)
}

/** Favorit mit Anzeigenamen (Sender, Film oder Serie). */

@Dao
interface IntroProfileDao {
    @Query("SELECT * FROM intro_profiles WHERE seriesId = :seriesId LIMIT 1")
    suspend fun get(seriesId: String): IntroProfileEntity?

    @Query("SELECT * FROM intro_profiles")
    suspend fun all(): List<IntroProfileEntity>

    @Upsert
    suspend fun upsert(profile: IntroProfileEntity)

    @Query("DELETE FROM intro_profiles")
    suspend fun deleteAll()
}

@Dao
interface TmdbDao {
    @Query("SELECT * FROM tmdb_cache WHERE itemKey = :itemKey LIMIT 1")
    suspend fun get(itemKey: String): TmdbCacheEntity?

    @Query("SELECT * FROM tmdb_cache WHERE itemKey = :itemKey LIMIT 1")
    fun observe(itemKey: String): Flow<TmdbCacheEntity?>

    @Upsert
    suspend fun upsert(entry: TmdbCacheEntity)

    @Query("DELETE FROM tmdb_cache")
    suspend fun deleteAll()
}
