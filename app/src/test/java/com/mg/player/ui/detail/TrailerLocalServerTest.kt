package com.mg.player.ui.detail

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * TrailerLocalServer liefert die Wrapper-Seite wirklich über HTTP aus statt loadDataWithBaseURL() zu nutzen - nur
 * das schickt für das eingebettete YouTube-Iframe einen echten Referer, den YouTubes Einbettungs-Prüfung verlangt
 * (gegen echte YouTube-Server nachgewiesen: Fehler 153 bzw. 152 bei einer synthetischen Seite, egal welcher
 * Origin-Wert angegeben wird - siehe Klassenkommentar in TrailerScreen.kt).
 */
class TrailerLocalServerTest {

    @Test
    fun servesTheIframePageOverRealHttp() {
        val server = TrailerLocalServer("abc123XYZ_-")
        try {
            val url = server.start()
            assertNotNull("Server muss eine Loopback-Adresse liefern", url)
            assertTrue(url!!.startsWith("http://127.0.0.1:"))

            val connection = URL(url).openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().readText()
            assertTrue("Iframe muss auf die Embed-Adresse mit der richtigen Kennung zeigen", body.contains("https://www.youtube.com/embed/abc123XYZ_-"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun stopClosesTheServerSocket() {
        val server = TrailerLocalServer("abc123XYZ_-")
        val url = server.start()!!
        server.stop()

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        try {
            connection.responseCode
            fail("Verbindung sollte nach stop() scheitern")
        } catch (expected: IOException) {
            // Server nimmt nach stop() keine Verbindungen mehr an - genau das wird hier erwartet.
        }
    }
}
