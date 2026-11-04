package com.mg.player.domain.usecase

import com.mg.player.core.util.UrlUtils
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.EpgRepository
import com.mg.player.domain.repository.ImportProgress
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.domain.repository.PlaylistRepository
import com.mg.player.domain.repository.SettingsRepository

/** Legt eine Playlist an, erkennt get.php-Links als Xtream-Zugang und setzt sie aktiv. */
class AddPlaylistUseCase(private val playlists: PlaylistRepository, private val settings: SettingsRepository) {
    suspend operator fun invoke(input: PlaylistInput): Long {
        val normalized = if (input.type == PlaylistType.M3U) {
            UrlUtils.detectXtreamFromM3u(input.m3uUrl)?.let { creds ->
                input.copy(
                    type = PlaylistType.XTREAM,
                    serverUrl = creds.baseUrl,
                    username = creds.username,
                    password = creds.password,
                )
            } ?: input
        } else {
            input
        }
        val id = playlists.addPlaylist(normalized)
        settings.update { it.copy(activePlaylistId = id, lastChannelId = null, lastChannelCategoryId = null) }
        return id
    }
}

/** Lädt Inhalte und anschließend das EPG einer Playlist neu. */
class RefreshPlaylistUseCase(
    private val playlists: PlaylistRepository,
    private val epg: EpgRepository,
    /** Läuft zwischen Inhalten und EPG – z. B. die Länderauswahl, damit kein EPG für ausgeblendete Länder lädt. */
    private val afterContent: suspend (Long) -> Unit = {},
) {
    suspend operator fun invoke(playlistId: Long, withEpg: Boolean = true, onProgress: (ImportProgress) -> Unit) {
        playlists.refreshContent(playlistId, onProgress)
        afterContent(playlistId)
        if (withEpg) refreshEpg(playlistId, onProgress)
    }

    suspend fun refreshEpg(playlistId: Long, onProgress: (ImportProgress) -> Unit) {
        val playlist = playlists.getPlaylist(playlistId) ?: return
        epg.refresh(playlist, onProgress)
    }
}
