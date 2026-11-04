package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gemeldet nach 3.36: „In TRT 2 ist TRT 1 in den Quellen.“ Anbieter B gibt TRT 2 die EPG-ID von TRT 1. Weder die
 * Quellen in „Alle Zugänge“ noch das Gegenstück in einem einzelnen Zugang (Quellen-Wahl, Favoriten) dürfen TRT 1
 * zu TRT 2 stellen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SiblingChannelsTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    private val listA = """
        #EXTM3U
        #EXTINF:-1 tvg-id="TRT1.tr" group-title="TR | Ulusal",TR: TRT 1 HD
        http://stream.invalid/a/trt1.ts
        #EXTINF:-1 tvg-id="TRT2.tr" group-title="TR | Ulusal",TR: TRT 2 HD
        http://stream.invalid/a/trt2.ts
        #EXTINF:-1 tvg-id="Pro7.de" group-title="DE | Vollprogramm",DE: ProSieben HD
        http://stream.invalid/a/pro7.ts
        #EXTINF:-1 tvg-id="Pro7.de" group-title="DE | Vollprogramm",DE: ProSieben UHD
        http://stream.invalid/a/pro7_uhd.ts
        #EXTINF:-1 tvg-id="AlJazeera.qa" group-title="AR | News",AR: AL JAZERA HD
        http://stream.invalid/a/aj_hd.ts
        #EXTINF:-1 tvg-id="AlJazeera.qa" group-title="AR | News",AR: AL JAZEERA LQ
        http://stream.invalid/a/aj_lq.ts
    """.trimIndent()

    private val listB = """
        #EXTM3U
        #EXTINF:-1 tvg-id="TRT1.tr" group-title="TR | Ulusal",TR: TRT 1
        http://stream.invalid/b/trt1.ts
        #EXTINF:-1 tvg-id="TRT1.tr" group-title="TR | Ulusal",TR: TRT 2
        http://stream.invalid/b/trt2.ts
        #EXTINF:-1 tvg-id="Pro7.de" group-title="DE | Vollprogramm",DE: ProSieben MAXX
        http://stream.invalid/b/maxx.ts
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if ("b.m3u" in request.path.orEmpty()) listB else listA)
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun add(name: String, path: String): Long {
        val repo = container.playlistRepository
        val id = repo.addPlaylist(PlaylistInput(name, PlaylistType.M3U, m3uUrl = server.url(path).toString(), allowInsecureHttp = true))
        repo.refreshContent(id) {}
        return id
    }

    /** ID eines Senders über seinen Originalnamen (die Suche liefert bereinigte Titel). */
    private suspend fun idOf(playlist: Long, original: String): String =
        container.database.combinedDao().channelSources(playlist).first { it.name == original }.id

    @Test
    fun wrongEpgIdNeverMakesTrt1ASourceOfTrt2() = runBlocking {
        val a = add("A", "/a.m3u")
        val b = add("B", "/b.m3u")
        val finder = container.counterpartFinder

        // Einzelner Zugang: Gegenstück nur über passende Namen (der Eintrag in „Alle Zugänge“ selbst zählt hier nicht).
        val trt2B = idOf(b, "TR: TRT 2")
        assertEquals(listOf(idOf(a, "TR: TRT 2 HD")), finder.forItem(trt2B).filter { it.playlistId != Combined.ID }.map { it.itemId })
        assertTrue(finder.forItem(idOf(b, "DE: ProSieben MAXX")).none { it.playlistId != Combined.ID })

        // „Alle Zugänge“: TRT 1, TRT 2, ProSieben und MAXX je ein eigener Eintrag mit den richtigen Quellen.
        finder.clear()
        container.combined.builder.rebuild("Test")
        val sources = finder.forItem(trt2B).filter { it.playlistId != Combined.ID }.map { it.itemId }
        assertEquals(listOf(idOf(a, "TR: TRT 2 HD")), sources)
        val pro7 = finder.forItem(idOf(a, "DE: ProSieben HD"))
        assertTrue(pro7.none { it.itemId == idOf(b, "DE: ProSieben MAXX") })
    }

    @Test
    fun zappingListsHoldEachChannelOnce() = runBlocking {
        val a = add("A", "/a.m3u")
        val live = container.liveRepository
        val hd = idOf(a, "DE: ProSieben HD")
        val uhd = idOf(a, "DE: ProSieben UHD")
        // Beide Qualitäten gesehen (z. B. UHD über die Suche): „Zuletzt gesehen“ zeigt ProSieben trotzdem nur einmal.
        container.playbackRepository.markChannelWatched(hd)
        container.playbackRepository.markChannelWatched(uhd)
        val recent = live.channelIds(a, com.mg.player.domain.model.Category.RECENT)
        assertEquals(1, recent.count { it == hd || it == uhd })
        // Leicht andere Schreibweise desselben Senders mit gleicher EPG-ID: ein Eintrag in „Alle Sender“, die andere
        // Quelle hängt darunter.
        val all = live.channelIds(a, com.mg.player.domain.model.Category.ALL)
        val aj = listOf(idOf(a, "AR: AL JAZERA HD"), idOf(a, "AR: AL JAZEERA LQ"))
        assertEquals(1, all.count { it in aj })
        val shown = live.channel(all.first { it in aj })!!
        assertEquals(2, live.variants(shown).size)
    }
}
