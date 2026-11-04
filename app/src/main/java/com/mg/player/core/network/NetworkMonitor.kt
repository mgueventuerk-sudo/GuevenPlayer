package com.mg.player.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface

enum class Transport(val label: String) {
    WIFI("WLAN"),
    ETHERNET("LAN-Kabel"),
    CELLULAR("Mobilfunk"),
    OTHER("Netzwerk"),
    NONE("Keine Verbindung"),
}

/**
 * Netzwerkzustand für Reconnect und Stream-Info.
 * [vpnForApp]: Der Verkehr dieser App läuft über ein VPN. [vpnOnDevice]: Auf dem Gerät ist irgendein VPN
 * aktiv (bei Split-Tunneling kann die App daran vorbeilaufen).
 */
@Immutable
data class NetworkStatus(
    val online: Boolean = true,
    val transport: Transport = Transport.OTHER,
    val vpnForApp: Boolean = false,
    val vpnOnDevice: Boolean = false,
    /** Geschätzte Bandbreite laut System (kbit/s), oft nur grob. */
    val linkDownKbps: Int? = null,
    /** WLAN-Verbindungsgeschwindigkeit zum Router (Mbit/s). */
    val wifiLinkMbps: Int? = null,
)

/**
 * Beobachtet die Netzwerkverbindung über den ConnectivityManager. So kann der Player nach einem
 * WLAN-Aussetzer von selbst weiterspielen, statt nach einigen Fehlversuchen aufzugeben.
 */
class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
    }

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(callback)
        }
    }

    /** Liest den Zustand neu (z. B. jede Sekunde, solange die Stream-Info offen ist). */
    fun refresh() {
        _status.value = read()
    }

    @Suppress("DEPRECATION")
    private fun read(): NetworkStatus {
        val cm = connectivity ?: return NetworkStatus()
        return runCatching {
            val active = cm.activeNetwork ?: return@runCatching NetworkStatus(online = false, transport = Transport.NONE)
            val caps = cm.getNetworkCapabilities(active) ?: return@runCatching NetworkStatus(online = false, transport = Transport.NONE)
            val vpnForApp = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val all = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            // Beim VPN zeigt das aktive Netz selbst kein WLAN/LAN – dafür das darunterliegende Netz suchen.
            val physical = if (vpnForApp) all.firstOrNull { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } ?: caps else caps
            val transport = transportOf(physical)
            NetworkStatus(
                // Bewusst nicht VALIDATED: Hinter DNS-Filtern schlägt Androids Prüfung fehl, Streams laufen trotzdem.
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                transport = transport,
                vpnForApp = vpnForApp,
                // Manche VPNs (Kernel-WireGuard, OpenVPN auf gerooteten Boxen) melden sich nicht als VPN-Netz an –
                // dann verrät sie nur ihre Netzwerkschnittstelle (tun0, wg0, ppp0 …).
                vpnOnDevice = vpnForApp || all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } || vpnInterfaceUp(),
                linkDownKbps = physical.linkDownstreamBandwidthKbps.takeIf { it > 0 },
                wifiLinkMbps = if (transport == Transport.WIFI) wifiLinkSpeed() else null,
            )
        }.getOrElse { NetworkStatus() }
    }

    private fun transportOf(caps: NetworkCapabilities): Transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
        else -> Transport.OTHER
    }

    private fun vpnInterfaceUp(): Boolean = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
        interfaces.asSequence().any { it.isUp && isVpnInterfaceName(it.name) }
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun wifiLinkSpeed(): Int? = runCatching {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        wifi?.connectionInfo?.linkSpeed?.takeIf { it > 0 }
    }.getOrNull()

    companion object {
        private val vpnPrefixes = listOf("tun", "tap", "ppp", "wg", "ipsec", "utun")

        /** tun0, wg0, ppp0, ipsec0 … sind typische VPN-Schnittstellen. */
        fun isVpnInterfaceName(name: String): Boolean {
            val lower = name.lowercase(java.util.Locale.ROOT)
            return vpnPrefixes.any { lower.startsWith(it) }
        }
    }
}
