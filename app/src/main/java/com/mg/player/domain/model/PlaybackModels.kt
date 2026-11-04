package com.mg.player.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class PlaybackProgress(val contentKey: String, val positionMs: Long, val durationMs: Long, val watched: Boolean, val updatedAtMs: Long) {
    val fraction: Float
        get() = if (watched) {
            1f
        } else if (durationMs <= 0) {
            0f
        } else {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }

    companion object {
        fun movieKey(movieId: String) = "movie:$movieId"
        fun episodeKey(episodeId: String) = "episode:$episodeId"
        fun channelKey(channelId: String) = "live:$channelId"
    }
}

/** Gelerntes oder markiertes Intro-Zeitfenster einer Serie. */
@Immutable
data class IntroProfile(val seriesId: String, val introStartMs: Long, val introEndMs: Long)

/** Kapitel aus dem Videostream (z. B. MKV-Chapters). */
@Immutable
data class ChapterInfo(val title: String, val startMs: Long, val endMs: Long)

/** Was der Player abspielen soll. [engine] überschreibt optional den Standard-Player aus den Einstellungen. */
sealed interface PlayRequest {
    val engine: PlayerEngineType?

    data class Live(val channelId: String, val categoryId: String, override val engine: PlayerEngineType? = null) : PlayRequest

    data class Movie(val movieId: String, val startPositionMs: Long, override val engine: PlayerEngineType? = null) : PlayRequest

    data class Episode(val episodeId: String, val seriesId: String, val startPositionMs: Long, override val engine: PlayerEngineType? = null) : PlayRequest

    /** Eine eigene Aufnahme (Datei auf dem Gerät). */
    data class Recording(val recordingId: String, override val engine: PlayerEngineType? = null) : PlayRequest

    /** Zeitversetzt: eine vergangene (oder die laufende) Sendung aus dem Sender-Archiv. */
    data class Catchup(
        val channelId: String,
        val categoryId: String,
        val startMs: Long,
        val endMs: Long,
        val title: String,
        override val engine: PlayerEngineType? = null,
    ) : PlayRequest
}
