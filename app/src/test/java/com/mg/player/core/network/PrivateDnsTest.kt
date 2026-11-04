package com.mg.player.core.network

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verschlüsseltes DNS als Rückfall: nie schlechter als das normale DNS. */
class PrivateDnsTest {

    private fun dns(block: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = block(hostname)
    }

    private val dohAddress = listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)))
    private val systemAddress = listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)))

    private var now = 0L
    private val systemCalls = mutableListOf<String>()
    private val system = dns { host ->
        systemCalls += host
        systemAddress
    }

    @Test
    fun usesDohOnlyWhenWantedAndNeverForLocalNames() {
        var wanted = true
        val resolver = PrivateDns({ wanted }, system, { now }, listOf(dns { dohAddress }))
        assertEquals(dohAddress, resolver.lookup("api.themoviedb.org"))
        assertEquals(systemAddress, resolver.lookup("nas"))
        assertEquals(systemAddress, resolver.lookup("box.fritz.box"))
        assertEquals(systemAddress, resolver.lookup("192.168.1.20"))
        wanted = false
        assertEquals(systemAddress, resolver.lookup("anbieter.tv"))
        assertTrue(PrivateDns.isLocal("tv.local"))
        assertFalse(PrivateDns.isLocal("sports.bzzoiro.com"))
    }

    @Test
    fun unreachableDohFallsBackAndPausesTenMinutes() {
        var calls = 0
        val broken = dns { _ ->
            calls++
            throw UnknownHostException("weg").apply { initCause(IOException("timeout")) }
        }
        val resolver = PrivateDns({ true }, system, { now }, listOf(broken))
        assertEquals(systemAddress, resolver.lookup("anbieter.tv"))
        assertEquals(systemAddress, resolver.lookup("anbieter2.tv"))
        assertEquals("in der Pause kein weiterer DoH-Versuch", 1, calls)
        now += 600_001
        resolver.lookup("anbieter3.tv")
        assertEquals(2, calls)
    }

    @Test
    fun unknownNameStillAsksSystemDnsWithoutPause() {
        var calls = 0
        val nxdomain = dns { _ ->
            calls++
            throw UnknownHostException("gibt es nicht")
        }
        val resolver = PrivateDns({ true }, system, { now }, listOf(nxdomain))
        resolver.lookup("intern.firma.de")
        resolver.lookup("intern2.firma.de")
        assertEquals(2, calls)
        assertEquals(listOf("intern.firma.de", "intern2.firma.de"), systemCalls)
    }

    @Test
    fun cachesAnswersBriefly() {
        var calls = 0
        val resolver = PrivateDns(
            { true },
            system,
            { now },
            listOf(
                dns {
                    calls++
                    dohAddress
                },
            ),
        )
        resolver.lookup("a.example.com")
        resolver.lookup("a.example.com")
        assertEquals(1, calls)
        now += 120_001
        resolver.lookup("a.example.com")
        assertEquals(2, calls)
    }
}
