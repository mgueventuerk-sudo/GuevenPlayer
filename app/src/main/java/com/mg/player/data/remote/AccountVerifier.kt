package com.mg.player.data.remote

import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.util.UrlUtils
import com.mg.player.data.parser.XtreamDetailParser
import com.mg.player.data.sync.syncErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prüft Zugangsdaten, bevor sie gespeichert werden (Zugang bearbeiten): Xtream meldet sich mit `player_api.php` an,
 * eine M3U-Liste muss mit `#EXTM3U` bzw. `#EXTINF` beginnen. Liefert null bei Erfolg, sonst eine deutsche Meldung.
 * Zugangsdaten landen weder im Protokoll noch in der Meldung.
 */
class AccountVerifier(private val api: XtreamApi, private val fetcher: HttpFetcher) {

    /** [server], [username], [password] eines Xtream-Zugangs prüfen. */
    suspend fun verifyXtream(server: String, username: String, password: String, allowInsecureHttp: Boolean): String? = guarded {
        val c = XtreamApi.Credentials(UrlUtils.normalizeBaseUrl(server), username.trim(), password.trim(), allowInsecureHttp)
        val auth = api.open(api.playerApiUrl(c), c.allowInsecureHttp).use { XtreamDetailParser.parseAuth(it) }
        if (auth.authenticated) null else REJECTED
    }

    suspend fun verifyM3u(url: String, allowInsecureHttp: Boolean): String? = guarded {
        val head = fetcher.openMaybeGzip(url.trim(), allowInsecureHttp).use { input ->
            val buffer = ByteArray(HEAD_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        }
        if ("#EXTM3U" in head || "#EXTINF" in head) null else NOT_M3U
    }

    private suspend fun guarded(block: () -> String?): String? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncErrorText(e)
        }
    }

    companion object {
        const val REJECTED = "Anmeldung abgelehnt – Benutzername oder Passwort prüfen"
        const val NOT_M3U = "Unter dieser Adresse liegt keine M3U-Liste"
        private const val HEAD_BYTES = 4_096
    }
}
