package com.mg.player.ui.player

import androidx.compose.runtime.Immutable
import com.mg.player.core.di.AppContainer
import com.mg.player.core.util.TimeUtils
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.device.DeviceVideo
import com.mg.player.player.device.VariantOrder
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PlayableKind { LIVE, MOVIE, EPISODE, CATCHUP, RECORDING }

/** Ein konkret abspielbarer Inhalt. */
@Immutable
data class Playable(
    val kind: PlayableKind,
    val id: String,
    val title: String,
    val subtitle: String?,
    val url: String,
    val startPositionMs: Long,
    val progressKey: String?,
    val seriesId: String? = null,
    val episode: Episode? = null,
    val channel: Channel? = null,
    /** Live: alle Stream-Varianten des Senders, beste (bzw. zuletzt funktionierende) zuerst. */
    val variants: List<Channel> = emptyList(),
    val variantIndex: Int = 0,
    /** Explizite HTTP-Freigabe des IPTV-Zugangs; wird auch für HLS-Untersegmente verwendet. */
    val allowInsecureHttp: Boolean = false,
    /** Film/Folge aus „Alle Zugänge“: alle Quellen, beste zuerst ([variantIndex] zeigt auf die laufende). */
    val vodSources: List<VodSource> = emptyList(),
    /** Automatischer Wechsel auch in andere Zugänge (Einstellung „Automatisch zur nächsten Quelle“). */
    val autoAcrossAccounts: Boolean = true,
    /** Namen der Zugänge der [variants] (Quellen-Liste in „Alle Zugänge“). */
    val accountNames: Map<Long, String> = emptyMap(),
) {
    val isLive: Boolean get() = kind == PlayableKind.LIVE

    /** Derselbe Inhalt in derselben Stream-Variante (veraltete Umschaltungen erkennen). */
    fun isSameStream(other: Playable): Boolean = id == other.id && variantIndex == other.variantIndex

    /** Die gerade gespielte Stream-Variante (bei nur einer Quelle der Sender selbst). */
    val activeVariant: Channel? get() = variants.getOrNull(variantIndex) ?: channel

    /** Die gerade gespielte Quelle eines Films/einer Folge aus „Alle Zugänge“. */
    val activeVodSource: VodSource? get() = vodSources.getOrNull(variantIndex)

    /** Anzahl der Quellen, zwischen denen gewechselt werden kann (Live: Varianten, „Alle Zugänge“: auch Filme/Folgen). */
    val sourceCount: Int get() = if (vodSources.isNotEmpty()) vodSources.size else variants.size

    /** Zugang der laufenden Quelle (für Verbindungsanzeige, Nachfrage bei belegtem Zugang). */
    val activePlaylistId: Long? get() = activeVodSource?.playlistId ?: activeVariant?.playlistId?.takeIf { it > 0 }

    /** ID des Originals, das gerade läuft (bei „Alle Zugänge“ nicht die kombinierte ID). */
    val activeItemId: String get() = activeVodSource?.itemId ?: activeVariant?.id ?: id

    /** Gibt es eine nächste Quelle, zu der automatisch gewechselt werden darf? */
    val canSwitchSource: Boolean get() = nextSourceIndex() != null

    /** Nächste Quelle; ohne [autoAcrossAccounts] nur innerhalb des laufenden Zugangs (sonst wird nachgefragt). */
    fun nextSourceIndex(): Int? {
        val next = variantIndex + 1
        if (next >= sourceCount) return null
        if (!isLive && vodSources.isEmpty()) return null
        if (!autoAcrossAccounts) {
            val current = activePlaylistId
            val target = vodSources.getOrNull(next)?.playlistId ?: variants.getOrNull(next)?.playlistId
            if (current != null && target != current) return null
        }
        return next
    }

    /** Position einer Quelle (Original-ID) in diesem Inhalt, null = keine Quelle davon. */
    fun sourceIndexOf(itemId: String): Int? = (vodSources.indexOfFirst { it.itemId == itemId }.takeIf { it >= 0 })
        ?: variants.indexOfFirst { it.id == itemId }.takeIf { it >= 0 }

    /** Kurzbezeichnung der Quelle [index] (Hinweis beim Wechsel). */
    fun sourceName(index: Int): String = vodSources.getOrNull(index)?.label?.ifBlank { null }
        ?: variants.getOrNull(index)?.quality
        ?: "Quelle ${index + 1}"

    /** Anzeige im Overlay, z. B. "Quelle 1/3 · FHD". */
    val sourceLabel: String?
        get() = when {
            vodSources.size > 1 -> "Quelle ${variantIndex + 1}/${vodSources.size} · ${sourceName(variantIndex)}"
            variants.size > 1 -> "Quelle ${variantIndex + 1}/${variants.size}" + (activeVariant?.quality?.let { " · $it" } ?: "")
            else -> null
        }
}

@Immutable
data class PlayerUiState(
    val playable: Playable? = null,
    val engineType: PlayerEngineType = PlayerEngineType.MEDIA3,
    val engine: EngineState = EngineState(),
    val reconnecting: Boolean = false,
    /** Kein Netz: Es wird gewartet, bis die Verbindung zurück ist. */
    val waitingForNetwork: Boolean = false,
    /** Endgültige Fehlermeldung nach allen Neuversuchen (z. B. "Zugriff verweigert (HTTP 403)"). */
    val failure: String? = null,
    /** Was der Wiedergabe-Wächter gerade tut (z. B. „Bild ohne Ton – Ton selbst dekodieren“). */
    val recoveryText: String? = null,
    val loadError: String? = null,
    val nowProgram: EpgProgram? = null,
    val nextProgram: EpgProgram? = null,
    val isFavorite: Boolean = false,
    val nextEpisode: Episode? = null,
    val channelNumber: Int? = null,
    val channelCount: Int = 0,
) {
    val isBuffering: Boolean get() = engine.status == PlaybackStatus.BUFFERING || reconnecting
}

/** Wiedergabeposition – eigener Zustand, damit sich nicht der ganze Player-Screen zweimal pro Sekunde neu zeichnet. */
@Immutable
data class PlaybackPosition(val positionMs: Long = 0, val durationMs: Long = 0)

sealed interface PlayerEvent {
    data object Close : PlayerEvent
    data class ApplyFrameRate(val fps: Float) : PlayerEvent
    data class Toast(val message: String) : PlayerEvent
}

/**
 * Der **laufende** Inhalt als Abspiel-Wunsch für ein anderes Gerät („Auf dem Fernseher weiterschauen“) – nicht der
 * Wunsch, mit dem der Player geöffnet wurde: Nach dem Zappen oder „Nächste Folge“ läuft längst etwas anderes, und der
 * Fernseher startete sonst den falschen Sender bzw. die falsche Folge. [positionMs]: aktuelle Position (Filme/Folgen).
 * Null für Arten, die sich nicht übergeben lassen (Catch-up: Archivfenster je Zugang).
 */
fun Playable.toPlayRequest(positionMs: Long): PlayRequest? = when (kind) {
    PlayableKind.LIVE -> PlayRequest.Live(channel?.id ?: id, Category.ALL)
    PlayableKind.MOVIE -> PlayRequest.Movie(id, positionMs)
    PlayableKind.EPISODE -> (seriesId ?: episode?.seriesId)?.let { PlayRequest.Episode(id, it, positionMs) }
    PlayableKind.RECORDING -> PlayRequest.Recording(id)
    PlayableKind.CATCHUP -> null
}

/** Meldung, wenn sich eine Abspiel-Anfrage nicht auflösen lässt. */
fun PlayRequest.notFoundMessage(): String = when (this) {
    is PlayRequest.Catchup -> "Für diese Sendung ist kein Archiv verfügbar"
    is PlayRequest.Recording -> "Die Aufnahme-Datei wurde nicht gefunden"
    else -> "Inhalt nicht gefunden"
}

/** Löst eine Abspiel-Anfrage in einen konkreten Stream auf. */
class PlayableResolver(private val container: AppContainer) {

    private val urls = container.streamUrlResolver

    /**
     * Live-Sender inklusive aller zusammengefassten Varianten. Ohne Prüfverzögerung startet sofort die beste –
     * oder die Variante, die in dieser Sitzung zuletzt funktioniert hat.
     */
    suspend fun channel(channelId: String): Playable? {
        val channel = container.liveRepository.channel(channelId) ?: return null
        val all = container.liveRepository.variants(channel)
        val remembered = container.playback.streamMemory[channel.playlistId, channel.groupKey]
        val supports4K = withContext(Dispatchers.Default) { DeviceVideo.supports4K(container.appContext) }
        val combined = Combined.isCombinedItem(channel.id)
        val ordered = if (combined) {
            CombinedPlayables.rankLive(container, all, remembered, supports4K)
        } else {
            VariantOrder.order(all, { it.id }, { it.quality }, remembered, supports4K, container.playback.sourceHealth::rank)
        }
        val first = ordered.firstOrNull() ?: channel
        return Playable(
            kind = PlayableKind.LIVE,
            id = channel.id,
            title = channel.name,
            subtitle = channel.nowTitle,
            url = urls.resolve(first.streamUrl, first.id),
            startPositionMs = 0,
            progressKey = null,
            channel = channel,
            variants = ordered,
            variantIndex = 0,
            accountNames = if (combined) container.database.playlistDao().all().associate { it.id to it.name } else emptyMap(),
            allowInsecureHttp = urls.isHttpAllowed(first.id),
            autoAcrossAccounts = !combined || container.combined.settings.current().autoSwitch,
        )
    }

    /** Dieselbe Sendung, aber über eine andere Stream-Variante (Auto-Failover oder Quellen-Wahl). */
    suspend fun variant(playable: Playable, index: Int): Playable? {
        playable.vodSources.getOrNull(index)?.let { source ->
            val url = urls.resolve(source.streamUrl, source.itemId)
            return playable.copy(url = url, variantIndex = index, allowInsecureHttp = urls.isHttpAllowed(source.itemId))
        }
        val target = playable.variants.getOrNull(index) ?: return null
        return playable.copy(url = urls.resolve(target.streamUrl, target.id), variantIndex = index, allowInsecureHttp = urls.isHttpAllowed(target.id))
    }

    suspend fun resolve(request: PlayRequest): Playable? = when (request) {
        is PlayRequest.Live -> channel(request.channelId)

        is PlayRequest.Movie -> container.vodRepository.movie(request.movieId)?.let { movie ->
            if (Combined.isCombinedItem(movie.id)) {
                combinedVod(movie.id, movie.name, movie.year, request.startPositionMs, null)
            } else {
                Playable(
                kind = PlayableKind.MOVIE,
                id = movie.id,
                title = movie.name,
                subtitle = movie.year,
                url = urls.resolve(movie.streamUrl, movie.id),
                startPositionMs = request.startPositionMs,
                    progressKey = PlaybackProgress.movieKey(movie.id),
                    allowInsecureHttp = urls.isHttpAllowed(movie.id),
                )
            }
        }

        is PlayRequest.Episode -> container.seriesRepository.episode(request.episodeId)?.let { episode(it, request.startPositionMs) }

        is PlayRequest.Catchup -> catchup(request.channelId, request.startMs, request.endMs, request.title)

        is PlayRequest.Recording -> recording(request.recordingId)
    }

    /**
     * Film oder Folge aus „Alle Zugänge“: alle Quellen nach Rangfolge, gestartet wird die beste. Fortschritt und
     * Favorit hängen an der kombinierten ID – egal, aus welchem Zugang gerade gespielt wird.
     */
    private suspend fun combinedVod(id: String, title: String, subtitle: String?, startPositionMs: Long, episode: Episode?): Playable? {
        val remembered = container.playback.streamMemory[Combined.ID, id]
        val supports4K = withContext(Dispatchers.Default) { DeviceVideo.supports4K(container.appContext) }
        val sources = CombinedPlayables.vodSources(container, id, episode != null, remembered, supports4K)
        val first = sources.firstOrNull() ?: return null
        return Playable(
            kind = if (episode != null) PlayableKind.EPISODE else PlayableKind.MOVIE,
            id = id,
            title = title,
            subtitle = subtitle,
            url = urls.resolve(first.streamUrl, first.itemId),
            startPositionMs = startPositionMs,
            progressKey = if (episode != null) PlaybackProgress.episodeKey(id) else PlaybackProgress.movieKey(id),
            seriesId = episode?.seriesId,
            episode = episode,
            allowInsecureHttp = urls.isHttpAllowed(first.itemId),
            vodSources = sources,
            autoAcrossAccounts = container.combined.settings.current().autoSwitch,
        )
    }

    private suspend fun recording(id: String): Playable? {
        val recording = container.recordingManager.recording(id) ?: return null
        val file = java.io.File(recording.filePath)
        // Plattenzugriff: Der Player löst im Hauptthread auf (Labor-Build: StrictMode beendete sonst die Wiedergabe).
        if (!withContext(Dispatchers.IO) { file.exists() }) return null
        return Playable(
            kind = PlayableKind.RECORDING,
            id = recording.id,
            title = recording.title,
            subtitle = "${recording.channelName} · Aufnahme vom ${TimeUtils.formatDate(recording.startedAtMs)}",
            url = android.net.Uri.fromFile(file).toString(),
            startPositionMs = 0,
            progressKey = null,
        )
    }

    suspend fun catchup(channelId: String, startMs: Long, endMs: Long, title: String): Playable? {
        val channel = container.liveRepository.channel(channelId) ?: return null
        val url = urls.catchupUrl(channelId, startMs, endMs) ?: return null
        return Playable(
            kind = PlayableKind.CATCHUP,
            id = "${channel.id}@$startMs",
            title = channel.name,
            subtitle = "$title · ${TimeUtils.formatClock(startMs)}–${TimeUtils.formatClock(endMs)}",
            url = url,
            startPositionMs = 0,
            progressKey = null,
            channel = channel,
            allowInsecureHttp = urls.isHttpAllowed(channel.id),
        )
    }

    suspend fun episode(episode: Episode, startPositionMs: Long): Playable {
        val series = container.seriesRepository.seriesById(episode.seriesId)
        if (Combined.isCombinedItem(episode.id)) {
            combinedVod(episode.id, series?.name ?: episode.title, "${episode.code} – ${episode.title}", startPositionMs, episode)?.let { return it }
        }
        return Playable(
            kind = PlayableKind.EPISODE,
            id = episode.id,
            title = series?.name ?: episode.title,
            subtitle = "${episode.code} – ${episode.title}",
            url = urls.resolve(episode.streamUrl, episode.id),
            startPositionMs = startPositionMs,
            progressKey = PlaybackProgress.episodeKey(episode.id),
            seriesId = episode.seriesId,
            episode = episode,
            allowInsecureHttp = urls.isHttpAllowed(episode.id),
        )
    }
}
