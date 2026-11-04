package com.mg.player.core.vpn

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Meldet jedes Mal, wenn das VPN (wieder) steht – nicht den Zustand beim Abonnieren. Verbindungen, die vorher über
 * die normale Leitung aufgebaut wurden (laufender Stream, Vorschau), sollen dann neu und damit durch den Tunnel gehen.
 */
fun VpnController.tunnelUps(): Flow<Unit> = state.map { it is VpnState.Connected }.distinctUntilChanged().drop(1).filter { it }.map { }

/** Meldungen nach außen: Benachrichtigung, Ton, Protokoll. */
interface VpnAlerts {
    fun dropped(profile: String, reason: VpnDropReason) {}
    fun restored(profile: String) {}
    fun cleared() {}

    /** Technischer Grund eines Abbruchs fürs Protokoll (welche Prüfung hat ausgelöst). */
    fun detail(text: String) {}
}

/**
 * Hält das VPN dauerhaft am Laufen: Aufbau beim Start, Überwachung über den letzten Handshake, Neuverbindung
 * mit wachsenden Pausen ([VpnBackoff]). Bewusst **ohne Notbremse**: Steht der Tunnel nicht, wird er abgebaut,
 * damit Streams über die normale Leitung weiterlaufen – der Zustand ist dann über [state] überall sichtbar.
 */
class VpnController(
    private val backend: VpnBackend?,
    private val store: VpnStore,
    private val scope: CoroutineScope,
    private val online: StateFlow<Boolean>,
    private val alerts: VpnAlerts = object : VpnAlerts {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Ist die App sichtbar? Android ab 8 lässt den VPN-Dienst nur dann starten (nicht nach Update/Neustart im Hintergrund). */
    private val isForeground: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow<VpnState>(if (backend == null) VpnState.Unsupported else VpnState.Off)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /** System-Dialog für die VPN-Erlaubnis – die Activity zeigt ihn, sobald sie sichtbar ist. */
    private val _permissionRequest = MutableStateFlow<Intent?>(null)
    val permissionRequest: StateFlow<Intent?> = _permissionRequest.asStateFlow()

    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val backendLock = Mutex()
    private var job: Job? = null

    /** In diesem Lauf abgelehnt: nicht bei jedem Zurückkehren in die App erneut fragen (nur per Knopf). */
    private var permissionDenied = false

    val supported: Boolean get() = backend != null

    /** Startet die Überwachung, wenn das VPN eingerichtet und eingeschaltet ist. Mehrfach aufrufbar. */
    fun start() {
        if (backend == null) return
        if (!store.enabled.value || store.active() == null) {
            stop()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch { runLoop(backend) }
    }

    /** Neu aufbauen (Knopf „Neu verbinden“, anderer Standort, neue Konfiguration). */
    fun restart() {
        permissionDenied = false
        job?.cancel()
        job = null
        start()
    }

    fun stop() {
        job?.cancel()
        job = null
        if (backend != null) scope.launch { withContext(NonCancellable) { backendLock.withLock { backend.down() } } }
        if (_state.value !is VpnState.Unsupported) _state.value = VpnState.Off
        _permissionRequest.value = null
        alerts.cleared()
    }

    fun setEnabled(on: Boolean) {
        store.setEnabled(on)
        if (on) restart() else stop()
    }

    /** Einschalten aus einem beliebigen Thread (z. B. nach dem Upload vom Handy). */
    fun enableAsync() {
        scope.launch { setEnabled(true) }
    }

    fun select(profileId: String) {
        store.select(profileId)
        if (store.enabled.value) restart()
    }

    /** Die Activity hat den System-Dialog geöffnet. */
    fun consumePermissionRequest() {
        _permissionRequest.value = null
    }

    fun onPermissionResult(granted: Boolean) {
        _permissionRequest.value = null
        if (granted) restart() else permissionDenied = true
    }

    /** App wieder sichtbar: fehlende Erlaubnis erneut erfragen bzw. eine laufende Pause abkürzen. */
    fun onForeground() = wake()

    /**
     * Starten oder eine laufende Wartezeit sofort beenden. Auch für „Durchgehend aktives VPN“: Android hat den
     * Dienst dann selbst gestartet – eine Schleife, die nach einem Hintergrundstart auf die App wartet, muss jetzt
     * verbinden, sonst bliebe das VPN bis zum Öffnen der App aus.
     */
    fun wake() {
        if (job?.isActive != true) start() else kick.trySend(Unit)
    }

    fun traffic(): VpnTraffic? = backend?.traffic()

    private suspend fun runLoop(b: VpnBackend) {
        var attempt = 0
        while (true) {
            val profile = store.active() ?: return stop()
            // Entschlüsseln (Keystore) nicht im Hauptthread – auf langsamen TV-Boxen sonst spürbare Hänger.
            val conf = withContext(io) { store.configText(profile.id) }
            if (conf == null) {
                markDown(profile, VpnDropReason.BAD_CONFIG)
                kick.receive()
                continue
            }
            if (!awaitOnline()) {
                markDown(profile, VpnDropReason.NO_NETWORK)
                online.first { it }
                attempt = 0
                continue
            }
            val intent = runCatching { b.permissionIntent() }.getOrNull()
            if (intent != null) {
                markDown(profile, VpnDropReason.PERMISSION)
                if (!permissionDenied) _permissionRequest.value = intent
                kick.receive()
                continue
            }
            _state.value = when (val current = _state.value) {
                is VpnState.Disconnected -> current.copy(retrying = true)
                else -> VpnState.Connecting(profile.name, profile.country)
            }
            val failure = try {
                connect(b, conf)
            } catch (e: BackgroundStartBlocked) {
                // Start nach Update/Neustart: Android verbietet den Dienst im Hintergrund. Kein Abbruch, keine
                // Benachrichtigung, keine Wiederholungen – verbunden wird, sobald die App sichtbar ist (onForeground).
                kick.receive()
                continue
            }
            if (failure == null) {
                markUp(profile)
                attempt = 0
                val upAt = clock()
                val reason = monitor(b)
                backendLock.withLock { b.down() }
                markDown(profile, reason)
                if (reason == VpnDropReason.STALE && shortSessions.onSessionEnded(clock() - upAt)) keyConflictPause()
                continue
            }
            markDown(profile, failure)
            when (failure) {
                VpnDropReason.PERMISSION -> continue
                VpnDropReason.BAD_CONFIG -> kick.receive()
                else -> withTimeoutOrNull(VpnBackoff.delayFor(attempt++)) { kick.receive() }
            }
        }
    }

    private val shortSessions = ShortSessions(clock)
    private var lastConflictNoteAt = 0L

    /** Gleicher Schlüssel auf zwei Geräten (vermutlich): einmal je Stunde erklären, dann mit Abstand neu verbinden. */
    private suspend fun keyConflictPause() {
        if (clock() - lastConflictNoteAt > CONFLICT_NOTE_MS) {
            lastConflictNoteAt = clock()
            alerts.detail(
                "Verbindung reißt immer nach rund 2 Minuten – vermutlich läuft derselbe VPN-Schlüssel gleichzeitig auf einem " +
                    "anderen Gerät (z. B. über eine Sicherung übertragen). Jedes Gerät braucht einen eigenen Schlüssel.",
            )
        }
        withTimeoutOrNull(ShortSessions.COOLDOWN_MS) { kick.receive() }
    }

    /** null = Tunnel steht und der Server hat geantwortet. */
    private suspend fun connect(b: VpnBackend, conf: String): VpnDropReason? {
        try {
            backendLock.withLock { b.up(conf) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VpnStartException) {
            if (e.cause is IllegalStateException && !isForeground()) throw BackgroundStartBlocked()
            return e.reason
        } catch (e: Exception) {
            return VpnDropReason.START_FAILED
        }
        val answered = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            while (withContext(io) { b.latestHandshakeMs() } <= 0L) delay(HANDSHAKE_POLL_MS)
            true
        } ?: false
        if (answered) return null
        backendLock.withLock { b.down() }
        return VpnDropReason.SERVER_UNREACHABLE
    }

    private class BackgroundStartBlocked : Exception()

    /** Läuft, solange der Tunnel steht; liefert den Grund, sobald er nicht mehr trägt. */
    private suspend fun monitor(b: VpnBackend): VpnDropReason {
        val health = TunnelHealth(clock)
        while (true) {
            delay(POLL_MS)
            if (!awaitOnline()) return VpnDropReason.NO_NETWORK
            val (up, handshake, traffic) = withContext(io) { Triple(b.isUp(), b.latestHandshakeMs(), b.traffic()) }
            if (!up) return VpnDropReason.REVOKED
            when (val verdict = health.judge(handshake, traffic)) {
                TunnelHealth.Verdict.Alive -> Unit

                // Tunnel wirkt eingeschlafen: ein Prüfpaket schicken, statt ihn gleich abzureißen.
                TunnelHealth.Verdict.Probe -> health.probed(withContext(io) { b.probe() })

                is TunnelHealth.Verdict.Dead -> {
                    alerts.detail("Abbruch erkannt: ${verdict.detail}")
                    return VpnDropReason.STALE
                }
            }
        }
    }

    /** Kurze Aussetzer (z. B. beim Umschalten auf das VPN-Netz) zählen nicht als „kein Netz“. */
    private suspend fun awaitOnline(): Boolean = online.value || withTimeoutOrNull(OFFLINE_GRACE_MS) { online.first { it } } != null

    private fun markUp(profile: VpnProfile) {
        val wasDown = (_state.value as? VpnState.Disconnected)?.announced == true
        _state.value = VpnState.Connected(profile.name, profile.country, clock())
        _permissionRequest.value = null
        if (wasDown) alerts.restored(profile.name)
    }

    private fun markDown(profile: VpnProfile, reason: VpnDropReason) {
        val current = _state.value
        if (current is VpnState.Disconnected && current.reason == reason && !current.retrying) return
        val since = (current as? VpnState.Disconnected)?.sinceMs ?: clock()
        // Fehlt beim Einrichten nur die Erlaubnis, ist das kein Abbruch (keine Benachrichtigung, kein „letzter Abbruch“).
        val setup = reason == VpnDropReason.PERMISSION && current !is VpnState.Connected
        val wasAnnounced = (current as? VpnState.Disconnected)?.announced ?: false
        _state.value = VpnState.Disconnected(profile.name, profile.country, reason, since, announced = wasAnnounced || !setup)
        if (!wasAnnounced && !setup) {
            store.recordDrop(since, reason.text)
            alerts.dropped(profile.name, reason)
        }
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val HANDSHAKE_POLL_MS = 250L
        const val POLL_MS = 3_000L

        /** Kurze Aussetzer beim Umschalten aufs VPN-Netz zählen nicht als „kein Netz“. */
        const val OFFLINE_GRACE_MS = 5_000L

        /** Hinweis auf doppelt genutzten Schlüssel höchstens so oft. */
        const val CONFLICT_NOTE_MS = 60 * 60_000L
    }
}
