package com.mg.player.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.MovieRow

private const val MOVIE_SELECT =
    "SELECT c.*, t.rating AS tmdbRating, mp.positionMs AS progressPos, mp.durationMs AS progressDur, " +
        "mp.watched AS progressWatched FROM movies c LEFT JOIN tmdb_cache t ON t.itemKey = 'movie:' || c.id " +
        "LEFT JOIN progress mp ON mp.`key` = 'movie:' || c.id"

@Dao
interface VodDao {

    @Query("$MOVIE_SELECT WHERE c.playlistId = :playlistId AND c.categoryId = :categoryId ORDER BY c.sortIndex LIMIT :limit OFFSET :offset")
    suspend fun pageByCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<MovieRow>

    @Query("$MOVIE_SELECT WHERE c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED ORDER BY c.sortIndex LIMIT :limit OFFSET :offset")
    suspend fun pageAll(playlistId: Long, limit: Int, offset: Int): List<MovieRow>

    // A–Z (Einstellung „Reihenfolge in Gruppen“): eigene Abfragen, damit die Anbieter-Reihenfolge ihren Index behält.
    @Query("$MOVIE_SELECT WHERE c.playlistId = :playlistId AND c.categoryId = :categoryId ORDER BY $ALPHA_ORDER LIMIT :limit OFFSET :offset")
    suspend fun pageByCategoryAlpha(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<MovieRow>

    @Query("$MOVIE_SELECT WHERE c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED ORDER BY $ALPHA_ORDER LIMIT :limit OFFSET :offset")
    suspend fun pageAllAlpha(playlistId: Long, limit: Int, offset: Int): List<MovieRow>

    @Query(
        "$MOVIE_SELECT LEFT JOIN favorites f ON f.itemId = c.id AND f.type = 'MOVIE' " +
            "WHERE $FAVORITE_MOVIES $FAVORITE_ORDER LIMIT :limit OFFSET :offset",
    )
    suspend fun pageFavorites(playlistId: Long, limit: Int, offset: Int): List<MovieRow>

    @Query(
        // Vom (kleinen) Verlauf aus über den Primärschlüssel der Filme – nicht über alle Filme.
        "SELECT c.*, t.rating AS tmdbRating, pr.positionMs AS progressPos, pr.durationMs AS progressDur, pr.watched AS progressWatched " +
            "FROM progress pr INNER JOIN movies c ON c.id = substr(pr.`key`, 7) LEFT JOIN tmdb_cache t ON t.itemKey = 'movie:' || c.id " +
            "WHERE pr.`key` LIKE 'movie:%' AND c.playlistId = :playlistId AND pr.watched = 0 AND pr.positionMs > 0 " +
            "AND $NOT_LOCKED " +
            "ORDER BY pr.updatedAtMs DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageContinue(playlistId: Long, limit: Int, offset: Int): List<MovieRow>

    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    suspend fun get(id: String): MovieEntity?

    @Query("UPDATE movies SET streamUrl = :url WHERE id = :id")
    suspend fun updateStreamUrl(id: String, url: String)

    @Query(
        "UPDATE movies SET plot = :plot, backdropUrl = :backdropUrl, durationSec = :durationSec, " +
            "year = COALESCE(:year, year), rating = COALESCE(:rating, rating), " +
            "posterUrl = COALESCE(posterUrl, :posterUrl), tmdbId = COALESCE(:tmdbId, tmdbId), detailsLoaded = 1 WHERE id = :id",
    )
    suspend fun updateDetails(
        id: String,
        plot: String?,
        backdropUrl: String?,
        durationSec: Int?,
        year: String?,
        rating: Float?,
        posterUrl: String?,
        tmdbId: String?,
    )

    @Query(
        "SELECT * FROM movies c WHERE c.playlistId = :playlistId AND c.name LIKE '%' || :query || '%' " +
            "AND $EXCLUDE_RESTRICTED ORDER BY c.sortIndex LIMIT :limit",
    )
    suspend fun search(playlistId: Long, query: String, limit: Int): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM movies WHERE playlistId = :playlistId AND generation != :generation")
    suspend fun deleteStale(playlistId: Long, generation: Long)

    @Query("SELECT COUNT(*) FROM movies WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM movies WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun countGeneration(playlistId: Long, generation: Long): Int
}
