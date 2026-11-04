package com.mg.player.data.mapper

import com.mg.player.core.security.SecretCipher
import com.mg.player.core.util.MediaTitle
import com.mg.player.data.db.dao.CategoryWithPref
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.db.entity.ChannelWithNow
import com.mg.player.data.db.entity.EpgProgramEntity
import com.mg.player.data.db.entity.EpisodeEntity
import com.mg.player.data.db.entity.IntroProfileEntity
import com.mg.player.data.db.entity.MovieEntity
import com.mg.player.data.db.entity.MovieRow
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.db.entity.ProgressEntity
import com.mg.player.data.db.entity.SeriesEntity
import com.mg.player.data.db.entity.SeriesRow
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.IntroProfile
import com.mg.player.domain.model.Movie
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.Series

fun PlaylistEntity.toDomain() = Playlist(
    id = id,
    name = name,
    type = runCatching { PlaylistType.valueOf(type) }.getOrDefault(PlaylistType.M3U),
    serverUrl = serverUrl,
    username = username,
    password = SecretCipher.decrypt(password),
    m3uUrl = SecretCipher.decrypt(m3uUrl),
    customEpgUrl = customEpgUrl,
    headerEpgUrl = headerEpgUrl,
    lastUpdatedMs = lastUpdatedMs,
    allowInsecureHttp = allowInsecureHttp,
    autoRefreshDays = autoRefreshDays,
    epgShiftHours = epgShiftHours,
)

fun CategoryEntity.toDomain() = Category(
    id = id,
    name = name,
    type = runCatching { ContentType.valueOf(type) }.getOrDefault(ContentType.LIVE),
)

fun CategoryWithPref.toDomain() = category.toDomain().copy(locked = locked, pinned = pinned, hidden = hidden)

fun MovieRow.toDomain(): Movie {
    val base = movie.toDomain()
    val pos = progressPos ?: 0L
    val dur = progressDur ?: 0L
    val progress = if (progressWatched != true && pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0.02f, 1f) else null
    return base.copy(rating = tmdbRating?.takeIf { it > 0f } ?: base.rating, progress = progress)
}

fun SeriesRow.toDomain() = series.toDomain().let { s -> if (tmdbRating != null && tmdbRating > 0f) s.copy(rating = tmdbRating) else s }

/** [shiftMs] = EPG-Zeitverschiebung aus den Einstellungen. */
fun ChannelWithNow.toDomain(shiftMs: Long) = channel.toDomain().copy(
    nowTitle = nowTitle,
    nowStartMs = nowStart?.plus(shiftMs),
    nowEndMs = nowEnd?.plus(shiftMs),
    variantCount = variants,
)

/** Bereinigt den Sendernamen; Qualitätskürzel aus dem Titel bleiben Anbieterhinweise. */
fun ChannelEntity.toDomain(): Channel {
    val info = MediaTitle.parse(name)
    return Channel(
        id = id,
        name = info.title,
        logoUrl = logoUrl,
        streamUrl = streamUrl,
        categoryId = categoryId,
        epgKey = epgKey,
        nowTitle = null,
        nowStartMs = null,
        nowEndMs = null,
        catchupDays = catchupDays,
        playlistId = playlistId,
        groupKey = groupKey,
        quality = info.resolution,
        epgPlaylistId = if (epgPlaylistId != 0L) epgPlaylistId else playlistId,
        originalName = name,
        qualityBadges = info.badges,
    )
}

fun MovieEntity.toDomain(): Movie {
    val info = MediaTitle.parse(name)
    return Movie(
        id = id,
        name = info.title,
        posterUrl = posterUrl,
        rating = rating,
        year = year ?: info.year,
        streamUrl = streamUrl,
        plot = plot,
        backdropUrl = backdropUrl,
        durationSec = durationSec,
        remoteId = remoteId,
        badges = info.visibleBadges,
    )
}

fun SeriesEntity.toDomain(): Series {
    val info = MediaTitle.parse(name)
    return Series(
        id = id,
        name = info.title,
        posterUrl = posterUrl,
        rating = rating,
        year = year ?: info.year,
        plot = plot,
        backdropUrl = backdropUrl,
        remoteId = remoteId,
        badges = info.visibleBadges,
    )
}

fun EpisodeEntity.toDomain() = Episode(
    id = id,
    seriesId = seriesId,
    season = season,
    episode = episode,
    title = title,
    streamUrl = streamUrl,
    imageUrl = imageUrl,
    plot = plot,
    durationSec = durationSec,
)

fun EpgProgramEntity.toDomain(shiftMs: Long) = EpgProgram(
    title = title,
    description = description,
    startMs = startMs + shiftMs,
    endMs = endMs + shiftMs,
)

fun ProgressEntity.toDomain() = PlaybackProgress(
    contentKey = key,
    positionMs = positionMs,
    durationMs = durationMs,
    watched = watched,
    updatedAtMs = updatedAtMs,
)

fun IntroProfileEntity.toDomain() = IntroProfile(seriesId, introStartMs, introEndMs)
