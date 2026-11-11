package com.mg.player.data.parser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private val playlist = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz" catchup="shift" catchup-days="3"
        #EXTINF:-1 tvg-id="DasErste.de" tvg-name="Das Erste" tvg-logo="http://logo/ard.png" group-title="Deutschland",Das Erste HD
        http://server/live/1.ts
        #EXTINF:-1 tvg-id="" group-title="Filme",Der Film, mit Komma (2020)
        http://server/movie/2.mkv
        #EXTINF:-1,Ohne Gruppe
        #EXTGRP:Sonstiges
        http://server/live/3.ts
    """.trimIndent()

    private fun parse(): Pair<String?, List<M3uParser.Entry>> = runBlocking {
        var epg: String? = null
        val entries = mutableListOf<M3uParser.Entry>()
        M3uParser.parse(playlist.byteInputStream(), onHeaderEpg = { epg = it }, onEntry = { entries += it })
        epg to entries
    }

    @Test
    fun `liest EPG-Adresse aus dem Kopf`() {
        assertEquals("http://epg.example/guide.xml.gz", parse().first)
    }

    @Test
    fun `liest Einträge mit Attributen`() {
        val entries = parse().second
        assertEquals(3, entries.size)
        val first = entries[0]
        assertEquals("Das Erste HD", first.name)
        assertEquals("http://server/live/1.ts", first.url)
        assertEquals("http://logo/ard.png", first.logo)
        assertEquals("Deutschland", first.group)
        assertEquals("DasErste.de", first.tvgId)
    }

    @Test
    fun `Komma im Titel bleibt erhalten`() {
        assertEquals("Der Film, mit Komma (2020)", parse().second[1].name)
    }

    @Test
    fun `EXTGRP und fehlendes Logo`() {
        val third = parse().second[2]
        assertEquals("Sonstiges", third.group)
        assertNull(third.logo)
    }

    @Test
    fun `Catch-up-Vorgaben aus dem Kopf gelten für alle Einträge`() {
        val first = parse().second[0]
        assertEquals("shift", first.catchupType)
        assertEquals(3, first.catchupDays)
    }

    @Test
    fun `uebernimmt HTTP-Header aus EXTVLCOPT und EXTHTTP`() = runBlocking {
        val list = """
            #EXTM3U
            #EXTINF:-1 group-title="Test",Sender mit Headern
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 (SMART-TV)
            #EXTVLCOPT:http-referrer=http://portal.example/
            http://server/live/9.ts
            #EXTINF:-1,JSON-Header
            #EXTHTTP:{"User-Agent":"Kodi/20","Referer":"http://r.example/"}
            http://server/live/10.ts
            #EXTINF:-1,Ohne Header
            http://server/live/11.ts
        """.trimIndent()
        val entries = mutableListOf<M3uParser.Entry>()
        M3uParser.parse(list.byteInputStream(), onHeaderEpg = {}, onEntry = { entries += it })
        assertEquals("Mozilla/5.0 (SMART-TV)", entries[0].headers["User-Agent"])
        assertEquals("http://portal.example/", entries[0].headers["Referer"])
        assertEquals("Kodi/20", entries[1].headers["User-Agent"])
        assertEquals(emptyMap<String, String>(), entries[2].headers)
    }

    private fun parseText(text: String): List<M3uParser.Entry> = runBlocking {
        val entries = mutableListOf<M3uParser.Entry>()
        M3uParser.parse(text.byteInputStream(), onHeaderEpg = {}, onEntry = { entries += it })
        entries
    }

    @Test
    fun fileWithoutLineBreaksDoesNotLoadIntoMemory() {
        // 8 MB ohne Zeilenumbruch (z. B. Binärdaten): wird überlesen statt als eine Zeile gespeichert.
        val junk = "x".repeat(8 * 1024 * 1024)
        assertEquals(0, parseText(junk).size)
    }

    @Test
    fun longLinesAreSkippedAndCrLfWorks() {
        val long = "#EXTVLCOPT:" + "a".repeat(M3uParser.MAX_LINE + 10)
        val text = "#EXTM3U\r\n#EXTINF:-1,Eins\r\n$long\r\nhttp://s/1.ts\r\n#EXTINF:-1,Zwei\rhttp://s/2.ts"
        assertEquals(listOf("Eins" to "http://s/1.ts", "Zwei" to "http://s/2.ts"), parseText(text).map { it.name to it.url })
    }

    @Test
    fun manyOverlongLinesMeanItIsNoM3u() {
        val line = "y".repeat(M3uParser.MAX_LINE + 1) + "\n"
        val error = runCatching { parseText(line.repeat(M3uParser.MAX_DROPPED)) }.exceptionOrNull()
        assertEquals(M3uParser.NOT_M3U, error?.message)
    }

    /**
     * Abbruch mitten im Parsen: onEntry im Importer suspendiert nicht selbst (sammelt nur) - ohne eigenen
     * Abbruchpunkt im Parser lief das Lesen nach einem cancel() bisher bis zum Ende weiter. Deterministisch:
     * feste Groesse, kein Zufall, keine Zeitmessung.
     */
    @Test(timeout = 30_000)
    fun cancellingTheParseStopsItEarly() = runBlocking {
        val total = 40_000
        val text = buildString {
            append("#EXTM3U\n")
            repeat(total) { i -> append("#EXTINF:-1,T$i\n").append("http://s/$i.ts\n") }
        }
        var seen = 0
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val work = scope.launch {
            M3uParser.parse(text.byteInputStream(), onHeaderEpg = {}, onEntry = {
                seen++
                if (seen == 1) job.cancel()
            })
        }
        work.join()
        assertEquals(true, seen < total / 2)
        assertEquals(true, seen >= 1)
    }
}
