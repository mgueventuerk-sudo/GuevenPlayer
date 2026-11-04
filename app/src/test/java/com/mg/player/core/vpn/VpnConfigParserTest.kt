package com.mg.player.core.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Surfshark-ähnliche .conf: Prüfung, Land/Name, nur GuevenPlayer durch den Tunnel, keine Schlüssel in Fehlern. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VpnConfigParserTest {

    private val conf = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.14.0.2/16
        DNS = 162.252.172.57, 149.154.159.92
        ExcludedApplications = com.example.other

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0
        Endpoint = de-fra.prod.surfshark.com:51820
    """.trimIndent()

    @Test
    fun readsSurfsharkConfig() {
        val parsed = VpnConfigParser.parse("﻿" + conf.replace("\n", "\r\n")).getOrThrow()
        assertEquals("DE", parsed.country)
        assertEquals("Deutschland · Frankfurt", parsed.suggestedName)
    }

    @Test
    fun onlyThisAppThroughTunnelWithKeepaliveIpv6AndDns() {
        val parsed = VpnConfigParser.parse(conf).getOrThrow()
        val restricted = VpnConfigParser.restrictToApp(parsed.config, "com.mg.player")
        val iface = restricted.`interface`
        assertEquals(setOf("com.mg.player"), iface.includedApplications)
        assertTrue(iface.excludedApplications.isEmpty())
        assertEquals(2, iface.dnsServers.size)
        val peer = restricted.peers.single()
        assertEquals(25, peer.persistentKeepalive.get())
        // Ganzer IPv4- und IPv6-Verkehr durch den Tunnel (Adresslänge 4 bzw. 16 Byte, Maske 0).
        assertEquals(setOf(4 to 0, 16 to 0), peer.allowedIps.map { it.address.address.size to it.mask }.toSet())
        assertEquals(parsed.config.`interface`.keyPair.publicKey, iface.keyPair.publicKey)
    }

    @Test
    fun endpointIsResolvedByTheAppWithFallback() {
        val parsed = VpnConfigParser.parse(conf).getOrThrow()
        val restricted = VpnConfigParser.restrictToApp(parsed.config, "com.mg.player") { java.net.InetAddress.getByName("185.65.1.2") }
        assertEquals("185.65.1.2", restricted.peers.single().endpoint.get().host)
        assertEquals(51820, restricted.peers.single().endpoint.get().port)
        // Nicht auflösbar: Name bleibt (die Bibliothek versucht es dann selbst).
        val unresolved = VpnConfigParser.restrictToApp(parsed.config, "com.mg.player") { null }
        assertEquals("de-fra.prod.surfshark.com", unresolved.peers.single().endpoint.get().host)
    }

    @Test
    fun missingPrivateKeyExplainsSurfsharkOption() {
        val placeholder = conf.replace("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "<insert your private key>")
        assertTrue(VpnConfigParser.parse(placeholder).exceptionOrNull()!!.message!!.contains("Ich habe kein Schlüsselpaar"))
    }

    @Test
    fun addsDnsWhenMissing() {
        val parsed = VpnConfigParser.parse(conf.lines().filterNot { it.startsWith("DNS") }.joinToString("\n")).getOrThrow()
        val restricted = VpnConfigParser.restrictToApp(parsed.config, "com.mg.player")
        assertEquals(1, restricted.`interface`.dnsServers.size)
    }

    @Test
    fun countryFromOtherProviders() {
        assertEquals("NL", countryOf("nl-ams-wg-001.relays.mullvad.net"))
        assertEquals("GB", countryOf("uk-lon.prod.surfshark.com"))
        assertEquals(null, countryOf("vpn.example.com"))
        assertEquals("Niederlande · Amsterdam", nameFor("NL", cityOf("nl-ams-wg-001")))
        assertEquals("VPN-Server", nameFor(null, null))
    }

    @Test
    fun errorsAreClearAndNeverContainKeys() {
        val badKey = conf.replace("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "geheimerKaputterSchluessel")
        val error = VpnConfigParser.parse(badKey).exceptionOrNull()!!.message!!
        assertFalse(error, error.contains("geheimer"))
        assertTrue(VpnConfigParser.parse("").isFailure)
        assertTrue(VpnConfigParser.parse("hallo welt").exceptionOrNull()!!.message!!.contains("[Interface]"))
        val noPeer = conf.substringBefore("[Peer]")
        assertTrue(VpnConfigParser.parse(noPeer).exceptionOrNull()!!.message!!.contains("[Peer]"))
        val noEndpoint = conf.lines().filterNot { it.startsWith("Endpoint") }.joinToString("\n")
        assertTrue(VpnConfigParser.parse(noEndpoint).exceptionOrNull()!!.message!!.contains("Endpoint"))
    }
}
