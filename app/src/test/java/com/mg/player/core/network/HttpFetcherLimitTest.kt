package com.mg.player.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

/** Text-Antworten werden vollständig gelesen, aber nie über die Obergrenze hinaus in den Speicher (Audit A-15). */
class HttpFetcherLimitTest {
    private val server = MockWebServer().apply { start() }
    private val fetcher = HttpFetcher(OkHttpClient())

    @After
    fun stop() = server.shutdown()

    @Test
    fun normalResponseIsReadCompletely() {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        assertEquals("{\"ok\":true}", fetcher.getText(server.url("/a").toString(), allowInsecureHttp = true))
    }

    @Test
    fun oversizedResponseWithLengthIsRejected() {
        val body = Buffer().write(ByteArray((HttpFetcher.MAX_TEXT_BYTES + 1).toInt()))
        server.enqueue(MockResponse().setBody(body))
        assertThrows(IOException::class.java) { fetcher.getText(server.url("/big").toString(), allowInsecureHttp = true) }
    }

    @Test
    fun oversizedChunkedResponseIsRejected() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray((HttpFetcher.MAX_TEXT_BYTES + 10).toInt())), 1 shl 20))
        assertThrows(IOException::class.java) { fetcher.getText(server.url("/chunked").toString(), allowInsecureHttp = true) }
    }

    @Test
    fun credentialedRequestDoesNotFollowRedirectWhenDisabled() {
        val destination = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/capture")))
            assertThrows(HttpStatusException::class.java) {
                fetcher.getText(
                    server.url("/api").toString(),
                    headers = mapOf("Authorization" to "Bearer test-token"),
                    allowRedirects = false,
                    allowInsecureHttp = true,
                )
            }
            assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally {
            destination.shutdown()
        }
    }

    @Test
    fun providerRequestStillFollowsRedirectByDefault() {
        val destination = MockWebServer().apply { start() }
        try {
            destination.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/provider-list")))
            assertEquals("{\"ok\":true}", fetcher.getText(server.url("/redirect").toString(), allowInsecureHttp = true))
            assertEquals("/provider-list", destination.takeRequest().path)
        } finally {
            destination.shutdown()
        }
    }

    @Test
    fun httpIsBlockedUnlessCallerExplicitlyAllowsIt() {
        server.enqueue(MockResponse().setBody("should not be read"))
        assertThrows(IOException::class.java) { fetcher.getText(server.url("/blocked").toString()) }
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }
}
