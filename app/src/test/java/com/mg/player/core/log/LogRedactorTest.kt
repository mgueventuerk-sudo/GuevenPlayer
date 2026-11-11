package com.mg.player.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Protokoll vor dem Weitergeben: keine Zugangsdaten, Schlüssel oder E-Mail-Adressen. */
class LogRedactorTest {

    @Test
    fun masksCredentialsKeysAndEmails() {
        val line = "GET http://tv.example:8080/player_api.php?username=max&password=geheim&action=x " +
            "/live/max/geheim/123.ts http://max:geheim@host/x PrivateKey = abc123 Bearer abcdefghijkl max@example.com"
        val out = LogRedactor.redact(line)
        listOf("max", "geheim", "abc123", "abcdefghijkl", "example.com").forEach { assertFalse(it, out.contains(it)) }
        assertEquals("action=x", Regex("action=\\w+").find(out)?.value)
    }

    @Test
    fun masksApiToken() {
        assertEquals("GET /fixtures?api_token=***&page=2", LogRedactor.redact("GET /fixtures?api_token=abc123&page=2"))
    }

    @Test
    fun leavesOrdinaryLinesAlone() {
        val line = "12.10. 20:15:00  [Player]  Start bis Bild: 850 ms"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test
    fun masksSecretAndCodeQueryParameters() {
        assertEquals("GET /x?secret=***&page=2", LogRedactor.redact("GET /x?secret=abc123def&page=2"))
        assertEquals("GET /oauth?code=***", LogRedactor.redact("GET /oauth?code=geheimercode"))
        // Zusammengesetzte Namen bleiben unberührt – das sind keine Zugangsdaten.
        val unrelated = "GET /x?postal_code=12345&area_code=030&status_code=200"
        assertEquals(unrelated, LogRedactor.redact(unrelated))
    }

    @Test
    fun masksAuthorizationCookieAndSetCookieHeaders() {
        assertEquals("Authorization: ***", LogRedactor.redact("Authorization: Basic dXNlcjpwYXNz"))
        assertEquals("Cookie: ***", LogRedactor.redact("Cookie: stb_lang=en; mac=00:1A:79:AB:CD:EF"))
        assertEquals("Set-Cookie: ***", LogRedactor.redact("Set-Cookie: session=abcdef; Path=/"))
        // Die Kopfzeilen-Regel schwärzt den ganzen Wert nach "Authorization:" – auch mit vorangestelltem Bearer.
        assertEquals("Authorization: ***", LogRedactor.redact("Authorization: Bearer abcdefghijkl"))
    }
}
