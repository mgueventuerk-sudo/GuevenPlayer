package com.mg.player.core.vpn

import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import java.io.BufferedReader
import java.io.StringReader
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/** Geprüfte WireGuard-Konfiguration mit erkanntem Land (aus dem Servernamen, z. B. „de-fra.prod.surfshark.com“). */
class ParsedVpnConfig(val config: Config, val country: String?, val suggestedName: String)

/**
 * Liest .conf-Dateien (Surfshark, Mullvad, Proton …). Fehlermeldungen nennen nie Schlüssel oder Adressen.
 * Braucht Android 7 (die WireGuard-Bibliothek nutzt java.util.Optional).
 */
object VpnConfigParser {

    private const val MAX_CHARS = 16_000
    private const val KEEPALIVE_S = 25

    fun parse(raw: String, fileName: String? = null): Result<ParsedVpnConfig> {
        val text = raw.removePrefix("\uFEFF").replace("\r", "").trim()
        if (text.isEmpty()) return fail("Die Konfiguration ist leer.")
        if (text.length > MAX_CHARS) return fail("Die Datei ist zu groß für eine WireGuard-Konfiguration.")
        if (!text.contains("[Interface]", ignoreCase = true)) {
            return fail("Das ist keine WireGuard-Konfiguration (Abschnitt [Interface] fehlt).")
        }
        val config = try {
            Config.parse(BufferedReader(StringReader(text)))
        } catch (e: BadConfigException) {
            return fail(badConfigMessage(e.location))
        } catch (e: Exception) {
            return fail("Die Konfiguration ließ sich nicht lesen.")
        } catch (e: LinkageError) {
            // Fehlende Systemklassen auf sehr alten Geräten – nie die App mitreißen.
            return fail("Das eingebaute VPN wird auf diesem Gerät nicht unterstützt.")
        }
        if (config.peers.isEmpty()) return fail("Die Konfiguration enthält keinen Server ([Peer] fehlt).")
        val host = config.peers.firstNotNullOfOrNull { peer -> peer.endpoint.orElse(null)?.host }
            ?: return fail("In der Konfiguration fehlt die Server-Adresse (Endpoint).")
        val country = countryOf(host) ?: fileName?.let(::countryOf)
        val city = cityOf(host) ?: fileName?.let(::cityOf)
        return Result.success(ParsedVpnConfig(config, country, nameFor(country, city)))
    }

    /**
     * Nur GuevenPlayer durch den Tunnel, Keepalive 25 s, IPv6 und DNS ebenfalls durch den Tunnel.
     * Eigene Angaben der Datei zu ein- oder ausgeschlossenen Apps werden ersetzt.
     */
    fun restrictToApp(config: Config, packageName: String, resolve: (String) -> InetAddress? = { null }): Config {
        val source = config.`interface`
        val iface = Interface.Builder()
            .addAddresses(source.addresses)
            .addDnsServers(source.dnsServers.ifEmpty { setOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))) })
            .addDnsSearchDomains(source.dnsSearchDomains)
            .setKeyPair(source.keyPair)
            .includeApplication(packageName)
        source.listenPort.ifPresent { iface.setListenPort(it) }
        source.mtu.ifPresent { iface.setMtu(it) }
        val peers = config.peers.map { peer ->
            val allowed = LinkedHashSet(peer.allowedIps)
            // Ganzer IPv4-Verkehr durch den Tunnel, aber IPv6 nicht → IPv6 liefe am VPN vorbei.
            if (allowed.any { it.mask == 0 && it.address.address.size == 4 } && allowed.none { it.mask == 0 && it.address.address.size == 16 }) {
                allowed += InetNetwork.parse("::/0")
            }
            Peer.Builder()
                .setPublicKey(peer.publicKey)
                .addAllowedIps(allowed)
                .setPersistentKeepalive(peer.persistentKeepalive.orElse(KEEPALIVE_S))
                .apply {
                    // Servernamen selbst auflösen (mit DoH-Rückfall): Die Bibliothek nutzt nur das System-DNS und
                    // scheitert sonst z. B. hinter DNS-Filtern oder kurz nach einem Netzwechsel.
                    peer.endpoint.ifPresent { endpoint -> setEndpoint(resolved(endpoint, resolve)) }
                    peer.preSharedKey.ifPresent { setPreSharedKey(it) }
                }
                .build()
        }
        return Config.Builder().setInterface(iface.build()).addPeers(peers).build()
    }

    private fun resolved(endpoint: InetEndpoint, resolve: (String) -> InetAddress?): InetEndpoint {
        val host = endpoint.host
        if (host.contains(':') || IPV4.matches(host)) return endpoint
        val address = runCatching { resolve(host) }.getOrNull() ?: return endpoint
        val literal = if (address is Inet6Address) "[${address.hostAddress}]" else address.hostAddress ?: return endpoint
        return runCatching { InetEndpoint.parse("$literal:${endpoint.port}") }.getOrDefault(endpoint)
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Verständliche Meldung je Feld (nie mit dem Inhalt des Feldes – der kann ein Schlüssel sein). */
    private fun badConfigMessage(location: BadConfigException.Location): String = when (location) {
        BadConfigException.Location.PRIVATE_KEY ->
            "In der Datei fehlt der private Schlüssel oder er ist ungültig. Bei Surfshark beim Erzeugen „Ich habe kein " +
                "Schlüsselpaar“ wählen – dann steht der Schlüssel in der Datei."

        BadConfigException.Location.PUBLIC_KEY -> "Der Server-Schlüssel (PublicKey) in der Datei ist ungültig."

        BadConfigException.Location.ENDPOINT -> "Die Server-Adresse (Endpoint) in der Datei ist ungültig."

        BadConfigException.Location.ADDRESS -> "Die Adresse (Address) in der Datei ist ungültig."

        BadConfigException.Location.DNS -> "Der DNS-Eintrag in der Datei ist ungültig."

        else -> "Die Konfiguration ist fehlerhaft (${location.name.lowercase()})."
    }

    private fun fail(message: String): Result<ParsedVpnConfig> = Result.failure(IllegalArgumentException(message))
}

/** Land aus „de-fra.prod.surfshark.com“, „nl-ams-wg-001“ oder „uk-lon.conf“ – null, wenn nichts passt. */
internal fun countryOf(name: String): String? {
    val match = Regex("^([a-z]{2})[-._]").find(name.lowercase(Locale.ROOT).substringAfterLast('/')) ?: return null
    val code = match.groupValues[1].let { if (it == "uk") "gb" else it }.uppercase(Locale.ROOT)
    return code.takeIf { it in Locale.getISOCountries() }
}

private val CITIES = mapOf(
    "fra" to "Frankfurt", "ber" to "Berlin", "muc" to "München", "ham" to "Hamburg", "dus" to "Düsseldorf",
    "ams" to "Amsterdam", "vie" to "Wien", "zrh" to "Zürich", "ist" to "Istanbul", "lon" to "London",
    "par" to "Paris", "bru" to "Brüssel", "cph" to "Kopenhagen", "sto" to "Stockholm", "mil" to "Mailand",
)

internal fun cityOf(name: String): String? {
    val match = Regex("^[a-z]{2}[-_]([a-z]{3})").find(name.lowercase(Locale.ROOT).substringAfterLast('/')) ?: return null
    return CITIES[match.groupValues[1]]
}

internal fun nameFor(country: String?, city: String?): String {
    val land = country?.let { runCatching { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.GERMAN) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
    return when {
        land != null && city != null -> "$land · $city"
        land != null -> land
        else -> "VPN-Server"
    }
}
