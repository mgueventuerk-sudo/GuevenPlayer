package com.mg.player.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

private const val RESTRICTED = "SELECT categoryId FROM category_prefs WHERE hidden = 1 OR locked = 1"

/** Beste sichtbare Variante der Gruppe (gleiche Regel wie früher MIN(qualityRank, sortIndex) im GROUP BY). */
private const val BEST_VISIBLE =
    "(SELECT v.id FROM channels v WHERE v.playlistId = c.playlistId AND v.groupKey = c.groupKey " +
        "AND v.categoryId NOT IN ($RESTRICTED) ORDER BY v.qualityRank, v.sortIndex LIMIT 1)"
private const val COUNT_VISIBLE =
    "(SELECT COUNT(*) FROM channels v WHERE v.playlistId = c.playlistId AND v.groupKey = c.groupKey " +
        "AND v.categoryId NOT IN ($RESTRICTED))"
private const val BEST_IN_CATEGORY =
    "(SELECT v.id FROM channels v WHERE v.playlistId = c.playlistId AND v.groupKey = c.groupKey " +
        "AND v.categoryId = c.categoryId ORDER BY v.qualityRank, v.sortIndex LIMIT 1)"
private const val COUNT_IN_CATEGORY =
    "(SELECT COUNT(*) FROM channels v WHERE v.playlistId = c.playlistId AND v.groupKey = c.groupKey AND v.categoryId = c.categoryId)"

/** Pflege der vorberechneten Sender-Gruppierung (Tabelle channel_flags). */
@Dao
abstract class ChannelFlagsDao {

    @Query("DELETE FROM channel_flags WHERE playlistId = :playlistId")
    abstract suspend fun clear(playlistId: Long)

    @Query(
        "INSERT INTO channel_flags (channelId, playlistId, categoryId, sortIndex, primaryAll, variantsAll, primaryCat, variantsCat) " +
            "SELECT c.id, c.playlistId, c.categoryId, c.sortIndex, " +
            "CASE WHEN c.categoryId IN ($RESTRICTED) THEN 0 WHEN c.groupKey = '' THEN 1 WHEN c.id = $BEST_VISIBLE THEN 1 ELSE 0 END, " +
            "CASE WHEN c.groupKey = '' THEN 1 ELSE $COUNT_VISIBLE END, " +
            "CASE WHEN c.groupKey = '' THEN 1 WHEN c.id = $BEST_IN_CATEGORY THEN 1 ELSE 0 END, " +
            "CASE WHEN c.groupKey = '' THEN 1 ELSE $COUNT_IN_CATEGORY END " +
            "FROM channels c WHERE c.playlistId = :playlistId",
    )
    abstract suspend fun insertFor(playlistId: Long)

    /** Neuaufbau in einer Transaktion: Die Listen sehen entweder die alte oder die neue Gruppierung. */
    @Transaction
    open suspend fun rebuild(playlistId: Long) {
        clear(playlistId)
        insertFor(playlistId)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM channel_flags WHERE playlistId = :playlistId LIMIT 1)")
    abstract suspend fun hasFlags(playlistId: Long): Boolean
}
