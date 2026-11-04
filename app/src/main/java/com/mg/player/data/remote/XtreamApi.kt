package com.mg.player.data.remote

import android.net.Uri
import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.security.SecretCipher
import com.mg.player.data.db.entity.PlaylistEntity
import java.io.InputStream

/** URL-Bau und Stream-Zugriff für die Xtream-Codes-API. Alle Methoden blockieren (Dispatchers.IO). */
class XtreamApi(private val fetcher: HttpFetcher) {

    companion object {
        const val PLACEHOLDER = "xt://"
    }

    data class Credentials(
        val baseUrl: String,
        val username: String,
        val password: String,
        val allowInsecureHttp: Boolean = false,
    )

    fun credentials(playlist: PlaylistEntity) = Credentials(
        playlist.serverUrl,
        playlist.username,
        SecretCipher.decrypt(playlist.password),
        playlist.allowInsecureHttp,
    )

    private fun query(c: Credentials) = "username=${Uri.encode(c.username)}&password=${Uri.encode(c.password)}"

    fun playerApiUrl(c: Credentials, action: String? = null, extra: String = ""): String =
        "${c.baseUrl}/player_api.php?${query(c)}" + (action?.let { "&action=$it" } ?: "") + extra

    fun xmltvUrl(c: Credentials): String = "${c.baseUrl}/xmltv.php?${query(c)}"

    /**
     * In der Datenbank stehen nur Platzhalter wie "xt://5/live/123.ts" – ohne Zugangsdaten.
     * Die echte Adresse baut erst der [StreamUrlResolver] beim Abspielen.
     */
    fun livePlaceholder(playlistId: Long, streamId: String): String = "$PLACEHOLDER$playlistId/live/$streamId.ts"

    fun moviePlaceholder(playlistId: Long, streamId: String, extension: String): String = "$PLACEHOLDER$playlistId/movie/$streamId.$extension"

    fun episodePlaceholder(playlistId: Long, episodeId: String, extension: String): String = "$PLACEHOLDER$playlistId/series/$episodeId.$extension"

    /**
     * "live/123.ts" -> "http://server/live/user/pass/123.ts". Benutzer und Passwort werden als Pfadsegment
     * kodiert – ein Passwort mit `#`, `?`, `/` oder `%` würde die Adresse sonst zerschneiden.
     */
    fun streamUrl(c: Credentials, kind: String, file: String): String = "${c.baseUrl}/$kind/${Uri.encode(c.username)}/${Uri.encode(c.password)}/$file"

    fun open(url: String, allowInsecureHttp: Boolean = false): InputStream = fetcher.openStream(url, allowInsecureHttp)

    fun openAction(c: Credentials, action: String, extra: String = ""): InputStream = open(playerApiUrl(c, action, extra), c.allowInsecureHttp)

    fun openShortEpg(c: Credentials, streamId: String, limit: Int = 12): InputStream = openAction(c, "get_short_epg", "&stream_id=$streamId&limit=$limit")

    fun openSeriesInfo(c: Credentials, seriesId: String): InputStream = openAction(c, "get_series_info", "&series_id=$seriesId")

    fun openVodInfo(c: Credentials, vodId: String): InputStream = openAction(c, "get_vod_info", "&vod_id=$vodId")
}
