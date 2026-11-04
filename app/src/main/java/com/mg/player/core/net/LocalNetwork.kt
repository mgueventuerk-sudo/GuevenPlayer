package com.mg.player.core.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android 17 (bei targetSdk 37): Verkehr ins Heimnetz – eingehend (QR-Seite fürs Handy) wie ausgehend (IPTV-Server
 * im LAN, z. B. NAS oder TV-Box) – braucht die Laufzeit-Erlaubnis „Geräte in der Nähe“ (ACCESS_LOCAL_NETWORK).
 * Ohne sie laufen Verbindungen ins Leere (TCP: Zeitüberschreitung), Internet bleibt unberührt.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val ANDROID_17 = 37

    fun permissionRequired(context: Context): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17 && context.applicationInfo.targetSdkVersion >= ANDROID_17

    fun granted(context: Context): Boolean =
        !permissionRequired(context) || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Liegt die Adresse im Heimnetz (privat, link-lokal, IPv6-ULA)? Loopback zählt nicht dazu. */
    fun isLocal(address: InetAddress): Boolean = address.isSiteLocalAddress ||
        address.isLinkLocalAddress ||
        (address is Inet6Address && (address.address[0].toInt() and ULA_MASK) == ULA_PREFIX)

    /**
     * Zeigt [url] ins Heimnetz? Löst den Namen auf (blockierend – nur im Hintergrund aufrufen); nicht auflösbar = nein.
     * [resolve] austauschbar für Tests.
     */
    fun pointsToLocal(url: String, resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName): Boolean {
        val host = hostOf(url) ?: return false
        return runCatching { resolve(host).any(::isLocal) }.getOrDefault(false)
    }

    internal fun hostOf(url: String): String? {
        val rest = url.trim().substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
        return host.takeIf { it.isNotBlank() }
    }

    private const val ULA_MASK = 0xFE
    private const val ULA_PREFIX = 0xFC
}
