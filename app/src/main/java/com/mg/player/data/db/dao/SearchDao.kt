package com.mg.player.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.SearchIndexEntity
import com.mg.player.data.db.entity.SeriesEntity

/**
 * Suche über den FTS4-Index `search_index` (Aufbau siehe SearchIndexer). Die Spalte `name` enthält nicht den
 * Anzeigenamen, sondern gefaltete Suchbegriffe (siehe SearchText) – angezeigt wird immer der Eintrag selbst.
 */
@Dao
interface SearchDao {

    /**
     * Eine Zeile, aus der Suchbegriffe erzeugt werden (Index-Aufbau in Blöcken entlang der Zeilennummer).
     * "+playlistId" verhindert, dass SQLite den Zugangs-Index nimmt und dann für jeden Block alle Zeilen sortiert.
     */
    data class IndexSource(val rowId: Long, val id: String, val name: String)

    @Query("DELETE FROM search_index WHERE playlistId = :playlistId")
    suspend fun clear(playlistId: String)

    /** Alle Index-Stände eines Zugangs ([plain] bzw. [pattern] „5.%“) außer [keep] löschen. */
    @Query("DELETE FROM search_index WHERE (playlistId = :plain OR playlistId LIKE :pattern) AND playlistId != :keep")
    suspend fun clearExcept(plain: String, pattern: String, keep: String)

    /** Einzelne Einträge entfernen (umbenannte Sender), danach neu einfügen. */
    @Query("DELETE FROM search_index WHERE playlistId = :playlistId AND itemId IN (:itemIds)")
    suspend fun deleteItems(playlistId: String, itemIds: List<String>)

    @Query("SELECT COUNT(*) FROM search_index WHERE playlistId = :playlistId")
    suspend fun indexedCount(playlistId: String): Int

    @Insert
    suspend fun insert(rows: List<SearchIndexEntity>)

    /**
     * Fingerabdruck des Bestands eines Zugangs – ohne Zeilen zu übertragen. Stimmt er mit dem Stand des Index
     * überein, ist ein Neuaufbau (auf dem Fire TV bis zu 103 s für 250 000 Einträge) unnötig.
     */
    @Query(
        "SELECT ($COUNTS_CHANNELS) || '|' || ($COUNTS_MOVIES) || '|' || ($COUNTS_SERIES)",
    )
    suspend fun fingerprint(playlistId: Long): String

    @Query("SELECT rowid AS rowId, id, name FROM channels WHERE +playlistId = :playlistId AND rowid > :afterRowId ORDER BY rowid LIMIT :limit")
    suspend fun channelSource(playlistId: Long, afterRowId: Long, limit: Int): List<IndexSource>

    @Query("SELECT rowid AS rowId, id, name FROM movies WHERE +playlistId = :playlistId AND rowid > :afterRowId ORDER BY rowid LIMIT :limit")
    suspend fun movieSource(playlistId: Long, afterRowId: Long, limit: Int): List<IndexSource>

    @Query("SELECT rowid AS rowId, id, name FROM series WHERE +playlistId = :playlistId AND rowid > :afterRowId ORDER BY rowid LIMIT :limit")
    suspend fun seriesSource(playlistId: Long, afterRowId: Long, limit: Int): List<IndexSource>

    // Gesucht wird in zwei Schritten: erst nur im Index (liefert IDs), dann die Einträge per Primärschlüssel.
    // Eine gemeinsame Abfrage (Index JOIN Tabelle) ließ SQLite bei großen Mediatheken die Tabelle durchlaufen und
    // für JEDE Zeile den Index neu durchsuchen – eine einzige Suche dauerte dann Minuten.

    /** Wortanfänge über den Volltext-Index. */
    @Query("SELECT itemId FROM search_index WHERE search_index MATCH :match AND type = :type AND playlistId = :playlistKey LIMIT :limit")
    suspend fun matching(match: String, type: String, playlistKey: String, limit: Int): List<String>

    /** Mitten im Wort über die gefalteten Begriffe ("neo" in "ZDFneo", "ntv" in "n-tv"). */
    @Query("SELECT itemId FROM search_index WHERE playlistId = :playlistKey AND type = :type AND name LIKE '%' || :fragment || '%' LIMIT :limit")
    suspend fun containing(fragment: String, type: String, playlistKey: String, limit: Int): List<String>

    @Query("SELECT * FROM channels c WHERE c.id IN (:ids) AND c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED ORDER BY c.sortIndex")
    suspend fun channelsById(playlistId: Long, ids: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM movies c WHERE c.id IN (:ids) AND c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED ORDER BY c.sortIndex")
    suspend fun moviesById(playlistId: Long, ids: List<String>): List<MovieEntity>

    @Query("SELECT * FROM series c WHERE c.id IN (:ids) AND c.playlistId = :playlistId AND $EXCLUDE_RESTRICTED ORDER BY c.sortIndex")
    suspend fun seriesById(playlistId: Long, ids: List<String>): List<SeriesEntity>
}

/** Fingerabdruck je Tabelle: Zeilenzahl und Gesamtlänge von Namen und Kennungen (ein Durchgang, keine Zeilen). */
private const val COUNTS = "SELECT COUNT(*) || '/' || IFNULL(SUM(LENGTH(name)), 0) || '/' || IFNULL(SUM(LENGTH(id)), 0) FROM "
private const val COUNTS_CHANNELS = COUNTS + "channels WHERE +playlistId = :playlistId"
private const val COUNTS_MOVIES = COUNTS + "movies WHERE +playlistId = :playlistId"
private const val COUNTS_SERIES = COUNTS + "series WHERE +playlistId = :playlistId"
