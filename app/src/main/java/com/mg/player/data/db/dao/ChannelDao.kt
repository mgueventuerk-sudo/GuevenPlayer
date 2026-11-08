package com.mg.player.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomWarnings
import androidx.room.Transaction
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.ChannelMatchRow
import com.mg.player.data.db.entity.ChannelWithNow

/** "Läuft gerade" für Einzelabfragen (ein Sender). Listen nutzen [NO_NOW] und laden das gebündelt nach. */
private const val EPG_PLAYLIST = "(CASE WHEN c.epgPlaylistId != 0 THEN c.epgPlaylistId ELSE c.playlistId END)"
private const val NOW_WHERE =
    "p.playlistId = $EPG_PLAYLIST AND p.channelKey = c.epgKey AND p.startMs <= :now AND p.endMs > :now AND $ACTIVE_EPG"
private const val NOW_COLUMNS =
    "(SELECT p.title FROM epg_programs p WHERE $NOW_WHERE ORDER BY p.startMs DESC LIMIT 1) AS nowTitle, " +
        "(SELECT p.startMs FROM epg_programs p WHERE $NOW_WHERE ORDER BY p.startMs DESC LIMIT 1) AS nowStart, " +
        "(SELECT p.endMs FROM epg_programs p WHERE $NOW_WHERE ORDER BY p.startMs DESC LIMIT 1) AS nowEnd"

private const val NO_NOW = "NULL AS nowTitle, NULL AS nowStart, NULL AS nowEnd"

/** Ausgeblendete und gesperrte Kategorien tauchen in "Alle", Suche und Zapping nicht auf. */
/** Alphabetische Reihenfolge für Filme und Serien (Groß/Klein egal), gleiche Namen in Anbieter-Reihenfolge. */
internal const val ALPHA_ORDER = "c.name COLLATE NOCASE, c.sortIndex"

internal const val EXCLUDE_RESTRICTED =
    "c.categoryId NOT IN (SELECT categoryId FROM category_prefs WHERE hidden = 1 OR locked = 1)"

/** Einzeln markierte Favoriten zuerst (neueste oben). Angeheftete Gruppen erweitern diese Listen nicht. */
internal const val FAVORITE_ORDER = "ORDER BY (f.itemId IS NULL), f.addedAtMs DESC, c.sortIndex"

/*
 * Favoriten-Zeilen über den Primärschlüssel der explizit markierten Einträge statt über die ganze Tabelle.
 */

/**
 * Gesperrte Gruppen (Kindersicherung) bleiben auch in Favoriten, "Zuletzt gesehen" und "Weiterschauen" außen vor –
 * sonst ließen sich gesperrte Inhalte darüber ohne PIN öffnen. Zugang gibt es über die gesperrte Gruppe selbst.
 */
internal const val LOCKED_GROUPS = "(SELECT categoryId FROM category_prefs WHERE locked = 1)"
internal const val NOT_LOCKED = "c.categoryId NOT IN $LOCKED_GROUPS"
internal const val FAVORITE_CHANNELS =
    "c.rowid IN (SELECT rowid FROM channels WHERE id IN (SELECT itemId FROM favorites WHERE type = 'LIVE') AND playlistId = :playlistId " +
        "AND categoryId NOT IN $LOCKED_GROUPS)"
internal const val FAVORITE_MOVIES =
    "c.rowid IN (SELECT rowid FROM movies WHERE id IN (SELECT itemId FROM favorites WHERE type = 'MOVIE') AND playlistId = :playlistId " +
        "AND categoryId NOT IN $LOCKED_GROUPS)"
internal const val FAVORITE_SERIES =
    "c.rowid IN (SELECT rowid FROM series WHERE id IN (SELECT itemId FROM favorites WHERE type = 'SERIES') AND playlistId = :playlistId " +
        "AND categoryId NOT IN $LOCKED_GROUPS)"

/**
 * Sender-Gruppierung: Varianten mit gleichem groupKey (z. B. "ZDF UHD", "ZDF FHD", "ZDF HD") erscheinen als
 * eine Kachel. Es gibt genau ein MIN()-Aggregat – SQLite liefert dann die übrigen Spalten aus genau der Zeile
 * mit dem kleinsten Wert, also der besten Qualität (bei Gleichstand die zuerst gelistete).
 */
private const val GROUP_KEY = "CASE WHEN c.groupKey = '' THEN c.id ELSE c.groupKey END"

/** Verlauf je Sender (Gruppe): die zuletzt gesehene Quelle und wann (SQLite nimmt bei MAX die Zeile des Maximums). */
private const val RECENT_PER_CHANNEL =
    "SELECT h.id AS rid, MAX(pr.updatedAtMs) AS at FROM progress pr INNER JOIN channels h ON h.id = substr(pr.`key`, 6) " +
        "WHERE pr.`key` LIKE 'live:%' AND h.playlistId = :playlistId GROUP BY CASE WHEN h.groupKey = '' THEN h.id ELSE h.groupKey END"
private const val BEST_FAVORITE = "MIN((f.itemId IS NULL) * 1000000000000 + c.qualityRank * 1000000 + c.sortIndex) AS best"

@Dao
abstract class ChannelDao {

    // Listen-Seiten ohne "läuft gerade": Das ermittelt ChannelPagingSource gebündelt für die 60 Sender
    // einer Seite. Vorher liefen pro Gruppe drei Unterabfragen – bei 50.000 Sendern bei jeder Seite neu.
    // Die Gruppierung (beste Variante je Sender) ist in channel_flags vorberechnet: Jede Seite ist ein
    // Index-Zugriff statt eines GROUP BY über alle Sender.
    @Query(
        "SELECT c.*, $NO_NOW, g.variantsCat AS variants FROM channel_flags g INNER JOIN channels c ON c.id = g.channelId " +
            "WHERE g.playlistId = :playlistId AND g.categoryId = :categoryId AND g.primaryCat = 1 ORDER BY g.sortIndex, g.rowid " +
            "LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun pageByCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<ChannelWithNow>

    // EXCLUDE_RESTRICTED zusätzlich: Eine gerade ausgeblendete Gruppe verschwindet sofort, nicht erst nach dem Neuaufbau.
    @Query(
        "SELECT c.*, $NO_NOW, g.variantsAll AS variants FROM channel_flags g INNER JOIN channels c ON c.id = g.channelId " +
            "WHERE g.playlistId = :playlistId AND g.primaryAll = 1 AND $EXCLUDE_RESTRICTED ORDER BY g.sortIndex, g.rowid " +
            "LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun pageAll(playlistId: Long, limit: Int, offset: Int): List<ChannelWithNow>

    // Favoriten sind wenige: hier bleibt das GROUP BY (die Favoriten-Reihenfolge hängt am Favoriten selbst).
    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
    @Query(
        "SELECT c.*, $NO_NOW, COUNT(*) AS variants, $BEST_FAVORITE FROM channels c " +
            "LEFT JOIN favorites f ON f.itemId = c.id AND f.type = 'LIVE' " +
            "WHERE $FAVORITE_CHANNELS GROUP BY $GROUP_KEY $FAVORITE_ORDER LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun pageFavorites(playlistId: Long, limit: Int, offset: Int): List<ChannelWithNow>

    @Query(
        // Vom (kleinen) Verlauf aus über den Primärschlüssel der Sender – nicht umgekehrt über alle Sender.
        // Je Sender ein Eintrag (zuletzt gesehene Quelle): Sonst stand ProSieben HD/UHD/4K einzeln in der Liste und
        // Umschalten lief durch die Qualitäten statt zum nächsten Sender.
        "SELECT c.*, $NO_NOW, 1 AS variants FROM channels c INNER JOIN ($RECENT_PER_CHANNEL) r ON c.id = r.rid " +
            "WHERE $NOT_LOCKED ORDER BY r.at DESC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun pageRecent(playlistId: Long, limit: Int, offset: Int): List<ChannelWithNow>

    @Query("SELECT c.*, $NO_NOW, 1 AS variants FROM channels c WHERE c.id IN (:ids) AND c.playlistId = :playlistId AND $NOT_LOCKED")
    abstract suspend fun byIds(playlistId: Long, ids: List<String>): List<ChannelWithNow>

    @Query("SELECT c.*, $NOW_COLUMNS, 1 AS variants FROM channels c WHERE c.id = :id LIMIT 1")
    abstract suspend fun get(id: String, now: Long): ChannelWithNow?

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    abstract suspend fun entity(id: String): ChannelEntity?

    @Query("UPDATE channels SET streamUrl = :url WHERE id = :id")
    abstract suspend fun updateStreamUrl(id: String, url: String)

    @Query("UPDATE channels SET catchupSource = :source WHERE id = :id")
    abstract suspend fun updateCatchupSource(id: String, source: String?)

    @Query("SELECT * FROM channels WHERE id IN (:ids)")
    abstract suspend fun entities(ids: List<String>): List<ChannelEntity>

    /** Generation des letzten Imports (neue Sender aus der Kontrolle vor Anstoß bekommen dieselbe). */
    @Query("SELECT MAX(generation) FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun latestGeneration(playlistId: Long): Long?

    @Query("SELECT MAX(sortIndex) FROM channels WHERE playlistId = :playlistId AND categoryId = :categoryId")
    abstract suspend fun lastSortIndex(playlistId: Long, categoryId: String): Int?

    /**
     * Derselbe Sender in anderen Zugängen (gleiche EPG-ID oder gleicher bereinigter Name), beste Qualität zuerst.
     * Die EPG-ID wird über den indizierten epgKey vorsortiert (beim Import = epgChannelId in Kleinschrift) und dann
     * exakt verglichen; bis 3.23 lief die Abfrage ohne Index durch alle Sender aller Zugänge. Vom EPG umgeschlüsselte
     * Sender fallen dabei heraus – im Zweifel kein Treffer statt eines falschen.
     */
    @Query(
        "SELECT * FROM channels c WHERE c.playlistId != :playlistId AND " +
            "((:epgId != '' AND c.epgKey = lower(:epgId) AND c.epgChannelId = :epgId) OR " +
            "(:normalized != '' AND c.normalizedName = :normalized)) " +
            "AND $EXCLUDE_RESTRICTED ORDER BY c.qualityRank, c.sortIndex LIMIT 20",
    )
    abstract suspend fun counterparts(playlistId: Long, epgId: String, normalized: String): List<ChannelEntity>

    /** Quellen einer Gruppe zuschlagen ([com.mg.player.data.combined.VariantUnifier]). */
    @Query("UPDATE channels SET groupKey = :groupKey WHERE id IN (:ids)")
    abstract suspend fun setGroupKey(ids: List<String>, groupKey: String)

    /** Alle Stream-Varianten eines Senders, beste Qualität zuerst (für Auto-Failover und Quellen-Wahl). */
    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND groupKey = :groupKey AND groupKey != '' " +
            "ORDER BY qualityRank, sortIndex",
    )
    abstract suspend fun variants(playlistId: Long, groupKey: String): List<ChannelEntity>

    // Für das Zappen: pro Sender-Gruppe nur die beste Variante, in Listenreihenfolge.
    @Query(
        "SELECT g.channelId FROM channel_flags g WHERE g.playlistId = :playlistId AND g.categoryId = :categoryId " +
            "AND g.primaryCat = 1 ORDER BY g.sortIndex, g.rowid",
    )
    abstract suspend fun idsByCategory(playlistId: Long, categoryId: String): List<String>

    @Query(
        "SELECT g.channelId FROM channel_flags g WHERE g.playlistId = :playlistId AND g.primaryAll = 1 " +
            "AND g.categoryId NOT IN (SELECT categoryId FROM category_prefs WHERE hidden = 1 OR locked = 1) ORDER BY g.sortIndex, g.rowid",
    )
    abstract suspend fun idsAll(playlistId: Long): List<String>

    @Query(
        "SELECT COALESCE((SELECT (SELECT COUNT(*) FROM channel_flags g " +
            "WHERE g.playlistId = :playlistId AND g.categoryId = :categoryId AND g.primaryCat = 1 " +
            "AND g.sortIndex <= target.sortIndex AND (g.sortIndex < target.sortIndex OR g.rowid < target.rowid)) " +
            "FROM channel_flags target WHERE target.channelId = :channelId AND target.playlistId = :playlistId " +
            "AND target.categoryId = :categoryId AND target.primaryCat = 1), -1)",
    )
    abstract suspend fun positionByCategory(playlistId: Long, categoryId: String, channelId: String): Int

    @Query(
        "SELECT COALESCE((SELECT (SELECT COUNT(*) FROM channel_flags g " +
            "WHERE g.playlistId = :playlistId AND g.primaryAll = 1 AND g.categoryId NOT IN " +
            "(SELECT categoryId FROM category_prefs WHERE hidden = 1 OR locked = 1) " +
            "AND g.sortIndex <= target.sortIndex AND (g.sortIndex < target.sortIndex OR g.rowid < target.rowid)) " +
            "FROM channel_flags target WHERE target.channelId = :channelId AND target.playlistId = :playlistId " +
            "AND target.primaryAll = 1 AND target.categoryId NOT IN " +
            "(SELECT categoryId FROM category_prefs WHERE hidden = 1 OR locked = 1)), -1)",
    )
    abstract suspend fun positionAll(playlistId: Long, channelId: String): Int

    @Query(
        "SELECT id FROM (SELECT c.id AS id, (f.itemId IS NULL) AS notFavorite, f.addedAtMs AS addedAt, " +
            "c.sortIndex AS sortIndex, $BEST_FAVORITE FROM channels c LEFT JOIN favorites f ON f.itemId = c.id AND f.type = 'LIVE' " +
            "WHERE $FAVORITE_CHANNELS GROUP BY $GROUP_KEY) " +
            "ORDER BY notFavorite, addedAt DESC, sortIndex",
    )
    abstract suspend fun idsFavorites(playlistId: Long): List<String>

    @Query("SELECT c.id FROM channels c INNER JOIN ($RECENT_PER_CHANNEL) r ON c.id = r.rid WHERE $NOT_LOCKED ORDER BY r.at DESC")
    abstract suspend fun idsRecent(playlistId: Long): List<String>

    @Query("SELECT id, epgChannelId, normalizedName, epgKey FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun matchRows(playlistId: Long): List<ChannelMatchRow>

    /** Nur Sender aus sichtbaren Gruppen: Für ausgeblendete Länder wird kein EPG gespeichert. */
    @Query(
        "SELECT id, epgChannelId, normalizedName, epgKey FROM channels c WHERE c.playlistId = :playlistId " +
            "AND c.categoryId NOT IN (SELECT categoryId FROM category_prefs WHERE hidden = 1)",
    )
    abstract suspend fun visibleMatchRows(playlistId: Long): List<ChannelMatchRow>

    @Query("UPDATE channels SET epgKey = :epgKey WHERE id = :id")
    abstract suspend fun updateEpgKey(id: String, epgKey: String)

    @Transaction
    open suspend fun updateEpgKeys(keys: Map<String, String>) {
        keys.forEach { (id, key) -> updateEpgKey(id, key) }
    }

    @Query(
        "SELECT * FROM channels c WHERE c.playlistId = :playlistId AND c.name LIKE '%' || :query || '%' " +
            "AND $EXCLUDE_RESTRICTED ORDER BY c.sortIndex LIMIT :limit",
    )
    abstract suspend fun search(playlistId: Long, query: String, limit: Int): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND generation != :generation")
    abstract suspend fun deleteStale(playlistId: Long, generation: Long)

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun count(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND generation = :generation")
    abstract suspend fun countGeneration(playlistId: Long, generation: Long): Int
}
