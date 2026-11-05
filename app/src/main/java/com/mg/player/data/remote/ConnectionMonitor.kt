package com.mg.player.data.remote

import androidx.core.net.toUri
import com.mg.player.core.log.AppLog
import com.mg.player.core.security.SecretCipher
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.db.entity.PlaylistEntity
import com.mg.player.data.parser.XtreamAccountParser
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.PlaylistType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Fragt beim Anbieter ab, wie viele Verbindungen je Zugang gerade belegt sind ("1/1"). Geht bei Xtream-Zugängen
 * und bei M3U-Links im Xtream-Format (get.php?username=…&password=…); andere M3U-Listen haben keine solche Angabe.
 * Abfragen sind gedrosselt: Mehrere Bildschirme gleichzeitig lösen höchstens alle [MIN_INTERVAL_MS] eine aus.
 */
class ConnectionMonitor(
    private val db: AppDatabase,
    private val api: XtreamApi,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _status = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())

    /** Letzter bekannter Stand je Zugang; fehlt ein Eintrag, ist er unbekannt (M3U, offline, Fehler). */
    val status: StateFlow<Map<Long, ConnectionStatus>> = _status.asStateFlow()

    /**
     * Wie [status], hält den Stand aber aktuell: Solange jemand zusieht (ein Bildschirm mit Zugängen ist sichtbar),
     * wird alle [POLL_MS] neu abgefragt. Ohne Zuschauer ruht die Abfrage – im Hintergrund kein Netzverkehr.
     */
    val live: StateFlow<Map<Long, ConnectionStatus>> = flow {
        coroutineScope {
            launch {
                while (true) {
                    try {
                        refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.error("Verbindungen", "Abfrage fehlgeschlagen", e)
                    }
                    delay(POLL_MS)
                }
            }
            emitAll(_status)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val lastFetch = ConcurrentHashMap<Long, Long>()

    /** Fehlschläge in Folge je Zugang: Ein toter Zugang wird mit wachsender Pause (bis 5 min) abgefragt. */
    private val failures = ConcurrentHashMap<Long, Int>()

    /** Wann dieses Gerät zuletzt über einen Zugang abgespielt hat (eigene Verbindung, keine fremde). */
    private val lastOwnUse = ConcurrentHashMap<Long, Long>()

    /**
     * Dieses Gerät nutzt den Zugang gerade selbst: beim Start, regelmäßig während der Wiedergabe und am Ende aufrufen –
     * vom Vollbild-Player ebenso wie von Vorschau, Mosaik und „Läuft gerade“ (ab 3.45; vorher nur der Player, deshalb
     * hielt die App die eigene Vorschau für ein fremdes Gerät und fragte „Zugang belegt“).
     */
    fun markOwnUse(playlistId: Long) {
        lastOwnUse[playlistId] = clock()
    }

    /**
     * Hat dieses Gerät in den letzten [OWN_USE_GRACE_MS] über den Zugang geschaut? Dann stammt „belegt“ sehr
     * wahrscheinlich von der eigenen, gerade beendeten Wiedergabe (Anbieter geben Verbindungen verzögert frei).
     */
    fun recentlyUsedHere(playlistId: Long): Boolean = lastOwnUse[playlistId]?.let { clock() - it < OWN_USE_GRACE_MS } == true

    /** Frische Einzelabfrage (vor dem Abspielen); null = unbekannt (M3U ohne Angaben, offline, Zeitüberschreitung). */
    suspend fun checkNow(playlistId: Long, timeoutMs: Long = 1_500): ConnectionStatus? {
        // Eigener Job: Ein blockierender HTTP-Aufruf lässt sich nicht abbrechen – mit withContext hätte die Frist erst nach
        // dem Ende des Aufrufs gegriffen (bis zur vollen Netz-Zeitüberschreitung vor dem Abspielen).
        val fetch = scope.async(Dispatchers.IO) { db.playlistDao().get(playlistId)?.let { refreshOne(it, force = true) } }
        return withTimeoutOrNull(timeoutMs) {
            fetch.await()
            _status.value[playlistId]
        }
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val playlists = db.playlistDao().all()
        val ids = playlists.map { it.id }.toSet()
        _status.update { map -> map.filterKeys { it in ids } }
        coroutineScope {
            playlists.map { playlist -> async { refreshOne(playlist, force) } }.awaitAll()
        }
    }

    private fun refreshOne(playlist: PlaylistEntity, force: Boolean) {
        val now = clock()
        val last = lastFetch[playlist.id]
        if (!force && last != null && now - last < intervalFor(playlist.id)) return
        lastFetch[playlist.id] = now
        val credentials = credentialsFor(playlist) ?: return
        val result = try {
            api.open(api.playerApiUrl(credentials), credentials.allowInsecureHttp).use { XtreamAccountParser.parse(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val count = (failures[playlist.id] ?: 0) + 1
            failures[playlist.id] = count
            // Nur der erste Fehlschlag einer Serie ins Protokoll (früher alle 30 s eine Zeile).
            if (count == 1) AppLog.log("Verbindungen", "${playlist.name}: nicht abrufbar (${e.javaClass.simpleName})")
            null
        }
        if (result != null) failures.remove(playlist.id)
        _status.update { map -> if (result == null) map - playlist.id else map + (playlist.id to result) }
    }

    private fun credentialsFor(playlist: PlaylistEntity): XtreamApi.Credentials? = when (playlist.type) {
        PlaylistType.XTREAM.name -> api.credentials(playlist)
        else -> runCatching { credentialsFromM3u(SecretCipher.decrypt(playlist.m3uUrl))?.copy(allowInsecureHttp = playlist.allowInsecureHttp) }.getOrNull()
    }

    /** 15 s im Normalfall; nach Fehlschlägen 30 s, 1, 2, 4, höchstens 5 min. */
    internal fun intervalFor(playlistId: Long): Long = backoffMs(failures[playlistId] ?: 0)

    companion object {
        const val MIN_INTERVAL_MS = 15_000L
        const val MAX_BACKOFF_MS = 300_000L
        private const val MAX_DOUBLINGS = 4

        fun backoffMs(failures: Int): Long = if (failures <=
            0
        ) {
            MIN_INTERVAL_MS
        } else {
            (POLL_MS shl (failures - 1).coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_BACKOFF_MS)
        }

        const val POLL_MS = 30_000L
        // 3 min (vorher 90 s): Viele Anbieter zählen eine beendete Verbindung noch ein, zwei Minuten mit.
        const val OWN_USE_GRACE_MS = 180_000L

        /** "http://host:8080/get.php?username=a&password=b&type=m3u_plus" -> Zugangsdaten für player_api.php. */
        fun credentialsFromM3u(url: String): XtreamApi.Credentials? {
            val uri = url.trim().toUri()
            val user = uri.getQueryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
            val pass = uri.getQueryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return XtreamApi.Credentials("$scheme://$host$port", user, pass)
        }
    }
}
