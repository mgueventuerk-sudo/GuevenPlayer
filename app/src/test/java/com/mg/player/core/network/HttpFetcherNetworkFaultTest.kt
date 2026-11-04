package com.mg.player.core.network

import com.mg.player.integration.Toxiproxy
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Echte TCP-Fehler über Toxiproxy: [HttpFetcher] liefert entweder den vollständigen Text oder eine IOException – nie halbe Daten, nie Hängen. */
class HttpFetcherNetworkFaultTest {
    private val toxi = Toxiproxy()
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val fetcher = HttpFetcher(client)
    private val payload = "#EXTM3U\n" + "#EXTINF:-1,Sender\nhttp://x/1.ts\n".repeat(4000)
    private lateinit var base: String

    @Before
    fun setUp() {
        toxi.assumeRunning()
        server.start()
        base = "http://" + toxi.proxy("fetcher", server.port)
        server.enqueue(MockResponse().setBody(payload))
    }

    @After
    fun tearDown() {
        toxi.reset()
        server.shutdown()
    }

    @Test
    fun latencyBelowTimeoutStillDeliversEverything() {
        toxi.toxic("fetcher", "latency", "\"latency\":1200,\"jitter\":0")
        assertEquals(payload, fetcher.getText("$base/a", allowInsecureHttp = true))
    }

    @Test
    fun tinyTcpSegmentsAreReassembledIntact() {
        toxi.toxic("fetcher", "slicer", "\"average_size\":7,\"size_variation\":5,\"delay\":0")
        assertEquals(payload, fetcher.openStream("$base/m3u", allowInsecureHttp = true).bufferedReader().use { it.readText() })
    }

    @Test
    fun cutMidBodyFailsInsteadOfReturningTruncatedText() {
        toxi.toxic("fetcher", "limit_data", "\"bytes\":20000")
        assertThrows(IOException::class.java) { fetcher.getText("$base/a", allowInsecureHttp = true) }
    }

    @Test
    fun cutMidDownloadFailsInsteadOfEndingQuietly() {
        toxi.toxic("fetcher", "limit_data", "\"bytes\":20000")
        assertThrows(IOException::class.java) { fetcher.openStream("$base/m3u", allowInsecureHttp = true).bufferedReader().use { it.readText() } }
    }

    @Test
    fun stalledServerHitsReadTimeoutInsteadOfHanging() {
        toxi.toxic("fetcher", "timeout", "\"timeout\":0")
        val start = System.nanoTime()
        assertThrows(IOException::class.java) { fetcher.getText("$base/a", allowInsecureHttp = true) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("hing $elapsedMs ms", elapsedMs < 6_000)
    }

    @Test
    fun peerResetIsAnIOException() {
        toxi.toxic("fetcher", "reset_peer", "\"timeout\":0")
        assertThrows(IOException::class.java) { fetcher.getText("$base/a", allowInsecureHttp = true) }
    }
}
