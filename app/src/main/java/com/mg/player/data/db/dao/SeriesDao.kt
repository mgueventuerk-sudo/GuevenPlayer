package com.mg.player.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mg.player.data.db.entity.EpisodeEntity
import com.mg.player.data.db.entity.SeriesEntity
import com.mg.player.data.db.entity.SeriesRow
import kotlinx.coroutines.flow.Flow

private const val SERIES_SELECT = "SELECT c.*, t.rating AS tmdbRating FROM series c LEFT JOIN tmdb_cache t ON t.itemKey = 'series:' || c.id"

@Dao
abstract class SeriesDao {

    @Query("$SERIES_SELECT WHERE c.playlistId = :playlistId AND c.categoryId = :categoryId ORDER BY c.sortIndex LIMIT :limit OFFSET :offset")
    abstract suspend fun pageByCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<SeriesRow>

    @Query("$SERIES_SELECT WHERE c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED ORDER BY c.sortIndex LIMIT :limit OFFSET :offset")
    abstract suspend fun pageAll(playlistId: Long, limit: Int, offset: Int): List<SeriesRow>

    // A–Z (Einstellung „Reihenfolge in Gruppen“): eigene Abfragen, damit die Anbieter-Reihenfolge ihren Index behält.
    @Query("$SERIES_SELECT WHERE c.playlistId = :playlistId AND c.categoryId = :categoryId ORDER BY $ALPHA_ORDER LIMIT :limit OFFSET :offset")
    abstract suspend fun pageByCategoryAlpha(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<SeriesRow>

    @Query("$SERIES_SELECT WHERE c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED ORDER BY $ALPHA_ORDER LIMIT :limit OFFSET :offset")
    abstract suspend fun pageAllAlpha(playlistId: Long, limit: Int, offset: Int): List<SeriesRow>

    @Query(
        "$SERIES_SELECT LEFT JOIN favorites f ON f.itemId = c.id AND f.type = 'SERIES' " +
            "WHERE $FAVORITE_SERIES $FAVORITE_ORDER LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun pageFavorites(playlistId: Long, limit: Int, offset: Int): List<SeriesRow>

    @Query(
        "$SERIES_SELECT INNER JOIN " +
            "(SELECT seriesId, MAX(updatedAtMs) AS lastAt FROM progress WHERE seriesId IS NOT NULL GROUP BY seriesId) pr " +
            "ON pr.seriesId = c.id WHERE c.playlistId = :playlistId AND $NOT_LOCKED " +
            "ORDER BY pr.lastAt DESC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun pageContinue(playlistId: Long, limit: Int, offset: Int): List<SeriesRow>

    @Query("SELECT * FROM series WHERE id = :id LIMIT 1")
    abstract suspend fun get(id: String): SeriesEntity?

    @Query(
        "SELECT * FROM series c WHERE c.playlistId = :playlistId AND c.name LIKE '%' || :query || '%' " +
            "AND $EXCLUDE_RESTRICTED ORDER BY c.sortIndex LIMIT :limit",
    )
    abstract suspend fun search(playlistId: Long, query: String, limit: Int): List<SeriesEntity>

    @Query(
        "UPDATE series SET plot = COALESCE(:plot, plot), backdropUrl = COALESCE(:backdropUrl, backdropUrl), " +
            "tmdbId = COALESCE(:tmdbId, tmdbId) WHERE id = :id",
    )
    abstract suspend fun updateInfo(id: String, plot: String?, backdropUrl: String?, tmdbId: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(series: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    abstract suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM series WHERE playlistId = :playlistId AND generation != :generation")
    abstract suspend fun deleteStale(playlistId: Long, generation: Long)

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :playlistId")
    abstract suspend fun count(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :playlistId AND generation = :generation")
    abstract suspend fun countGeneration(playlistId: Long, generation: Long): Int

    // --- Episoden ---

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, episode")
    abstract fun observeEpisodes(seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, episode")
    abstract suspend fun episodes(seriesId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    abstract suspend fun episode(id: String): EpisodeEntity?

    @Query("UPDATE episodes SET streamUrl = :url WHERE id = :id")
    abstract suspend fun updateEpisodeStreamUrl(id: String, url: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    abstract suspend fun deleteEpisodes(seriesId: String)

    @Query("DELETE FROM episodes WHERE playlistId = :playlistId")
    abstract suspend fun deleteEpisodesForPlaylist(playlistId: Long)

    /**
     * Veraltete Episoden nach einer Aktualisierung entfernen. Generation 0 = bei Xtream erst in der Detailansicht
     * nachgeladen: Die bleiben erhalten, solange ihre Serie noch existiert – vorher löschte jede Aktualisierung sie.
     */
    @Query(
        "DELETE FROM episodes WHERE playlistId = :playlistId AND (" +
            "seriesId NOT IN (SELECT id FROM series WHERE playlistId = :playlistId) OR " +
            "(generation != 0 AND generation != :generation))",
    )
    abstract suspend fun deleteStaleEpisodes(playlistId: Long, generation: Long)

    @Transaction
    open suspend fun replaceEpisodes(seriesId: String, episodes: List<EpisodeEntity>) {
        deleteEpisodes(seriesId)
        insertEpisodes(episodes)
    }
}
