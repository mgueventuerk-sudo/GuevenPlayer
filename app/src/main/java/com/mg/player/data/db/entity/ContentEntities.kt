package com.mg.player.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** Passwort und M3U-URL werden verschlüsselt gespeichert (siehe SecretCipher). */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val m3uUrl: String,
    val customEpgUrl: String,
    val headerEpgUrl: String,
    val lastUpdatedMs: Long,
    val serverTimezone: String = "",
    /** Der Zugang darf unverschlüsseltes HTTP für Anbieter-URLs nutzen (standardmäßig gesperrt). */
    val allowInsecureHttp: Boolean = false,
    /** Automatisch komplett aktualisieren (Inhalte + EPG), wenn seit [lastUpdatedMs] so viele Tage vergangen sind; 0 = aus. */
    @ColumnInfo(defaultValue = "0") val autoRefreshDays: Int = 0,
    /** Korrektur falscher Sendezeiten dieses Anbieters in Stunden (−12 … +12). Gilt für das EPG, das von diesem Zugang stammt. */
    @ColumnInfo(defaultValue = "0") val epgShiftHours: Int = 0,
)

/*
 * "generation": Beim Aktualisieren werden neue Einträge mit einer neuen Generation geschrieben
 * und erst ganz am Ende die veralteten gelöscht – die Listen werden dabei nie leer.
 */

@Entity(
    tableName = "categories",
    indices = [Index(value = ["playlistId", "type", "sortIndex"])],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val playlistId: Long,
    val type: String,
    val remoteId: String,
    val name: String,
    val sortIndex: Int,
    val generation: Long = 0,
)

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId", "categoryId", "sortIndex"]),
        Index(value = ["playlistId", "sortIndex"]),
        Index(value = ["epgKey"]),
        // Auto-Failover und Quellen-Wahl suchen bei jedem Senderwechsel die Varianten über groupKey.
        Index(value = ["playlistId", "groupKey"]),
        // Ab v10: Gegenstücke in anderen Zugängen und Sport-Zuordnung suchten sonst alle Sender aller Zugänge durch.
        Index(value = ["normalizedName"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val playlistId: Long,
    val categoryId: String,
    val remoteId: String,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val epgChannelId: String,
    val normalizedName: String,
    val epgKey: String,
    val sortIndex: Int,
    val catchupDays: Int = 0,
    val catchupType: String? = null,
    val catchupSource: String? = null,
    val generation: Long = 0,
    /** Gleicher Sender in mehreren Qualitäten hat denselben Schlüssel (z. B. "DE:zdf"). */
    val groupKey: String = "",
    /** Qualitätsrang der Variante, kleiner = besser (4K = 0 … SD = 60). */
    val qualityRank: Int = 50,
    /** EPG aus diesem Zugang lesen (0 = eigener). Nur „Alle Zugänge“ setzt es – das EPG bleibt beim Original-Zugang. */
    @ColumnInfo(defaultValue = "0") val epgPlaylistId: Long = 0,
)

/**
 * Vorberechnete Sender-Gruppierung (Neuaufbau nach jedem Import und jeder Änderung an ausgeblendeten Gruppen).
 * Die Senderlisten lesen hier per Index die "beste Variante" je Gruppe, statt bei jeder Seite alle Sender
 * zu gruppieren. 1/0 statt Boolean, weil die Werte direkt aus SQL-Ausdrücken kommen.
 */
@Entity(
    tableName = "channel_flags",
    indices = [
        Index(value = ["playlistId", "primaryAll", "sortIndex"]),
        Index(value = ["playlistId", "categoryId", "primaryCat", "sortIndex"]),
    ],
)
data class ChannelFlagsEntity(
    @PrimaryKey val channelId: String,
    val playlistId: Long,
    val categoryId: String,
    val sortIndex: Int,
    /** Beste Variante der Gruppe unter allen sichtbaren Sendern ("Alle Sender"). */
    val primaryAll: Int,
    val variantsAll: Int,
    /** Beste Variante der Gruppe innerhalb der eigenen Kategorie. */
    val primaryCat: Int,
    val variantsCat: Int,
)

/** Sender inklusive der gerade laufenden Sendung (per Subquery ermittelt). */
data class ChannelWithNow(
    @Embedded val channel: ChannelEntity,
    val nowTitle: String?,
    val nowStart: Long?,
    val nowEnd: Long?,
    /** Anzahl zusammengefasster Stream-Varianten dieses Senders. */
    val variants: Int,
)

/** Schlanke Projektion für das EPG-Fuzzy-Matching. */
data class ChannelMatchRow(val id: String, val epgChannelId: String, val normalizedName: String, val epgKey: String)

@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["playlistId", "categoryId", "sortIndex"]),
        Index(value = ["playlistId", "sortIndex"]),
    ],
)
data class MovieEntity(
    @PrimaryKey val id: String,
    val playlistId: Long,
    val categoryId: String,
    val remoteId: String,
    val name: String,
    val posterUrl: String?,
    val rating: Float?,
    val year: String?,
    val streamUrl: String,
    val sortIndex: Int,
    val plot: String? = null,
    val backdropUrl: String? = null,
    val durationSec: Int? = null,
    val detailsLoaded: Boolean = false,
    val tmdbId: String? = null,
    val generation: Long = 0,
)

/** Film inkl. TMDB-Bewertung aus dem Cache und Wiedergabe-Fortschritt (für das Poster-Grid). */
data class MovieRow(@Embedded val movie: MovieEntity, val tmdbRating: Float?, val progressPos: Long?, val progressDur: Long?, val progressWatched: Boolean?)

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["playlistId", "categoryId", "sortIndex"]),
        Index(value = ["playlistId", "sortIndex"]),
    ],
)
data class SeriesEntity(
    @PrimaryKey val id: String,
    val playlistId: Long,
    val categoryId: String,
    val remoteId: String,
    val name: String,
    val posterUrl: String?,
    val rating: Float?,
    val year: String?,
    val plot: String?,
    val backdropUrl: String?,
    val sortIndex: Int,
    val tmdbId: String? = null,
    val generation: Long = 0,
)

/** Serie inkl. TMDB-Bewertung aus dem Cache (für das Poster-Grid). */
data class SeriesRow(@Embedded val series: SeriesEntity, val tmdbRating: Float?)

@Entity(
    tableName = "episodes",
    // Ab v10 auch playlistId: Aufräumen nach dem Import (deleteStaleEpisodes) lief sonst über alle Episoden.
    indices = [Index(value = ["seriesId", "season", "episode"]), Index(value = ["playlistId"])],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val playlistId: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val streamUrl: String,
    val imageUrl: String?,
    val plot: String?,
    val durationSec: Int?,
    val generation: Long = 0,
)

/**
 * EPG je Zugang: Eine Aktualisierung von Zugang B lässt das EPG von Zugang A unangetastet.
 * [generation]: Ein Import schreibt seine Sendungen direkt hierher, aber unter einer neuen Generation; sichtbar wird
 * immer nur die in [EpgActiveEntity] eingetragene. Bis 3.12.1 lief der Import über eine Zwischentabelle, die am Ende
 * in einem einzigen großen Schritt kopiert wurde (kurzzeitig dreifacher Speicherplatz, lange Schreibsperre).
 */
@Entity(
    tableName = "epg_programs",
    indices = [Index(value = ["playlistId", "channelKey", "startMs"]), Index(value = ["startMs"])],
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
    @ColumnInfo(defaultValue = "0") val generation: Long = 0,
)

/** Sichtbare EPG-Generation je Zugang (fehlt der Eintrag, gilt 0). Umschalten = eine Zeile ändern. */
@Entity(tableName = "epg_active")
data class EpgActiveEntity(@PrimaryKey val playlistId: Long, val generation: Long)

/**
 * Volltext-Index für die Suche (FTS4, unicode61: Groß/Klein und Akzente egal). Wird nach jeder
 * Aktualisierung pro Zugang neu aufgebaut – so bleibt die Suche auch bei 100.000+ Titeln in Millisekunden.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["itemId", "type", "playlistId"])
@Entity(tableName = "search_index")
data class SearchIndexEntity(val name: String, val itemId: String, val type: String, val playlistId: String)

/**
 * „Alle Zugänge“ (ab v12): welche echten Einträge (Sender, Filme, Serien, Folgen) zu einem zusammengeführten Eintrag
 * gehören. [combinedId] ist die ID der kombinierten Zeile (Zugang [com.mg.player.data.combined.Combined.ID]),
 * [itemId] die des Originals. [qualityRank] wie bei Sendern (kleiner = besser), [language] Länderkürzel der Fassung.
 */
@Entity(
    tableName = "combined_links",
    primaryKeys = ["combinedId", "itemId"],
    indices = [Index(value = ["itemId"]), Index(value = ["type"])],
)
data class CombinedLinkEntity(
    val combinedId: String,
    val itemId: String,
    val playlistId: Long,
    val type: String,
    val qualityRank: Int = 50,
    val language: String? = null,
    val durationSec: Int? = null,
)

/**
 * Eigene Korrektur der Zuordnung: zwei Einträge (über ihren Vergleichsschlüssel, nicht über interne Nummern – so
 * überlebt die Regel jede Aktualisierung) sind dasselbe ([merge] = true) bzw. ausdrücklich nicht dasselbe.
 */
@Entity(tableName = "combined_rules", primaryKeys = ["type", "keyA", "keyB"])
data class CombinedRuleEntity(
    val type: String,
    val keyA: String,
    val keyB: String,
    val merge: Boolean,
)
