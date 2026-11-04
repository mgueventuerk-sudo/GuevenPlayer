package com.mg.player.lab

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.network.HttpFetcher
import com.mg.player.core.util.NameNormalizer
import com.mg.player.data.db.AppDatabase
import com.mg.player.data.importer.ChannelGrouping
import com.mg.player.data.importer.XtreamImporter
import com.mg.player.data.parser.M3uParser
import com.mg.player.data.parser.XmltvParser
import com.mg.player.data.parser.forEachObject
import com.mg.player.data.parser.jsonReader
import com.mg.player.data.remote.XtreamApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Virtuelles Labor, JVM-Teil (3.23): Parser-Durchsatz ohne Netz und Datenbank. Nur mit Umgebung GUEVEN_LAB=true.
 * Werte gelten für die Entwickler-JVM, nicht für den Fernseher – sie zeigen, welcher Teil des Imports Rechenzeit kostet.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LabJvmBenchmarkTest {

    @Before
    fun onlyInLab() = assumeTrue(System.getenv("GUEVEN_LAB") == "true")

    private fun report(key: String, value: Any) = println("LAB|$key|$value")

    private inline fun timed(runs: Int = 5, block: () -> Unit): Long {
        repeat(2) { block() } // Aufwärmen (JIT)
        val times = (1..runs).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000
        }
        return times.sorted()[runs / 2]
    }

    private fun m3u(n: Int) = buildString {
        appendLine("#EXTM3U url-tvg=\"http://127.0.0.1/epg.xml\"")
        for (i in 1..n) {
            appendLine("#EXTINF:-1 tvg-id=\"ch$i.de\" tvg-name=\"Sender $i\" tvg-logo=\"http://logo/$i.png\" group-title=\"Gruppe ${i % 40}\",DE| Sender $i HD")
            appendLine("http://127.0.0.1:8090/live/u/p/$i.ts")
        }
    }.toByteArray()

    @Test
    fun m3uParserThroughput() = runBlocking {
        for (n in listOf(10_000, 100_000)) {
            val bytes = m3u(n)
            var count = 0
            val ms = timed {
                count = 0
                runBlocking { M3uParser.parse(ByteArrayInputStream(bytes), {}) { count++ } }
            }
            assertEquals(n, count)
            report("jvm.m3u.$n.ms", ms)
            report("jvm.m3u.$n.mb", bytes.size / 1_048_576.0)
        }
    }

    @Test
    fun xtreamJsonThroughput() {
        val json = (1..10_000).joinToString(",", "[", "]") {
            """{"num":$it,"name":"DE| Sender $it HD","stream_type":"live","stream_id":$it,""" +
                """"stream_icon":"","epg_channel_id":"ch$it.de","category_id":"${it % 40}"}"""
        }.toByteArray()
        var count = 0
        val ms = timed {
            count = 0
            runBlocking { jsonReader(ByteArrayInputStream(json)).forEachObject { count++ } }
        }
        assertEquals(10_000, count)
        report("jvm.xtream.10000.ms", ms)
    }

    private fun xmltv(channels: Int, perChannel: Int) = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
        for (c in 1..channels) appendLine("<channel id=\"ch$c.de\"><display-name>Sender $c</display-name></channel>")
        for (c in 1..channels) {
            for (p in 0 until perChannel) {
                val h = 10 + p
                appendLine(
                    "<programme start=\"202611021${h % 10}0000 +0100\" stop=\"202611021${(h + 1) % 10}0000 +0100\" channel=\"ch$c.de\">" +
                        "<title>Sendung $p</title><desc>Beschreibung der Sendung $p auf Sender $c</desc></programme>",
                )
            }
        }
        append("</tv>")
    }.toByteArray()

    @Test
    fun xmltvThroughput() {
        val bytes = xmltv(2_000, 12)
        var programmes = 0
        val ms = timed(runs = 3) {
            programmes = 0
            runBlocking {
                XmltvParser.parse(
                    ByteArrayInputStream(bytes),
                    object : XmltvParser.Callback {
                        override suspend fun onChannel(id: String, displayNames: List<String>) = Unit
                        override suspend fun onProgramme(channelId: String, startMs: Long, endMs: Long, title: String, description: String?) {
                            programmes++
                        }
                    },
                )
            }
        }
        assertEquals(24_000, programmes)
        report("jvm.xmltv.24000.ms", ms)
        report("jvm.xmltv.24000.mb", bytes.size / 1_048_576.0)
    }

    /** Bedrohungsmodell T (kompromittierte EPG-Quelle): „Billion Laughs“ darf weder hängen noch Speicher sprengen. */
    @Test(timeout = 10_000)
    fun xmltvEntityExpansionIsHarmless() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE tv [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
            <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;"><!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;"><!ENTITY e "&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;">
            <!ENTITY f "&e;&e;&e;&e;&e;&e;&e;&e;&e;&e;"><!ENTITY g "&f;&f;&f;&f;&f;&f;&f;&f;&f;&f;">]>
            <tv><programme start="20261102100000 +0100" stop="20261102110000 +0100" channel="x"><title>&g;</title></programme></tv>"""
        var longest = 0
        val outcome = runCatching {
            runBlocking {
                XmltvParser.parse(
                    ByteArrayInputStream(bomb.toByteArray()),
                    object : XmltvParser.Callback {
                        override suspend fun onChannel(id: String, displayNames: List<String>) = Unit
                        override suspend fun onProgramme(channelId: String, startMs: Long, endMs: Long, title: String, description: String?) {
                            longest = maxOf(longest, title.length)
                        }
                    },
                )
            }
        }
        report("jvm.xmltv.entityBombe", outcome.exceptionOrNull()?.javaClass?.simpleName ?: "ohne Fehler, längster Titel $longest Zeichen")
    }

    /**
     * Im Emulator dauerten 10 000 Sender 46,5 s, 5 000 Filme nur 6,1 s (Faktor 3,8 je Eintrag). Welcher Schritt
     * kostet: Namensaufbereitung, Gruppierung (Qualitätsvarianten) oder das Schreiben in die Datenbank?
     */
    @Test
    fun liveChannelImportBreakdown() {
        val names = (1..10_000).map { "DE| Sender ${it % 900} ${listOf("HD", "FHD", "UHD", "SD", "")[it % 5]}" }
        report("jvm.live.normalize.10000.ms", timed { names.forEach { NameNormalizer.normalize(it) } })
        report("jvm.live.grouping.10000.ms", timed { names.forEach { ChannelGrouping.of(it, "DE") } })
        val api = XtreamApi(HttpFetcher(OkHttpClient()))
        val items = names.mapIndexed { i, name ->
            mapOf("stream_id" to "$i", "name" to name, "epg_channel_id" to "ch$i.de", "category_id" to "${i % 40}", "stream_icon" to "")
        }
        val channels = items.mapIndexed { i, item -> XtreamImporter.liveChannel(api, 1, item, "DE", i, 1)!! }
        report("jvm.live.mapping.10000.ms", timed { items.forEachIndexed { i, item -> XtreamImporter.liveChannel(api, 1, item, "DE", i, 1) } })
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        report(
            "jvm.live.insert.10000.ms",
            timed(runs = 3) { runBlocking { channels.chunked(1_000).forEach { db.channelDao().insertAll(it) } } },
        )
        db.close()
    }
}
