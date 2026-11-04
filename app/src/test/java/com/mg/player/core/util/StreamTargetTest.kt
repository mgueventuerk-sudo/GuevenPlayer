package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTargetTest {

    @Test
    fun `Adresse ohne Header bleibt unverändert`() {
        val target = StreamTarget.parse("http://server/live/1.ts")
        assertEquals("http://server/live/1.ts", target.url)
        assertTrue(target.headers.isEmpty())
        assertNull(target.userAgent)
    }

    @Test
    fun `Kodi-Format wird zerlegt und dekodiert`() {
        val target = StreamTarget.parse("http://server/1.m3u8|User-Agent=Mozilla%2F5.0&Referer=http%3A%2F%2Fseite.de%2F")
        assertEquals("http://server/1.m3u8", target.url)
        assertEquals("Mozilla/5.0", target.userAgent)
        assertEquals(mapOf("Referer" to "http://seite.de/"), target.extraHeaders)
    }

    @Test
    fun `Header-Namen werden vereinheitlicht`() {
        val target = StreamTarget.parse("http://x/1.ts|user-agent=A&referrer=B")
        assertEquals("A", target.headers["User-Agent"])
        assertEquals("B", target.headers["Referer"])
    }

    @Test
    fun `withHeaders und parse ergeben wieder dasselbe`() {
        val raw = StreamTarget.withHeaders("http://x/1.ts", mapOf("User-Agent" to "Mein Player/1.0", "Referer" to "http://a.b/?c=d&e=f"))
        val target = StreamTarget.parse(raw)
        assertEquals("http://x/1.ts", target.url)
        assertEquals("Mein Player/1.0", target.userAgent)
        assertEquals("http://a.b/?c=d&e=f", target.headers["Referer"])
    }

    @Test
    fun `withHeaders ohne Header ändert nichts`() {
        assertEquals("http://x/1.ts", StreamTarget.withHeaders("http://x/1.ts", emptyMap()))
    }

    @Test
    fun `Zeilenumbrüche in Headern werden verworfen`() {
        val target = StreamTarget.parse("http://x/1.ts|X-Test=a%0D%0AHost%3A%20evil")
        assertTrue(target.headers.isEmpty())
    }

    @Test
    fun `redact entfernt Zugangsdaten aus Adressen`() {
        assertEquals("http://host:8080/…", UrlUtils.redact("http://host:8080/live/user/geheim/1.ts"))
        assertEquals("http://host/…", UrlUtils.redact("http://host/xmltv.php?username=u&password=p"))
        assertEquals("https://host/…", UrlUtils.redact("https://user:pw@host/x"))
    }

    @Test
    fun `Endung ignoriert angehängte Header`() {
        assertEquals("mkv", UrlUtils.extension("http://x/film.mkv|User-Agent=A"))
    }

    /** Kopfzeilen aus der Playlist sind fremd: ungültige werden verworfen statt die Wiedergabe abstürzen zu lassen. */
    @Test
    fun `ungültige Header aus der Playlist werden verworfen`() {
        val target = StreamTarget.parse("http://s/live.ts|User-Agent=VLC&Mein%20Kopf=x&X-A=a%0D%0ASet-Cookie%3A%20b&Referer=http%3A%2F%2Fseite&X-Leer=")
        assertEquals("http://s/live.ts", target.url)
        assertEquals(mapOf(StreamTarget.USER_AGENT to "VLC", StreamTarget.REFERER to "http://seite"), target.headers)
    }
}
