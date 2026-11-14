package com.mg.player.ui.player

import com.mg.player.core.network.NetworkStatus
import com.mg.player.player.engine.ErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Was der Player gerade zur Fehlerbehebung tut – für Spinner-Text und Fehlermeldung. */
data class RecoveryState(
    val reconnecting: Boolean = false,
    val waitingForNetwork: Boolean = false,
    /** Endgültige Meldung, wenn alle Versuche ausgeschöpft sind. */
    val failure: String? = null,
)

/**
 * Fehlerbehandlung der Wiedergabe:
 * - Netzwerkfehler: Neuversuche mit wachsender Pause (1, 2, 4, 8, 8, 8 s).
 * - HTTP 401/403: zwei kurze Versuche (beim schnellen Zappen zählt der Anbieter die alte Verbindung oft noch
 *   eine Sekunde mit), dann eine klare Meldung statt sinnloser Schleifen.
 * - HTTP 404: ein Versuch, dann "nicht verfügbar".
 * - Kein Netz: Es wird nicht gezählt, sondern gewartet, bis die Verbindung zurück ist.
 * - Hängt ein Stream im Puffern (LibVLC meldet dann oft gar keinen Fehler), greift ein Watchdog.
 * - Nach dem Aufgeben startet die Wiedergabe von selbst, sobald sich das Netzwerk ändert (z. B. Router neu gestartet).
 * - Der Zähler sinkt erst nach 30 s stabiler Wiedergabe – kurzes Anlaufen und Abbrechen läuft nicht endlos.
 */
class PlaybackRecovery(
    private val scope: CoroutineScope,
    private val network: StateFlow<NetworkStatus>,
    private val retry: () -> Unit,
    private val onState: (RecoveryState) -> Unit,
) {
    private var attempts = 0
    private var retryJob: Job? = null
    private var stallJob: Job? = null
    private var networkJob: Job? = null
    private var stableJob: Job? = null

    /** Neuversuche seit der letzten stabilen Wiedergabe (für die Entscheidung "Quelle wechseln?"). */
    val attemptCount: Int get() = attempts

    /** Neuer Inhalt: alles zurücksetzen. */
    fun reset() {
        cancelAll()
        attempts = 0
        onState(RecoveryState())
    }

    /**
     * Bild läuft: Watchdog aus, Spinner weg. Der Zähler wird aber erst nach 30 s stabiler Wiedergabe
     * zurückgesetzt – sonst würde ein Stream, der immer nur kurz anläuft und abbricht (z. B. weil der Anbieter
     * das Verbindungslimit durchsetzt), endlos im Sekundentakt neu verbunden.
     */
    fun onPlaying() {
        stallJob?.cancel()
        networkJob?.cancel()
        onState(RecoveryState())
        if (attempts == 0 || stableJob?.isActive == true) return
        stableJob = scope.launch {
            delay(STABLE_PLAYBACK_MS)
            attempts = 0
        }
    }

    /** Puffern hat begonnen: Dauert es zu lange, wird neu verbunden. */
    fun onBuffering(isLive: Boolean) {
        if (stallJob?.isActive == true || retryJob?.isActive == true) return
        stallJob = scope.launch {
            delay(if (isLive) LIVE_STALL_MS else VOD_STALL_MS)
            onError(ErrorKind.NETWORK, null)
        }
    }

    /** Pausiert oder fertig gepuffert, aber angehalten: kein Watchdog nötig. */
    fun onIdle() {
        stallJob?.cancel()
    }

    fun onError(kind: ErrorKind, httpCode: Int?) {
        stallJob?.cancel()
        stableJob?.cancel()
        if (retryJob?.isActive == true) return
        if (!network.value.online) {
            waitForNetwork()
            return
        }
        val max = maxAttempts(kind)
        if (attempts >= max) {
            giveUp(kind, httpCode)
            return
        }
        val pause = when (kind) {
            ErrorKind.AUTH -> 1_500L
            ErrorKind.NOT_FOUND -> 1_000L
            else -> 1_000L shl attempts.coerceAtMost(3)
        }
        attempts++
        onState(RecoveryState(reconnecting = true))
        retryJob = scope.launch {
            delay(pause)
            retry()
        }
    }

    fun release() = cancelAll()

    private fun waitForNetwork() {
        onState(RecoveryState(reconnecting = true, waitingForNetwork = true))
        if (networkJob?.isActive == true) return
        networkJob = scope.launch {
            network.first { it.online }
            // Kurz warten: DHCP/DNS brauchen nach dem Wiederverbinden einen Moment.
            delay(NETWORK_SETTLE_MS)
            attempts = 0
            retry()
        }
    }

    private fun giveUp(kind: ErrorKind, httpCode: Int?) {
        onState(RecoveryState(failure = message(kind, httpCode)))
        if (kind == ErrorKind.AUTH || kind == ErrorKind.NOT_FOUND || kind == ErrorKind.FORMAT) return
        // Verbindungsprobleme: beim nächsten Netzwechsel von selbst erneut versuchen.
        networkJob?.cancel()
        networkJob = scope.launch {
            // Nur echte Wechsel zählen (online, WLAN/LAN, VPN) – nicht jede Schwankung der WLAN-Geschwindigkeit.
            network.map { Triple(it.online, it.transport, it.vpnForApp) }.distinctUntilChanged().drop(1).first { it.first }
            delay(NETWORK_SETTLE_MS)
            attempts = 0
            onState(RecoveryState(reconnecting = true))
            retry()
        }
    }

    private fun cancelAll() {
        retryJob?.cancel()
        stallJob?.cancel()
        networkJob?.cancel()
        stableJob?.cancel()
    }

    companion object {
        const val LIVE_STALL_MS = 20_000L
        const val VOD_STALL_MS = 30_000L
        const val STABLE_PLAYBACK_MS = 30_000L
        private const val NETWORK_SETTLE_MS = 1_500L

        fun maxAttempts(kind: ErrorKind): Int = when (kind) {
            ErrorKind.AUTH -> 2
            ErrorKind.NOT_FOUND -> 1
            ErrorKind.FORMAT -> 0
            else -> 6
        }

        fun message(kind: ErrorKind, httpCode: Int?): String {
            val code = httpCode?.let { " (HTTP $it)" }.orEmpty()
            return when (kind) {
                ErrorKind.AUTH -> "Zugriff verweigert$code – Abo abgelaufen, gesperrt oder zu viele gleichzeitige Verbindungen."
                ErrorKind.NOT_FOUND -> "Dieser Inhalt ist beim Anbieter nicht (mehr) verfügbar$code."
                ErrorKind.FORMAT -> "Dieses Format kann die gewählte Player-Engine nicht abspielen – im Player-Menü eine andere Engine wählen."
                ErrorKind.NETWORK -> "Keine Verbindung zum Stream$code. Die Wiedergabe startet automatisch, sobald das Netzwerk wieder stabil ist."
                else -> "Die Wiedergabe ist fehlgeschlagen$code – bitte erneut versuchen oder im Player-Menü eine andere Quelle oder Engine wählen."
            }
        }
    }
}

/**
 * „Bild steht, Ton läuft“: Die Engine spielt, aber seit [FROZEN_MS] kommt kein neues Videobild. Greift nur, wenn
 * die Engine Bilder meldet und vorher schon Bilder liefen (Radiosender ohne Bild lösen nie aus).
 */
class FreezeWatchdog(private val clock: () -> Long = System::currentTimeMillis) {
    private var lastFrames = -1L
    private var changedAt = 0L
    private var hadVideo = false

    fun isFrozen(playing: Boolean, frames: Long?): Boolean {
        val now = clock()
        if (!playing || frames == null) {
            lastFrames = -1L
            hadVideo = false
            return false
        }
        if (frames != lastFrames) {
            if (lastFrames in 0 until frames) hadVideo = true
            lastFrames = frames
            changedAt = now
            return false
        }
        if (!hadVideo || now - changedAt < FROZEN_MS) return false
        // Auslösen und neu beginnen: erst wenn wieder Bilder kamen, kann es erneut anschlagen.
        lastFrames = -1L
        hadVideo = false
        return true
    }

    companion object {
        const val FROZEN_MS = 12_000L
    }
}
