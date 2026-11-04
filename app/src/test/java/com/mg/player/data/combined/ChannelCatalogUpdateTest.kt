package com.mg.player.data.combined

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.network.HttpFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.zip.GZIPInputStream

/** Internetabgleich des Senderkatalogs: richtig umwandeln, kaputte Downloads nie übernehmen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChannelCatalogUpdateTest {

    private val server = MockWebServer()
    private lateinit var dir: File
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000_000_000L

    private val json = """
        [
          {"id":"TRT1.tr","name":"TRT 1","alt_names":[],"country":"TR","closed":null,"website":null,"categories":[]},
          {"id":"TRT2.tr","name":"TRT 2","alt_names":["TRT İki"],"country":"TR","closed":null,"owners":["TRT"]},
          {"id":"Old.de","name":"Alt;Sender","alt_names":[],"country":"DE","closed":"2020-01-01"},
          {"id":"NoCountry.xx","name":"Ohne Land","alt_names":[],"country":null,"closed":null},
          {"id":"DasErste.de","name":"Das Erste","alt_names":["ARD","Das\tErste"],"country":"DE","closed":null,"launched":1954}
        ]
    """.trimIndent()

    private val small = ChannelCatalogUpdate.Check(minChannels = 3, mustContain = setOf("DasErste.de"))

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "catalog-test").apply {
            deleteRecursively()
            mkdirs()
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun updater() = ChannelCatalogUpdate(
        dir,
        HttpFetcher(HttpFetcher.createClient()),
        context.getSharedPreferences("catalog-test-${System.nanoTime()}", Context.MODE_PRIVATE),
        clock = { now },
        url = server.url("/api/channels.json").toString(),
        check = small,
        allowHttp = true,
    )

    @Test
    fun convertsLikeTheBundledCatalog() {
        val out = StringWriter()
        val count = ChannelCatalogUpdate.convert(StringReader(json), out, small)
        assertEquals(3, count)
        val lines = out.toString().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("TRT1.tr\tTR\tTRT 1", "TRT2.tr\tTR\tTRT 2;TRT İki", "DasErste.de\tDE\tDas Erste;ARD"), lines)
        // Und der Katalog liest das Ergebnis: TRT 1 und TRT 2 sind verschiedene Sender.
        val catalog = ChannelCatalog.parse(out.toString().byteInputStream())
        assertEquals("TRT1.tr", catalog.byNameKey(ChannelCatalog.keyFor("TRT 1", "TR")))
        assertEquals("TRT2.tr", catalog.byNameKey(ChannelCatalog.keyFor("TRT 2", "TR")))
    }

    @Test
    fun downloadReplacesCatalogOnlyWhenComplete() = runBlocking {
        val update = updater()
        server.enqueue(MockResponse().setBody(json))
        val first = update.refresh(force = true)
        assertTrue(first is ChannelCatalogUpdate.Outcome.Updated)
        val file = File(dir, ChannelCatalogUpdate.FILE)
        val good = GZIPInputStream(file.inputStream()).bufferedReader().readText()
        assertTrue("DasErste.de" in good)
        assertTrue(update.lastUpdateMs == now)

        // Abgeschnittener Download, Serverfehler, zu wenige Sender: Die alte Datei bleibt unverändert.
        server.enqueue(MockResponse().setBody(json.take(json.length / 2)))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""[{"id":"A.de","name":"A","country":"DE"}]"""))
        repeat(3) {
            assertTrue(update.refresh(force = true) is ChannelCatalogUpdate.Outcome.Failed)
            assertEquals(good, GZIPInputStream(file.inputStream()).bufferedReader().readText())
        }
        assertTrue(!File(dir, "${ChannelCatalogUpdate.FILE}.part").exists())
    }

    @Test
    fun automaticCheckAtMostWeekly() = runBlocking {
        val update = updater()
        server.enqueue(MockResponse().setBody(json))
        assertTrue(update.refresh() is ChannelCatalogUpdate.Outcome.Updated)
        now += 6L * 24 * 60 * 60 * 1000
        assertEquals(ChannelCatalogUpdate.Outcome.NotDue, update.refresh())
        now += 2L * 24 * 60 * 60 * 1000
        server.enqueue(MockResponse().setBody(json))
        assertTrue(update.refresh() is ChannelCatalogUpdate.Outcome.Updated)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun loadPrefersTheDownloadedCatalogOnlyWhenPlausible() {
        val online = File(context.filesDir, ChannelCatalogUpdate.FILE)
        online.delete()
        val bundled = ChannelCatalog.load(context)
        assertTrue(bundled.size > ChannelCatalogUpdate.MIN_CHANNELS)
        // Zu kleine (kaputte) Datei im App-Speicher: Der mitgelieferte Katalog gilt weiter.
        java.util.zip.GZIPOutputStream(online.outputStream()).bufferedWriter().use { it.write("A.de\tDE\tA\n") }
        assertEquals(bundled.size, ChannelCatalog.load(context).size)
        online.delete()
    }
}
