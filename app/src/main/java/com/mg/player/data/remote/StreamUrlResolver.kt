package com.mg.player.data.remote

import com.mg.player.core.security.SecretCipher
import com.mg.player.core.util.StreamTarget
import com.mg.player.data.combined.Combined
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.importer.XtreamImporter
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.playlistIdOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Setzt erst beim Abspielen die echte Stream-Adresse zusammen. In der Datenbank liegen
 * für Xtream nur Platzhalter ohne Zugangsdaten.
 */
class StreamUrlResolver(private val db: AppDatabase, private val api: XtreamApi) {

    private val credentials = ConcurrentHashMap<Long, XtreamApi.Credentials>()
    private val m3uCredentials = ConcurrentHashMap<Long, XtreamApi.Credentials>()

    suspend fun isHttpAllowed(ownerId: String): Boolean {
        if (Combined.isCombinedItem(ownerId)) return bestSource(ownerId)?.let { isHttpAllowed(it.first) } == true
        val playlistId = playlistIdOf(ownerId) ?: return false
        return withContext(Dispatchers.IO) { db.playlistDao().get(playlistId)?.allowInsecureHttp == true }
    }

    /**
     * [ownerId] = ID des Senders/Films/der Folge („12:L:105“): Daraus kommt der Zugang für die Zugangsdaten
     * von M3U-Adressen mit Platzhaltern ([CredentialMask]).
     */
    suspend fun resolve(url: String, ownerId: String? = null): String {
        // „Alle Zugänge“ hat keine eigene Adresse: die beste noch vorhandene Quelle abspielen (Aufnahme, Vorschau …).
        if (ownerId != null && Combined.isCombinedItem(ownerId)) {
            val (sourceId, sourceUrl) = bestSource(ownerId) ?: throw IOException("Für diesen Eintrag ist in keinem Zugang mehr eine Quelle vorhanden")
            return resolve(sourceUrl, sourceId)
        }
        val storedUrl = url
        val plainUrl = StreamSecretStorage.reveal(url)
        val ownerPlaylistId = ownerId?.let(::playlistIdOf)
        if (!StreamSecretStorage.isProtected(storedUrl) && ownerId != null && StreamSecretStorage.containsSecret(plainUrl)) {
            val encrypted = runCatching { StreamSecretStorage.protect(plainUrl) }
                .getOrElse { throw IOException("Stream-Zugangsdaten konnten nicht sicher gespeichert werden", it) }
            persistProtectedStream(ownerId, encrypted)
        }
        if (CredentialMask.isMasked(plainUrl)) {
            val playlistId = ownerPlaylistId ?: return plainUrl
            val creds = m3uCredentialsFor(playlistId) ?: return plainUrl
            return requireHttpAllowed(playlistId, CredentialMask.reveal(plainUrl, creds.username, creds.password))
        }
        if (!plainUrl.startsWith(XtreamApi.PLACEHOLDER)) {
            return requireHttpAllowed(ownerPlaylistId, plainUrl)
        }
        val rest = plainUrl.removePrefix(XtreamApi.PLACEHOLDER)
        val playlistId = rest.substringBefore('/').toLongOrNull() ?: return url
        val path = rest.substringAfter('/')
        val creds = credentialsFor(playlistId) ?: return url
        return requireHttpAllowed(playlistId, api.streamUrl(creds, path.substringBefore('/'), path.substringAfter('/')))
    }

    /** Adresse für eine vergangene (oder laufende) Sendung – null, wenn der Sender kein Archiv hat. */
    suspend fun catchupUrl(channelId: String, startMs: Long, endMs: Long): String? = withContext(Dispatchers.IO) {
        if (Combined.isCombinedItem(channelId)) {
            // Archiv aus der ersten Quelle, die diese Sendung im Archiv hat.
            for (source in db.combinedDao().sources(channelId)) catchupUrl(source.itemId, startMs, endMs)?.let { return@withContext it }
            return@withContext null
        }
        val channel = db.channelDao().entity(channelId) ?: return@withContext null
        val now = System.currentTimeMillis()
        if (!isInArchive(channel.catchupDays, startMs, now)) return@withContext null
        if (channel.catchupType == XtreamImporter.CATCHUP_XTREAM) {
            val playlist = db.playlistDao().get(channel.playlistId) ?: return@withContext null
            val creds = credentialsFor(channel.playlistId) ?: return@withContext null
            // Wie Live/Film/Serie über die HTTP-Freigabe des Zugangs: Benutzername und Passwort stehen im Pfad, und nur
            // Media3Engine sperrt Klartext selbst - LibVLC und die System-Engine spielten sonst auch ohne Freigabe.
            requireHttpAllowed(
                channel.playlistId,
                CatchupUrlBuilder.xtream(
                    baseUrl = creds.baseUrl,
                    username = creds.username,
                    password = creds.password,
                    streamId = channel.remoteId,
                    startMs = startMs,
                    endMs = endMs,
                    serverTimezone = playlist.serverTimezone.ifBlank { TimeZone.getDefault().id },
                ),
            )
        } else {
            // Header ("|User-Agent=…") abtrennen, Catch-up-Adresse bauen, Header wieder anhängen.
            val target = StreamTarget.parse(resolve(channel.streamUrl, channel.id))
            val storedSource = channel.catchupSource
            val source = storedSource?.let(StreamSecretStorage::reveal)
            migrateCatchupSourceIfNeeded(channelId, storedSource, source)
            CatchupUrlBuilder.m3u(target.url, channel.catchupType, source, startMs, endMs, now)
                ?.let { StreamTarget.withHeaders(it, target.headers) }
        }
    }

    /** Erste noch vorhandene Quelle eines Eintrags aus „Alle Zugänge“: (ID, gespeicherte Adresse). */
    private suspend fun bestSource(combinedId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        db.combinedDao().sources(combinedId).firstNotNullOfOrNull { s ->
            when {
                ":L:" in s.itemId -> db.channelDao().entity(s.itemId)?.streamUrl
                ":M:" in s.itemId -> db.vodDao().get(s.itemId)?.streamUrl
                ":E:" in s.itemId -> db.seriesDao().episode(s.itemId)?.streamUrl
                else -> null
            }?.let { s.itemId to it }
        }
    }

    fun clear() {
        credentials.clear()
        m3uCredentials.clear()
    }

    private suspend fun migrateCatchupSourceIfNeeded(channelId: String, stored: String?, source: String?) {
        if (stored == null || source == null || StreamSecretStorage.isProtected(stored)) return
        if (!StreamSecretStorage.containsSecret(source)) return
        val encrypted = StreamSecretStorage.protect(source)
        withContext(Dispatchers.IO) { db.channelDao().updateCatchupSource(channelId, encrypted) }
    }

    private suspend fun m3uCredentialsFor(playlistId: Long): XtreamApi.Credentials? {
        m3uCredentials[playlistId]?.let { return it }
        val playlist = withContext(Dispatchers.IO) { db.playlistDao().get(playlistId) } ?: return null
        val creds = if (playlist.type == PlaylistType.XTREAM.name) {
            api.credentials(playlist)
        } else {
            ConnectionMonitor.credentialsFromM3u(SecretCipher.decrypt(playlist.m3uUrl))
        } ?: return null
        return creds.also { m3uCredentials[playlistId] = it }
    }

    private suspend fun credentialsFor(playlistId: Long): XtreamApi.Credentials? {
        credentials[playlistId]?.let { return it }
        val playlist = withContext(Dispatchers.IO) { db.playlistDao().get(playlistId) } ?: return null
        return api.credentials(playlist).also { credentials[playlistId] = it }
    }

    private suspend fun requireHttpAllowed(playlistId: Long?, target: String): String {
        if (!target.substringBefore(':').equals("http", ignoreCase = true)) return target
        val allowed = playlistId?.let { withContext(Dispatchers.IO) { db.playlistDao().get(it)?.allowInsecureHttp } } == true
        if (!allowed) throw IOException("HTTP ist für diesen IPTV-Zugang nicht freigegeben. Aktiviere die Freigabe in den Zugangseinstellungen.")
        return target
    }

    private suspend fun persistProtectedStream(ownerId: String, encrypted: String) = withContext(Dispatchers.IO) {
        when {
            ":L:" in ownerId -> db.channelDao().updateStreamUrl(ownerId, encrypted)
            ":M:" in ownerId -> db.vodDao().updateStreamUrl(ownerId, encrypted)
            ":E:" in ownerId -> db.seriesDao().updateEpisodeStreamUrl(ownerId, encrypted)
        }
    }

    companion object {
        private const val DAY = 24 * 3_600_000L

        fun isInArchive(catchupDays: Int, startMs: Long, nowMs: Long): Boolean = catchupDays > 0 && startMs < nowMs && startMs >= nowMs - catchupDays * DAY
    }
}
