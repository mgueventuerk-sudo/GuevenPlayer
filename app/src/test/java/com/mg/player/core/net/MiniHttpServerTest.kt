package com.mg.player.core.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Heimnetz-Server: Ohne Token wird nichts gelesen und nichts ausgeführt. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MiniHttpServerTest {

    private val calls = AtomicInteger()
    private var lastBody = ""
    private val server = MiniHttpServer(maxBodyBytes = 1024) { request ->
        calls.incrementAndGet()
        lastBody = request.body
        MiniHttpServer.Response.html(200, "ok")
    }
    private val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()

    @After
    fun tearDown() = server.stop()

    private fun base(): String {
        val base = server.start()
        // Ohne Netzwerkschnittstelle im Testsystem lässt sich der Server nicht prüfen.
        assumeTrue(base != null)
        return base!!
    }

    @Test
    fun rejectsRequestsWithoutTokenBeforeReadingBody() = runBlocking {
        val base = base()
        val body = "x".repeat(800).toRequestBody("text/plain".toMediaType())
        client.newCall(Request.Builder().url("$base/restore").post(body).build()).execute().use { assertEquals(403, it.code) }
        client.newCall(Request.Builder().url("$base/restore?t=falsch").post(body).build()).execute().use { assertEquals(403, it.code) }
        assertEquals("Handler darf ohne gültiges Token nie laufen", 0, calls.get())
    }

    @Test
    fun acceptsRequestsWithToken() = runBlocking {
        val base = base()
        val body = "hallo".toRequestBody("text/plain".toMediaType())
        client.newCall(Request.Builder().url("$base/restore?t=${server.token}").post(body).build()).execute().use {
            assertEquals(200, it.code)
            assertNotNull(it.body)
        }
        assertEquals(1, calls.get())
        assertEquals("hallo", lastBody)
    }

    /** Das Token steht in der Adresse: kein Referer nach außen, keine fremden Skripte, kein Einbetten. */
    @Test
    fun responsesCarrySecurityHeaders() = runBlocking {
        val base = base()
        client.newCall(Request.Builder().url("$base/?t=${server.token}").build()).execute().use {
            assertEquals("no-referrer", it.header("Referrer-Policy"))
            assertEquals("nosniff", it.header("X-Content-Type-Options"))
            assertEquals("DENY", it.header("X-Frame-Options"))
            assertTrue(it.header("Content-Security-Policy").orEmpty().contains("frame-ancestors 'none'"))
            assertTrue(it.header("Content-Security-Policy").orEmpty().contains("connect-src 'self'"))
        }
        client.newCall(Request.Builder().url("$base/?t=falsch").build()).execute().use {
            assertEquals(403, it.code)
            assertEquals("no-referrer", it.header("Referrer-Policy"))
        }
    }

    @Test
    fun tokenCheckIsExact() {
        assertTrue(server.isAuthorized(server.token))
        assertEquals(16, server.token.length)
        listOf(null, "", server.token.dropLast(1), server.token + "x", server.token.uppercase()).forEach {
            assertEquals("$it", false, server.isAuthorized(it))
        }
    }

    @Test
    fun headerValidationBlocksInjection() {
        assertTrue(isSafeHeader("Content-Disposition", "attachment; filename=\"a.json\""))
        assertTrue(isSafeHeader("User-Agent", "VLC/3.0\tLinux"))
        assertEquals(false, isSafeHeader("X-A", "wert\r\nSet-Cookie: x=1"))
        assertEquals(false, isSafeHeader("Mein Kopf", "x"))
        assertEquals(false, isSafeHeader("Kopf:", "x"))
        assertEquals(false, isSafeHeader("Ä", "x"))
        assertEquals(false, isSafeHeader("", "x"))
        assertEquals("Payload Too Large", reasonPhrase(413))
        assertEquals("Not Found", reasonPhrase(404))
    }

    /**
     * HandoffServer/ProofShareServer entscheiden anhand davon, ob ein Neuaufbau nötig ist (ein Netzwechsel kann
     * den gebundenen Socket wegziehen, ohne dass das Server-Objekt selbst verschwindet).
     */
    @Test
    fun isAcceptingReflectsTheAcceptLoopLifecycle() = runBlocking {
        assertEquals(false, server.isAccepting())
        base()
        withTimeout(2_000) { while (!server.isAccepting()) delay(10) }
        assertTrue(server.isAccepting())
        server.stop()
        withTimeout(2_000) { while (server.isAccepting()) delay(10) }
        assertEquals(false, server.isAccepting())
    }

    @Test
    fun subnetCheck() {
        fun ip(text: String) = java.net.InetAddress.getByName(text).address
        assertEquals(true, sameSubnet(ip("192.0.2.2"), ip("192.0.2.77"), 24))
        assertEquals(false, sameSubnet(ip("192.0.2.2"), ip("192.0.3.2"), 24))
        assertEquals(true, sameSubnet(ip("100.64.1.5"), ip("100.64.200.9"), 10))
        assertEquals(false, sameSubnet(ip("100.64.1.5"), ip("8.8.8.8"), 10))
    }

    private fun raw(base: String): java.net.Socket {
        val uri = java.net.URI(base)
        return java.net.Socket(uri.host, uri.port).apply { soTimeout = 3_000 }
    }

    /** Früher wurde auf maxBodyBytes abgeschnitten und der Handler bekam eine halbe Sicherung. */
    @Test
    fun oversizedBodyIsRejectedNotTruncated() {
        val base = base()
        raw(base).use { s ->
            s.getOutputStream().write("POST /restore?t=${server.token} HTTP/1.1\r\nHost: x\r\nContent-Length: 2048\r\n\r\n${"x".repeat(2048)}".toByteArray())
            val status = s.getInputStream().bufferedReader().readLine()
            assertEquals("413", status.split(' ')[1])
        }
        assertEquals("Handler darf keinen abgeschnittenen Inhalt bekommen", 0, calls.get())
    }

    /** Slowloris: ein Byte pro Sekunde hielt einen der vier Plätze bis zu 64 × 8 KB × 15 s fest. */
    @Test
    fun slowClientIsDroppedAfterDeadline() {
        val slow = MiniHttpServer(maxBodyBytes = 1024, requestDeadlineMs = 1_000) {
            calls.incrementAndGet()
            MiniHttpServer.Response.html(200, "ok")
        }
        try {
            val base = slow.start()
            assumeTrue(base != null)
            raw(base!!).use { s ->
                val out = s.getOutputStream()
                out.write("GET /?t=${slow.token} HTTP/1.1\r\n".toByteArray())
                val dripper = thread {
                    // IOException = Server hat geschlossen – erwartet.
                    runCatching {
                        repeat(20) {
                            out.write('a'.code)
                            out.flush()
                            Thread.sleep(200)
                        }
                    }
                }
                // Timeout = Server hält die Verbindung noch; jeder andere Fehler = geschlossen.
                val closed = runCatching { s.getInputStream().read() == -1 }.getOrElse { it !is java.net.SocketTimeoutException }
                dripper.join()
                assertTrue("Server muss die Verbindung nach der Frist schließen", closed)
            }
            assertEquals(0, calls.get())
        } finally {
            slow.stop()
        }
    }
}
