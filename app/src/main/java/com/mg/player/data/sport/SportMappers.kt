package com.mg.player.data.sport

import com.mg.player.data.db.dao.MatchedEventRow
import com.mg.player.data.db.entity.SportEventEntity
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.MatchedEvent
import com.mg.player.domain.model.SportEvent

internal fun SportEvent.toEntity(now: Long) = SportEventEntity(
    id, sport, league, title, homeTeam, awayTeam, startMs, endMs, status.name, stations.joinToString("\n"), now,
)

internal fun MatchedEventRow.toDomain() = MatchedEvent(
    event = SportEvent(
        id, sport, league, title, homeTeam, awayTeam, startMs, endMs,
        runCatching { EventStatus.valueOf(status) }.getOrDefault(EventStatus.UNKNOWN),
        stations.split('\n').filter { it.isNotBlank() },
    ),
    channelId = channelId,
    channelName = channelName,
    categoryId = categoryId,
    guessed = score <= ChannelEventMatcher.MANUAL_SCORE + 1,
)

internal fun SportEventEntity.toDomain() = SportEvent(
    id, sport, league, title, homeTeam, awayTeam, startMs, endMs,
    runCatching { EventStatus.valueOf(status) }.getOrDefault(EventStatus.UNKNOWN),
    stations.split('\n').filter { it.isNotBlank() },
)
