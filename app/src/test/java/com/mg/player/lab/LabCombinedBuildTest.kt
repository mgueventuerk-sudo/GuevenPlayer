package com.mg.player.lab

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Virtuelles Labor „Alle Zugänge“: zwei Zugänge mit je 10 000 Sendern und 20 000 Filmen, zur Hälfte gleiche Inhalte in
 * anderer Schreibweise. Misst Aufbauzeit, Trefferquote und Speicher. Nur mit GUEVEN_LAB=true.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LabCombinedBuildTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun onlyInLab() = assumeTrue(System.getenv("GUEVEN_LAB") == "true")

    @After
    fun tearDown() = server.shutdown()

    /** Zugang A schreibt „DE: Sender 1 HD“, Zugang B „|DE| SENDER 1 FHD“ – gleiche EPG-ID nur bei jedem zweiten. */
    private fun m3u(b: Boolean) = buildString {
        appendLine("#EXTM3U")
        for (i in 1..CHANNELS) {
            val epg = if (i % 2 == 0) "sender$i.de" else if (b) "" else "sender$i.de"
            val name = if (b) "|DE| SENDER $i FHD" else "DE: Sender $i HD"
            appendLine("#EXTINF:-1 tvg-id=\"$epg\" group-title=\"${if (b) "GERMANY GENERAL" else "DE | Vollprogramm"}\",$name")
            appendLine("http://127.0.0.1:9/${if (b) "b" else "a"}/live/$i.ts")
        }
        for (i in 1..MOVIES) {
            val year = 1950 + i % 70
            val title = if (b) "Film Nummer $i $year [FHD]" else "DE: Film Nummer $i ($year)"
            appendLine("#EXTINF:-1 group-title=\"${if (b) "VOD | Drama" else "Filme | Drama"}\",$title")
            appendLine("http://127.0.0.1:9/${if (b) "b" else "a"}/movie/$i.mkv")
        }
    }

    @Test
    fun buildTwoLargeAccounts() = runBlocking {
        val a = m3u(false)
        val b = m3u(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if ("b.m3u" in request.path.orEmpty()) b else a)
        }
        server.start()
        val repo = container.playlistRepository
        for (path in listOf("/a.m3u", "/b.m3u")) {
            val id = repo.addPlaylist(PlaylistInput(path, PlaylistType.M3U, m3uUrl = server.url(path).toString(), allowInsecureHttp = true))
            repo.refreshContent(id) {}
        }
        val runtime = Runtime.getRuntime()
        System.gc()
        val usedBefore = runtime.totalMemory() - runtime.freeMemory()
        // Spitze während des Aufbaus: alle 20 ms messen (mit GC-Hinweis, damit Müll nicht als Bedarf zählt).
        var peak = 0L
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
                Thread.sleep(20)
            }
        }.apply { start() }
        val start = System.nanoTime()
        assertTrue(container.combined.builder.rebuild("Labor"))
        val ms = (System.nanoTime() - start) / 1_000_000
        running.set(false)
        sampler.join()
        System.gc()
        val usedAfter = runtime.totalMemory() - runtime.freeMemory()
        println("LAB|jvm.combined.peakHeapMb|${(peak - usedBefore) / 1_048_576}")
        val channels = container.liveRepository.channelIds(Combined.ID, Category.ALL).size
        val links = container.database.combinedDao().linkCount()
        println("LAB|jvm.combined.build.ms|$ms")
        println("LAB|jvm.combined.channels|$channels (aus ${2 * CHANNELS}, Ziel $CHANNELS)")
        println("LAB|jvm.combined.links|$links")
        println("LAB|jvm.combined.retainedMb|${(usedAfter - usedBefore) / 1_048_576}")
        // Jeder Sender und jeder Film gehört zu genau einem Paar → doppelt so viele Quellen wie Einträge.
        assertTrue("Sender: $channels", channels == CHANNELS)
        val again = System.nanoTime()
        container.combined.builder.rebuild("Labor 2")
        println("LAB|jvm.combined.rebuild.ms|${(System.nanoTime() - again) / 1_000_000}")
    }

    private companion object {
        /** LAB_SCALE=3: dreifache Größe (30 000 Sender, 60 000 Filme je Zugang). */
        val SCALE = System.getenv("LAB_SCALE")?.toIntOrNull() ?: 1
        val CHANNELS = 10_000 * SCALE
        val MOVIES = 20_000 * SCALE
    }
}
