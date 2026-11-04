package com.mg.player.data.sync

import android.util.MalformedJsonException
import androidx.compose.runtime.Immutable
import com.mg.player.core.log.AppLog
import com.mg.player.domain.repository.ImportProgress
import com.mg.player.domain.usecase.RefreshPlaylistUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParserException
import java.io.EOFException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@Immutable
data class SyncState(
    val running: Boolean = false,
    val message: String = "",
    val count: Int = 0,
    val error: String? = null,
    val finishedAtMs: Long = 0,
    /** Hinweis aus der Aktualisierung (z. B. "ungewöhnlich wenige Einträge"), bleibt nach dem Ende sichtbar. */
    val warning: String? = null,
    /** Bei „Alle Zugänge“: Position wie „2/3“ (für die kleine Anzeige oben), sonst null. */
    val step: String? = null,
)

/**
 * Führt Playlist-/EPG-Aktualisierungen im App-Scope aus, damit sie weiterlaufen,
 * während der Nutzer schon durch die Oberfläche navigiert.
 */
class SyncManager(
    private val scope: CoroutineScope,
    /** (Zugang, mit EPG?, Fortschritt) – Inhalte und optional EPG laden. */
    private val loadContent: suspend (Long, Boolean, (ImportProgress) -> Unit) -> Unit,
    /** (Zugang, Fortschritt) – nur EPG laden. */
    private val loadEpg: suspend (Long, (ImportProgress) -> Unit) -> Unit,
    /** Wartezeiten vor erneuten Versuchen bei Netzabbruch (Protokoll 04.11.: „Software caused connection abort“). */
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    constructor(scope: CoroutineScope, refresh: RefreshPlaylistUseCase) : this(
        scope,
        { id, withEpg, progress -> refresh(id, withEpg, progress) },
        { id, progress -> refresh.refreshEpg(id, progress) },
    )

    /** Wird nach jeder Aktualisierung aufgerufen (z. B. um zwischengespeicherte Zugangsdaten zu verwerfen). */
    var onFinished: ((playlistId: Long) -> Unit)? = null

    /** Was eine Aktualisierung umfasst – entscheidet, ob eine laufende eine neue Anfrage schon abdeckt. */
    enum class Kind { ALL, CONTENT, EPG }

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private var job: Job? = null
    private var jobPlaylistId: Long? = null
    private var jobKind: Kind? = null

    fun refreshAll(playlistId: Long): Job = launchSync(playlistId, Kind.ALL) { loadContent(playlistId, true, ::onProgress) }

    /** Nur Sender, Filme und Serien – das EPG folgt nach der Länderauswahl ([refreshEpg]). */
    fun refreshContent(playlistId: Long): Job = launchSync(playlistId, Kind.CONTENT) { loadContent(playlistId, false, ::onProgress) }

    fun refreshEpg(playlistId: Long): Job = launchSync(playlistId, Kind.EPG) { loadEpg(playlistId, ::onProgress) }

    /**
     * Alle Zugänge nacheinander mit EPG – ein Job im Hintergrund. Schlägt einer fehl, laufen die übrigen weiter;
     * am Ende steht, welche nicht geklappt haben. Liefert null, wenn es keine Zugänge gibt.
     */
    fun refreshEverything(playlists: List<Pair<Long, String>>): Job? {
        if (playlists.isEmpty()) return null
        return launchSync(EVERYTHING, Kind.ALL) {
            val failed = ArrayList<String>()
            playlists.forEachIndexed { index, (id, name) ->
                prefix = if (playlists.size > 1) "$name: " else ""
                _state.value = _state.value.copy(step = "${index + 1}/${playlists.size}", message = prefix + "Starte…", count = 0)
                if (!loadWithRetry(id, name)) failed += name
                onFinished?.invoke(id)
            }
            prefix = ""
            _state.value = _state.value.copy(step = null)
            if (failed.isNotEmpty()) throw PartialSyncException(failed, playlists.size)
            doneMessage = if (playlists.size > 1) "Alle ${playlists.size} Zugänge aktualisiert" else null
        }
    }

    /**
     * Einen Zugang laden; reißt die Verbindung ab (WLAN-Aussetzer, VPN-Neuaufbau), wird nach [retryDelaysMs] erneut
     * versucht. Andere Fehler (Anmeldung abgelehnt, kaputte Liste) werden nicht wiederholt. true = geklappt.
     */
    private suspend fun loadWithRetry(id: Long, name: String): Boolean {
        for (attempt in 0..retryDelaysMs.size) {
            try {
                loadContent(id, true, ::onProgress)
                return true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                val again = attempt < retryDelaysMs.size
                AppLog.error("Sync", "Aktualisierung fehlgeschlagen ($name)" + if (again) " – neuer Versuch folgt" else "", e)
                if (!again) return false
                _state.value = _state.value.copy(message = prefix + "Verbindung abgerissen – neuer Versuch …")
                kotlinx.coroutines.delay(retryDelaysMs[attempt])
            } catch (e: Exception) {
                AppLog.error("Sync", "Aktualisierung fehlgeschlagen ($name)", e)
                return false
            }
        }
        return false
    }

    /** Vor jede Fortschrittsmeldung (Name des Zugangs bei „Alle Zugänge“). */
    private var prefix = ""
    private var doneMessage: String? = null

    private fun onProgress(progress: ImportProgress) {
        _state.value = _state.value.copy(
            message = prefix + progress.message,
            count = progress.count,
            warning = progress.warning ?: _state.value.warning,
        )
    }

    @Synchronized
    private fun launchSync(playlistId: Long, kind: Kind, block: suspend () -> Unit): Job {
        val previous = job?.takeIf { it.isActive }
        // Deckt die laufende Aktualisierung dieses Zugangs die Anfrage schon ab, wird sie wiederverwendet.
        // Sonst (z. B. EPG angefordert, während nur die Inhalte laden) stellt sich die neue dahinter an –
        // früher wurde dann einfach die laufende zurückgegeben und das EPG kam nie.
        if (previous != null && jobPlaylistId == playlistId && covers(jobKind, kind)) return previous
        // „Alle Zugänge“ läuft schon: deckt eine komplette Aktualisierung eines Zugangs mit ab (nur EPG z. B. nach
        // geänderter Länderauswahl stellt sich dagegen dahinter an).
        if (previous != null && jobPlaylistId == EVERYTHING && kind == Kind.ALL) return previous
        jobPlaylistId = playlistId
        jobKind = kind
        return scope.launch {
            // Wird gerade ein anderer Zugang geladen, stellt sich dieser dahinter an statt parallel zu laden.
            previous?.join()
            _state.value = SyncState(running = true, message = "Starte Aktualisierung…")
            doneMessage = null
            val error = try {
                block()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("Sync", "Aktualisierung fehlgeschlagen", e)
                syncErrorText(e)
            } catch (e: OutOfMemoryError) {
                // Lieber eine klare Meldung als ein App-Absturz mitten im Import.
                AppLog.log("Sync", "Zu wenig Arbeitsspeicher beim Import")
                "Zu wenig Arbeitsspeicher – bitte andere Apps schließen und erneut versuchen"
            }
            if (error == null) AppLog.log("Sync", "Aktualisierung abgeschlossen")
            if (playlistId != EVERYTHING) onFinished?.invoke(playlistId)
            _state.value = _state.value.copy(
                running = false,
                error = error,
                message = when {
                    error != null -> "Fehler: $error"
                    _state.value.warning != null -> "Aktualisiert – ${_state.value.warning}"
                    else -> doneMessage ?: "Aktualisierung abgeschlossen"
                },
                finishedAtMs = System.currentTimeMillis(),
            )
        }.also { job = it }
    }

    private fun covers(running: Kind?, requested: Kind): Boolean = running == requested || running == Kind.ALL

    companion object {
        /** Kennung des Jobs „Alle Zugänge“ (keine echte Zugangs-ID). */
        const val EVERYTHING = -1L

        /** Erneute Versuche nach Netzabbruch: nach 20 s und nach 60 s. */
        val DEFAULT_RETRY_DELAYS_MS = listOf(20_000L, 60_000L)
    }

    fun clearMessage() {
        if (!_state.value.running) _state.value = SyncState()
    }
}

/** Einzelne Zugänge sind fehlgeschlagen – die übrigen wurden aktualisiert. */
class PartialSyncException(failed: List<String>, total: Int) :
    Exception(if (failed.size == total) "Kein Zugang erreichbar" else "${total - failed.size} von $total aktualisiert, Fehler bei: ${failed.joinToString()}")

/**
 * Netzwerkfehler verständlich auf Deutsch – vorher stand dort die rohe englische Meldung
 * ("Failed to connect to /1.2.3.4:8080"). Eigene Meldungen (z. B. Anmeldung abgelehnt) bleiben unverändert.
 * Abgebrochene oder kaputte Antworten (3.23, virtuelles Labor: „Unterminated string at line 1 column 41“,
 * „unexpected end of stream“) bekommen ebenfalls einen deutschen Satz.
 */
internal fun syncErrorText(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }.take(5).toList()
    val network = causes.firstOrNull {
        it is UnknownHostException || it is ConnectException ||
            it is SocketTimeoutException || it is SSLException
    }
    if (network == null && causes.any { it.isBrokenResponse() }) return BROKEN_RESPONSE
    return when (network) {
        is UnknownHostException -> "Server nicht gefunden – Adresse und Internetverbindung prüfen"
        is ConnectException -> "Server nicht erreichbar – Adresse und Port prüfen"
        is SocketTimeoutException -> "Server antwortet nicht (Zeitüberschreitung) – später erneut versuchen"
        is SSLException -> "Sichere Verbindung (HTTPS) fehlgeschlagen – ggf. http:// statt https:// verwenden"
        else -> e.message ?: "Unbekannter Fehler"
    }
}

internal const val BROKEN_RESPONSE = "Antwort des Servers unvollständig oder beschädigt – später erneut versuchen"

private fun Throwable.isBrokenResponse() =
    this is MalformedJsonException || this is EOFException || this is ProtocolException || this is XmlPullParserException
