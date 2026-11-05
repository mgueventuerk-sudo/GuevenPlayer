package com.mg.player.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mg.player.data.db.entity.SportEventChannelEntity
import com.mg.player.data.db.entity.SportEventEntity
import com.mg.player.domain.model.ChannelKeyRow
import com.mg.player.domain.model.MatchSource
import kotlinx.coroutines.flow.Flow

/** Event + bester Kanal (nur sichtbare, nicht gesperrte Gruppen – gleiche Regel wie Suche und „Alle“). */
data class MatchedEventRow(
    val id: String,
    val sport: String,
    val league: String,
    val title: String,
    val homeTeam: String?,
    val awayTeam: String?,
    val startMs: Long,
    val endMs: Long?,
    val status: String,
    val stations: String,
    val channelId: String,
    val channelName: String,
    val categoryId: String,
    val score: Int,
)

data class EpgCandidateRow(val playlistId: Long, val channelKey: String, val title: String)

/**
 * Sender für den Abgleich mit dem öffentlichen Sport-Programm (Name und tvg-id). [qualityRank]/[sortIndex]: je Zugang
 * gewinnt die beste Variante; [categoryName] erkennt Event-Gruppen („LIVE EVENTS“), deren Sender selbst kein Sportwort tragen.
 */
data class ChannelIndexRow(
    val id: String,
    val name: String,
    val playlistId: Long,
    val epgChannelId: String,
    val qualityRank: Int = 50,
    val sortIndex: Int = 0,
    val categoryName: String = "",
)

/** Ein Block Sender beim seitenweisen Durchgehen (über die Zeilennummer, nie alle auf einmal im Speicher). */
data class ChannelIndexChunkRow(
    val rowId: Long,
    val id: String,
    val name: String,
    val playlistId: Long,
    val epgChannelId: String,
    val qualityRank: Int,
    val sortIndex: Int,
    val categoryName: String?,
) {
    fun toIndexRow() = ChannelIndexRow(id, name, playlistId, epgChannelId, qualityRank, sortIndex, categoryName.orEmpty())
}

/** Zuordnung eines Spiels (für die Kontrolle vor Anstoß). */
data class EventMatchRow(val eventId: String, val channelId: String, val playlistId: Long, val score: Int)

@Dao
abstract class SportDao {

    @Query(
        "SELECT e.id, e.sport, e.league, e.title, e.homeTeam, e.awayTeam, e.startMs, e.endMs, e.status, e.stations, " +
            "c.id AS channelId, c.name AS channelName, c.categoryId AS categoryId, MAX(m.score) AS score " +
            "FROM sport_events e INNER JOIN sport_event_channels m ON m.eventId = e.id " +
            "INNER JOIN channels c ON c.id = m.channelId " +
            "WHERE e.startMs BETWEEN :fromMs AND :toMs AND $EXCLUDE_RESTRICTED " +
            "GROUP BY e.id HAVING MAX(m.score) >= ${MatchSource.DISPLAY_MIN} ORDER BY e.startMs",
    )
    abstract fun observeMatched(fromMs: Long, toMs: Long): Flow<List<MatchedEventRow>>

    /** Alle geladenen Spiele im Zeitraum – auch ohne passenden Sender (Übersicht in den Einstellungen). */
    @Query("SELECT COUNT(*) FROM sport_events WHERE startMs BETWEEN :fromMs AND :toMs AND status != 'POST'")
    abstract fun observeEventCount(fromMs: Long, toMs: Long): Flow<Int>

    @Upsert
    abstract suspend fun upsertEvents(events: List<SportEventEntity>)

    @Query("DELETE FROM sport_event_channels WHERE eventId IN (:ids)")
    abstract suspend fun clearMatches(ids: List<String>)

    @Upsert
    abstract suspend fun upsertMatches(matches: List<SportEventChannelEntity>)

    /** Vorbei: Ende bzw. Start + 6 h liegt vor [beforeMs]. */
    @Query("DELETE FROM sport_events WHERE COALESCE(endMs, startMs + 21600000) < :beforeMs")
    abstract suspend fun deleteEndedBefore(beforeMs: Long)

    @Query("DELETE FROM sport_events WHERE id IN (:ids)")
    abstract suspend fun deleteEvents(ids: List<String>)

    @Query("DELETE FROM sport_event_channels WHERE eventId NOT IN (SELECT id FROM sport_events)")
    abstract suspend fun deleteOrphanMatches()

    /** Kandidaten für den EPG-Abgleich: alle Sendungen, die in diesem Zeitraum beginnen (Index auf startMs). */
    @Query("SELECT p.playlistId, p.channelKey, p.title FROM epg_programs p WHERE p.startMs BETWEEN :fromMs AND :toMs AND $ACTIVE_EPG LIMIT 30000")
    abstract suspend fun epgStartingBetween(fromMs: Long, toMs: Long): List<EpgCandidateRow>

    @Query("SELECT id FROM sport_events")
    abstract suspend fun allEventIds(): List<String>

    /** Reichweite des EPG (über den Index auf startMs, ohne die ganze Tabelle zu lesen). */
    @Query("SELECT p.startMs FROM epg_programs p WHERE $ACTIVE_EPG ORDER BY p.startMs DESC LIMIT 1")
    abstract suspend fun epgHorizonMs(): Long?

    /** Kanäle zu EPG-Schlüsseln (sichtbare Gruppen, beste Qualität zuerst). */
    @Query(
        "SELECT c.id, c.name, c.playlistId FROM channels c WHERE c.playlistId = :playlistId AND c.epgKey IN (:keys) " +
            "AND $EXCLUDE_RESTRICTED ORDER BY c.qualityRank, c.sortIndex",
    )
    abstract suspend fun channelsForEpgKeys(playlistId: Long, keys: List<String>): List<ChannelKeyRow>

    /** Kanäle mit genau diesem bereinigten Namen (für Sendernamen aus der Quelle). „Alle Zugänge“ (-2) zählt nicht doppelt. */
    @Query(
        "SELECT c.id, c.name, c.playlistId FROM channels c WHERE c.normalizedName IN (:names) AND c.playlistId != -2 " +
            "AND $EXCLUDE_RESTRICTED ORDER BY c.qualityRank, c.sortIndex",
    )
    abstract suspend fun channelsByNormalizedName(names: List<String>): List<ChannelKeyRow>

    /** Von Hand zugeordnete Sender (nur, solange ihre Gruppe sichtbar und nicht gesperrt ist). */
    @Query("SELECT c.id, c.name, c.playlistId FROM channels c WHERE c.id IN (:ids) AND c.playlistId != -2 AND $EXCLUDE_RESTRICTED")
    abstract suspend fun channelsByIds(ids: List<String>): List<ChannelKeyRow>

    /** Senderauswahl fürs Zuordnen: Name enthält den Suchtext (beste Qualität zuerst). */
    @Query(
        "SELECT c.id, c.name, c.playlistId FROM channels c WHERE c.name LIKE :pattern AND c.playlistId != -2 AND $EXCLUDE_RESTRICTED " +
            "ORDER BY c.qualityRank, c.sortIndex LIMIT 80",
    )
    abstract suspend fun channelsNamed(pattern: String): List<ChannelKeyRow>

    /**
     * Sichtbare, nicht gesperrte Sender blockweise (für den Abgleich mit dem Sport-Programm). Bis 3.12.1 wurden alle auf
     * einmal geladen – bei 100.000 Sendern rund 40 MB samt Index.
     */
    @Query(
        "SELECT c.rowid AS rowId, c.id, c.name, c.playlistId, c.epgChannelId, c.qualityRank, c.sortIndex, k.name AS categoryName " +
            "FROM channels c LEFT JOIN categories k ON k.id = c.categoryId " +
            "WHERE c.rowid > :afterRowId AND c.playlistId != -2 AND $EXCLUDE_RESTRICTED ORDER BY c.rowid LIMIT :limit",
    )
    abstract suspend fun channelIndexChunk(afterRowId: Long, limit: Int): List<ChannelIndexChunkRow>

    /** Spiele, die in diesem Zeitraum beginnen (Kontrolle kurz vor Anstoß). */
    @Query("SELECT * FROM sport_events WHERE startMs BETWEEN :fromMs AND :toMs AND status != 'POST' ORDER BY startMs")
    abstract suspend fun eventsStartingBetween(fromMs: Long, toMs: Long): List<SportEventEntity>

    @Query("SELECT eventId, channelId, playlistId, score FROM sport_event_channels WHERE eventId IN (:ids)")
    abstract suspend fun matchesFor(ids: List<String>): List<EventMatchRow>

    /** Kommende Spiele, für die (noch) kein Sender gefunden wurde. */
    @Query(
        "SELECT * FROM sport_events e WHERE e.startMs BETWEEN :fromMs AND :toMs AND e.status != 'POST' " +
            "AND NOT EXISTS (SELECT 1 FROM sport_event_channels m WHERE m.eventId = e.id) ORDER BY e.startMs",
    )
    abstract fun observeUnmatched(fromMs: Long, toMs: Long): Flow<List<SportEventEntity>>

    /**
     * Ein Abruf in einem Schritt: neue/aktualisierte Events samt Zuordnungen, nicht mehr gefolgte Events ([removeIds])
     * und Vergangenes löschen. [matchedIds]: Events, deren Zuordnung neu berechnet wurde (alte werden ersetzt).
     */
    @Transaction
    open suspend fun replace(
        events: List<SportEventEntity>,
        matchedIds: List<String>,
        matches: List<SportEventChannelEntity>,
        removeIds: List<String>,
        nowMs: Long,
    ) {
        upsertEvents(events)
        matchedIds.chunked(MAX_ARGS).forEach { clearMatches(it) }
        upsertMatches(matches)
        removeIds.chunked(MAX_ARGS).forEach { deleteEvents(it) }
        deleteEndedBefore(nowMs - 6 * 3_600_000L)
        deleteOrphanMatches()
    }

    private companion object {
        const val MAX_ARGS = 900
    }
}
