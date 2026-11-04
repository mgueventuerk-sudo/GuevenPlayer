package com.mg.player.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupUrlBuilderTest {

    // 2024-01-01 19:30 UTC = 20:30 in Berlin
    private val start = 1_704_137_400_000L
    private val end = start + 3_600_000L
    private val now = start + 7_200_000L

    @Test
    fun `Xtream-Timeshift nutzt die Server-Zeitzone`() {
        val url = CatchupUrlBuilder.xtream("http://srv:80", "user", "pass", "42", start, end, "Europe/Berlin")
        assertEquals("http://srv:80/timeshift/user/pass/60/2024-01-01:20-30/42.ts", url)
    }

    @Test
    fun `default ersetzt Platzhalter in der Vorlage`() {
        val url = CatchupUrlBuilder.m3u("http://s/live.ts", "default", "http://s/archive?start={utc}&end={utcend}&d={duration}", start, end, now)
        assertEquals("http://s/archive?start=1704137400&end=1704141000&d=3600", url)
    }

    @Test
    fun `append hängt die Vorlage an den Stream an`() {
        val url = CatchupUrlBuilder.m3u("http://s/live.m3u8", "append", "?utc=\${start}&lutc=\${now}", start, end, now)
        assertEquals("http://s/live.m3u8?utc=1704137400&lutc=1704144600", url)
    }

    @Test
    fun `shift ergänzt utc und lutc`() {
        val url = CatchupUrlBuilder.m3u("http://s/live.ts?token=a", "shift", null, start, end, now)
        assertEquals("http://s/live.ts?token=a&utc=1704137400&lutc=1704144600", url)
    }

    @Test
    fun `Datumsplatzhalter in UTC`() {
        assertEquals("2024/01/01/19-30", CatchupUrlBuilder.fill("{Y}/{m}/{d}/{H}-{M}", start, end, now))
    }

    @Test
    fun `default ohne Vorlage ist nicht möglich`() {
        assertNull(CatchupUrlBuilder.m3u("http://s/live.ts", "default", null, start, end, now))
    }

    @Test
    fun `Archivzeitraum wird geprüft`() {
        assertTrue(StreamUrlResolver.isInArchive(3, start, now))
        assertFalse(StreamUrlResolver.isInArchive(0, start, now))
        assertFalse(StreamUrlResolver.isInArchive(1, start - 2 * 86_400_000L, now))
        assertFalse(StreamUrlResolver.isInArchive(3, now + 60_000, now))
    }
}
