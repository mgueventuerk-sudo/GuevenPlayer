package com.mg.player.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.mg.player.core.log.AppLog
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Übertragene Datenmenge seit dem Aufbau des Tunnels. */
data class VpnTraffic(val rxBytes: Long, val txBytes: Long)

class VpnStartException(val reason: VpnDropReason, cause: Throwable? = null) : Exception(reason.text, cause)

/** Der eigentliche Tunnel – austauschbar, damit sich der Ablauf ohne echtes VPN testen lässt. */
interface VpnBackend {
    /** System-Dialog für die VPN-Erlaubnis; null = schon erlaubt. */
    fun permissionIntent(): Intent?

    /** Baut den Tunnel auf (oder schaltet auf eine andere Konfiguration um). Wirft [VpnStartException]. */
    suspend fun up(configText: String)

    suspend fun down()

    fun isUp(): Boolean

    /** Zeitpunkt des letzten Handshakes mit dem Server (Epoch-ms), 0 = noch keiner. */
    fun latestHandshakeMs(): Long

    fun traffic(): VpnTraffic?

    /**
     * Schickt ein kleines Paket durch den Tunnel (DNS-Anfrage an den Tunnel-DNS). WireGuard handelt für jedes zu
     * sendende Paket von selbst neu aus – danach zeigt [latestHandshakeMs] bei einem lebenden Server einen frischen
     * Handshake. false = konnte nicht gesendet werden (dann nichts daraus schließen).
     */
    suspend fun probe(): Boolean = false
}

/** WireGuard im Nutzerbereich (wireguard-go) über Androids VpnService – kein Root nötig. */
class WireGuardBackend(private val context: Context, private val fallbackDns: Dns? = null) : VpnBackend {

    // Erst beim ersten Aufbau laden: die native Bibliothek kostet beim App-Start sonst Zeit.
    private val backend by lazy { GoBackend(context) }

    @Volatile
    private var up = false

    /** Wurde der Tunnel in diesem Prozess schon einmal gestartet (erst dann ist die native Bibliothek geladen)? */
    @Volatile
    private var used = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            up = newState == Tunnel.State.UP
        }
    }

    override fun permissionIntent(): Intent? = runCatching { VpnService.prepare(context) }.getOrNull()

    override suspend fun up(configText: String): Unit = withContext(Dispatchers.IO) {
        val parsed = VpnConfigParser.parse(configText).getOrElse { throw VpnStartException(VpnDropReason.BAD_CONFIG, it) }
        try {
            val config = VpnConfigParser.restrictToApp(parsed.config, context.packageName, ::resolve)
            probeTarget = config.`interface`.dnsServers.firstOrNull { it is Inet4Address } ?: config.`interface`.dnsServers.firstOrNull()
            used = true
            backend.setState(tunnel, Tunnel.State.UP, config)
            up = true
        } catch (e: BackendException) {
            // Grund ins Protokoll (nur der Name, nie Adressen oder Schlüssel) – hilft bei der Fehlersuche am Gerät.
            AppLog.log("VPN", "Start fehlgeschlagen: ${e.reason.name}")
            val reason = when (e.reason) {
                BackendException.Reason.VPN_NOT_AUTHORIZED -> VpnDropReason.PERMISSION
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> VpnDropReason.DNS_FAILED
                else -> VpnDropReason.START_FAILED
            }
            throw VpnStartException(reason, e)
        } catch (e: Throwable) {
            // Auch fehlende native Bibliothek (UnsatisfiedLinkError) – nie die App mitreißen.
            // Nur der Typ – die Meldung kann Serveradresse oder IP enthalten. Einzige Ausnahme: Android verbietet den
            // Dienst im Hintergrund (Start nach Update/Neustart), das ist fürs Protokoll wichtig und enthält nichts davon.
            val hint = if (e is IllegalStateException && e.message?.contains("background", ignoreCase = true) == true) {
                " (App im Hintergrund – Android erlaubt den VPN-Dienst erst beim Öffnen)"
            } else {
                ""
            }
            AppLog.log("VPN", "Start fehlgeschlagen: ${e.javaClass.simpleName}$hint")
            throw VpnStartException(VpnDropReason.START_FAILED, e)
        }
    }

    override suspend fun down(): Unit = withContext(Dispatchers.IO) {
        if (used) runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        up = false
    }

    override fun isUp(): Boolean = used && up && runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    override fun latestHandshakeMs(): Long = if (!used) {
        0L
    } else {
        runCatching {
            val stats = backend.getStatistics(tunnel)
            stats.peers().maxOfOrNull { key -> stats.peer(key)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
        }.getOrDefault(0L)
    }

    override fun traffic(): VpnTraffic? = if (!used) {
        null
    } else {
        runCatching {
            val stats = backend.getStatistics(tunnel)
            VpnTraffic(stats.totalRx(), stats.totalTx())
        }.getOrNull()
    }

    /**
     * Prüfpaket: eine DNS-Anfrage an den DNS-Server des Tunnels. Der Inhalt ist eine gültige Anfrage nach „.“,
     * damit der Server auch antworten kann; fürs Ergebnis genügt aber schon, dass gesendet wurde.
     */
    override suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        val target = probeTarget ?: return@withContext false
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.send(DatagramPacket(ROOT_DNS_QUERY, ROOT_DNS_QUERY.size, target, DNS_PORT))
            }
            true
        }.getOrDefault(false)
    }

    /** Servername → Adresse: System-DNS, sonst DNS über HTTPS; IPv4 bevorzugt (Surfshark-Server haben nur IPv4). */
    private fun resolve(host: String): InetAddress? {
        val candidates = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
            ?: fallbackDns?.let { dns -> runCatching { dns.lookup(host) }.getOrNull() }
            ?: return null.also { AppLog.log("VPN", "Serveradresse nicht auflösbar") }
        return candidates.firstOrNull { it is Inet4Address } ?: candidates.firstOrNull()
    }

    @Volatile
    private var probeTarget: InetAddress? = null

    companion object {
        const val TUNNEL_NAME = "guevenplayer"
        private const val DNS_PORT = 53
        private const val PROBE_TIMEOUT_MS = 2_000

        /** DNS-Anfrage „NS .“ mit fester Kennung – 17 Byte, keine Namensauflösung nötig. */
        private val ROOT_DNS_QUERY = byteArrayOf(
            0x4d, 0x47, // Kennung
            0x01, 0x00, // Standard-Anfrage, Rekursion erwünscht
            0x00, 0x01, // eine Frage
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, // Name: Wurzel
            0x00, 0x02, // Typ NS
            0x00, 0x01, // Klasse IN
        )
    }
}
