package com.mg.player.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.network.HttpFetcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Setup bisher: ein Tippfehler im Passwort/der Adresse fiel erst nach dem kompletten Importversuch auf
 * (SetupViewModel.submit() -> runImport()). XtreamApi.quickRejection() prüft jetzt vorab, aber bewusst nur
 * eindeutige Ablehnungen (HTTP 401/403) - alles andere (Zeitüberschreitung, 500er, unklare Antwort) bleibt
 * unangetastet, damit ein zu strenger Vorab-Check keine gültigen, nur gerade angeschlagenen Zugänge ablehnt.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class XtreamApiQuickRejectionTest {

    private val server = MockWebServer()
    private val api = XtreamApi(HttpFetcher(OkHttpClient()))
    private fun credentials() = XtreamApi.Credentials(server.url("/").toString().trimEnd('/'), "u", "p", allowInsecureHttp = true)

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun http401IsReportedImmediately() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val message = api.quickRejection(credentials())
        assertTrue(message.orEmpty(), message?.contains("401") == true)
    }

    @Test
    fun http403IsReportedImmediately() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        val message = api.quickRejection(credentials())
        assertTrue(message.orEmpty(), message?.contains("403") == true)
    }

    @Test
    fun successfulResponseIsNotReportedAsAProblem() {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}""").setHeader("Content-Type", "application/json"))
        server.start()
        assertNull(api.quickRejection(credentials()))
    }

    @Test
    fun otherHttpErrorsAreLeftToTheFullImportAttempt() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        assertNull(api.quickRejection(credentials()))
    }

    @Test
    fun unreachableServerIsLeftToTheFullImportAttempt() {
        // Server nie gestartet: Verbindung scheitert am Transport, nicht an einer HTTP-Antwort.
        val message = api.quickRejection(XtreamApi.Credentials("http://127.0.0.1:1", "u", "p", allowInsecureHttp = true))
        assertNull(message)
    }
}
