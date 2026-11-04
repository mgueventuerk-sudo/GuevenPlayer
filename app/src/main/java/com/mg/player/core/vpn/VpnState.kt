package com.mg.player.core.vpn

import androidx.compose.runtime.Immutable

/** Warum das VPN gerade nicht steht – fürs rote Band, die Einstellungen und das Protokoll (ohne Adressen/Schlüssel). */
enum class VpnDropReason(val text: String) {
    NO_NETWORK("Kein Netzwerk"),
    SERVER_UNREACHABLE("VPN-Server antwortet nicht (Schlüssel ungültig, Server gesperrt oder gestört)"),
    STALE("Verbindung zum VPN-Server abgerissen"),
    PERMISSION("VPN-Erlaubnis fehlt – vermutlich ist ein anderes VPN aktiv"),
    REVOKED("Von Android beendet – vermutlich wurde ein anderes VPN gestartet"),
    START_FAILED("VPN ließ sich nicht starten"),
    DNS_FAILED("Adresse des VPN-Servers nicht auflösbar (DNS)"),
    BAD_CONFIG("Konfiguration ungültig – bitte neu einrichten"),
}

/** Gemeinsamer VPN-Zustand für Schild, rotes Band, Benachrichtigung und Einstellungen. */
@Immutable
sealed interface VpnState {

    /** Nicht eingerichtet oder bewusst ausgeschaltet: graues Schild, kein Band. */
    data object Off : VpnState

    /** Gerät zu alt (WireGuard braucht hier Android 7). */
    data object Unsupported : VpnState

    /** Erster Verbindungsaufbau nach dem Start: gelbes Schild. */
    data class Connecting(val profile: String, val country: String?) : VpnState

    data class Connected(val profile: String, val country: String?, val sinceMs: Long) : VpnState

    /** Getrennt: rotes Band, bis die Verbindung wieder steht. [retrying] = die App versucht es gerade erneut. */
    data class Disconnected(
        val profile: String,
        val country: String?,
        val reason: VpnDropReason,
        val sinceMs: Long,
        val retrying: Boolean = false,
        /** Als Abbruch gemeldet (Benachrichtigung). false = beim Einrichten fehlt nur die Erlaubnis. */
        val announced: Boolean = true,
    ) : VpnState
}

/** Pausen zwischen den Versuchen: sofort, 2, 5, 10, 30 s, danach jede Minute. */
object VpnBackoff {
    private val STEPS_MS = longArrayOf(0, 2_000, 5_000, 10_000, 30_000)
    const val STEADY_MS = 60_000L

    fun delayFor(attempt: Int): Long = if (attempt < STEPS_MS.size) STEPS_MS[attempt] else STEADY_MS
}
