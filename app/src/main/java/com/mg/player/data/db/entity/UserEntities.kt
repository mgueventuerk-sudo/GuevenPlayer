package com.mg.player.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Wiedergabefortschritt für Filme, Episoden und Sender ("live:" = zuletzt gesehen). */
@Entity(tableName = "progress", indices = [Index(value = ["seriesId"])])
data class ProgressEntity(
    @PrimaryKey val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAtMs: Long,
    val seriesId: String?,
)

@Entity(tableName = "favorites", indices = [Index(value = ["type", "itemId"])])
data class FavoriteEntity(@PrimaryKey val key: String, val type: String, val itemId: String, val addedAtMs: Long)

/** Serien-Gedächtnis: gelerntes Intro-Zeitfenster pro Serie (Stufe 2 der Intro-Erkennung). */
@Entity(tableName = "intro_profiles")
data class IntroProfileEntity(@PrimaryKey val seriesId: String, val introStartMs: Long, val introEndMs: Long)

/** Persönliche Kategorie-Einstellungen: ausblenden, sperren (PIN), anheften. */
@Entity(tableName = "category_prefs", indices = [Index(value = ["playlistId"])])
data class CategoryPrefEntity(
    @PrimaryKey val categoryId: String,
    val playlistId: Long,
    val hidden: Boolean,
    val locked: Boolean,
    val pinned: Boolean,
    /** Eigene Reihenfolge („Kategorien verwalten“, 0 = oben); null = Reihenfolge des Anbieters. */
    val position: Int? = null,
)

/** Zwischengespeicherte TMDB-Daten pro Film/Serie (Schlüssel z. B. "movie:<id>"). */
@Entity(tableName = "tmdb_cache")
data class TmdbCacheEntity(
    @PrimaryKey val itemKey: String,
    val tmdbId: Int?,
    val mediaType: String,
    val title: String?,
    val overview: String?,
    val rating: Float?,
    val voteCount: Int,
    val genres: String,
    val runtimeMin: Int?,
    val year: String?,
    val certification: String?,
    val trailerKey: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val cast: String,
    val similar: String,
    val fetchedAtMs: Long,
    val notFound: Boolean,
    val manual: Boolean,
)
