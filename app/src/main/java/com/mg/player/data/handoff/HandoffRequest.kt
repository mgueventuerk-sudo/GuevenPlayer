package com.mg.player.data.handoff

import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest

/**
 * „Auf dem Fernseher weiterschauen“: Ein [PlayRequest] als Text, damit er über das Heimnetz passt.
 *
 * Rein (ohne Android), damit beide Richtungen vollständig testbar sind – eine kaputte Serialisierung faellt sonst
 * erst auf, wenn zwei echte Geräte danebenliegen.
 *
 * Übertragen werden nur Kennungen und eine Position, nie Zugangsdaten und nie eine Stream-Adresse: Das empfangende
 * Gerät löst den Inhalt über die **eigene** Datenbank und den **eigenen** Zugang auf. Hat es den Film nicht, passiert
 * nichts – ein fremdes Gerät kann mit so einer Nachricht also auch nichts an sich ziehen.
 *
 * Die Wiedergabe-Art steckt in `k`, der Rest sind benannte Felder. Unbekannte Arten liefern null, damit eine neuere
 * Version auf der Gegenseite nicht zu einem falschen Start führt.
 */
object HandoffRequest {

    private const val KIND = "k"
    private const val ID = "i"
    private const val SECOND = "j"
    private const val POSITION = "p"

    private const val LIVE = "live"
    private const val MOVIE = "movie"
    private const val EPISODE = "episode"
    private const val RECORDING = "recording"

    /** Lässt sich dieser Wunsch übergeben? Zeitversetztes Fernsehen bleibt aussen vor (Archivfenster je Anbieter). */
    fun isSupported(request: PlayRequest): Boolean = encode(request) != null

    /** Als Abfrageteil einer Adresse („k=movie&i=123&p=42000“), oder null bei einer Art, die nicht übergeben wird. */
    fun encode(request: PlayRequest): Map<String, String>? = when (request) {
        is PlayRequest.Live -> mapOf(KIND to LIVE, ID to request.channelId)
        is PlayRequest.Movie -> mapOf(KIND to MOVIE, ID to request.movieId, POSITION to request.startPositionMs.toString())
        is PlayRequest.Episode -> mapOf(
            KIND to EPISODE,
            ID to request.episodeId,
            SECOND to request.seriesId,
            POSITION to request.startPositionMs.toString(),
        )
        is PlayRequest.Recording -> mapOf(KIND to RECORDING, ID to request.recordingId)
        else -> null
    }

    /**
     * Zurücklesen. Null, wenn die Art unbekannt ist oder eine Kennung fehlt – lieber nichts starten als das Falsche.
     * Die Engine wird nie übernommen: Das empfangende Gerät entscheidet selbst, womit es abspielt.
     */
    fun decode(query: Map<String, String>): PlayRequest? {
        val id = query[ID]?.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
        val position = query[POSITION]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return when (query[KIND]) {
            LIVE -> PlayRequest.Live(id, Category.ALL)
            MOVIE -> PlayRequest.Movie(id, position)
            EPISODE -> query[SECOND]?.takeIf { it.isNotBlank() && it.length <= MAX_ID }
                ?.let { PlayRequest.Episode(id, it, position) }
            RECORDING -> PlayRequest.Recording(id)
            else -> null
        }
    }

    /** Kennungen des Anbieters sind kurz; alles Längere ist kein Inhalt, den dieses Gerät kennt. */
    private const val MAX_ID = 128
}
