package com.mg.player.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Zwischengespeicherte Sport-Events – nur damit die Sport-Seite beim Start sofort da ist; wird immer online erneuert. */
@Entity(tableName = "sport_events", indices = [Index("startMs")])
data class SportEventEntity(
    @PrimaryKey val id: String,
    val sport: String,
    val league: String,
    val title: String,
    val homeTeam: String?,
    val awayTeam: String?,
    val startMs: Long,
    val endMs: Long?,
    val status: String,
    /** Sendernamen der Quelle, zeilengetrennt. */
    val stations: String,
    val fetchedAtMs: Long,
)

/** Zuordnung Event → Kanal. [score]: höher = sicherer (100 Sendername, 80 beide Teams im EPG, 60 Eventname im EPG). */
@Entity(tableName = "sport_event_channels", primaryKeys = ["eventId", "channelId"], indices = [Index("channelId")])
data class SportEventChannelEntity(val eventId: String, val channelId: String, val playlistId: Long, val score: Int)
