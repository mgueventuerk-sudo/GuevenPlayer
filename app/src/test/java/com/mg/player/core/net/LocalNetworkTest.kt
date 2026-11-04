package com.mg.player.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class LocalNetworkTest {

    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun privateAndLinkLocalAddressesAreLocal() {
        listOf("10.1.2.3", "172.16.0.9", "172.31.255.1", "192.168.178.20", "169.254.10.10", "fe80::1", "fd12:3456::1").forEach {
            assertTrue(it, LocalNetwork.isLocal(ip(it)))
        }
    }

    @Test
    fun internetAndLoopbackAreNotLocal() {
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "127.0.0.1", "::1", "2a00:1450::1").forEach {
            assertFalse(it, LocalNetwork.isLocal(ip(it)))
        }
    }

    @Test
    fun hostIsTakenFromAnyUrlForm() {
        assertEquals("192.168.1.5", LocalNetwork.hostOf("http://192.168.1.5:8080/get.php?username=u"))
        assertEquals("nas.fritz.box", LocalNetwork.hostOf("https://user:pw@nas.fritz.box/playlist.m3u"))
        assertEquals("fd00::5", LocalNetwork.hostOf("http://[fd00::5]:9981/"))
        assertNull(LocalNetwork.hostOf("kein-url"))
    }

    @Test
    fun namesAreResolvedAndFailuresCountAsInternet() {
        val dns = mapOf("nas.local" to arrayOf(ip("192.168.0.10")), "provider.example" to arrayOf(ip("93.184.216.34")))
        val resolve = { host: String -> dns[host] ?: throw UnknownHostException(host) }
        assertTrue(LocalNetwork.pointsToLocal("http://nas.local:8000/x.m3u", resolve))
        assertFalse(LocalNetwork.pointsToLocal("http://provider.example/live", resolve))
        assertFalse(LocalNetwork.pointsToLocal("http://unbekannt.example/live", resolve))
    }
}
