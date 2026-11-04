package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.player.PlayableResolver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * „Alle Zugänge“ Ende zu Ende mit zwei Zugängen, die dieselben Inhalte anders schreiben (Plan 3.28–3.31):
 * Zusammenführen, Gruppen, Quellen, Abspielen, Folgen ergänzen, Favoriten übernehmen, Suche, Ausschließen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CombinedAccountsTest {

    private val server = MockWebServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    private val second = """
        #EXTM3U
        #EXTINF:-1 tvg-id="ZDFHD.de" group-title="GERMANY GENERAL",|DE| ZDF UHD
        http://stream.invalid/b/zdf_uhd.ts
        #EXTINF:-1 tvg-id="" group-title="GERMANY GENERAL",DE: ARD ᴴᴰ
        http://stream.invalid/b/ard.ts
        #EXTINF:-1 tvg-id="nur-b.de" group-title="DE | Vollprogramm",DE: Nur Bei B
        http://stream.invalid/b/nurb.ts
        #EXTINF:-1 group-title="VOD | Action",Inception 2010 [FHD]
        http://stream.invalid/b/inception.mkv
        #EXTINF:-1 group-title="SERIEN | Mystery",Dark S01 E02
        http://stream.invalid/b/dark_s01e02.mkv
        #EXTINF:-1 group-title="SERIEN | Mystery",Dark S02 E01
        http://stream.invalid/b/dark_s02e01.mkv
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if ("b.m3u" in request.path.orEmpty()) second else TestPlaylists.M3U)
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

    @Test
    fun twoAccountsBecomeOneList() = runBlocking {
        val a = add("A", "/a.m3u")
        val b = add("B", "/b.m3u")
        val zdfA = container.searchRepository.search(a, "zdf").first { it.type == ContentType.LIVE && it.title == "ZDF" }
        container.playbackRepository.toggleFavorite(ContentType.LIVE, zdfA.id)
        assertTrue(container.combined.builder.rebuild("Test"))

        val live = container.liveRepository
        val all = live.channelIds(Combined.ID, Category.ALL)
        // A: ZDF (2 Qualitäten), ZDFneo, Das Erste, tagesschau24, TRT 1; B: ZDF UHD, ARD, Nur Bei B → 6 Sender.
        assertEquals(6, all.size)
        val zdf = live.channel(all.first { live.channel(it)!!.name.startsWith("ZDF") && !live.channel(it)!!.name.contains("neo") })!!
        val sources = live.variants(zdf)
        assertEquals(setOf(a, b), sources.map { it.playlistId }.toSet())
        assertEquals(3, sources.size)

        // Favorit vom Original übernommen.
        assertTrue(container.playbackRepository.isFavorite(ContentType.LIVE, zdf.id).first())

        // Abspielen: beste Quelle zuerst (UHD), echte Adresse statt kombinierter ID.
        val resolver = PlayableResolver(container)
        val playable = resolver.resolve(PlayRequest.Live(zdf.id, Category.ALL))!!
        assertEquals(3, playable.sourceCount)
        assertTrue(playable.url.startsWith("http://stream.invalid/"))
        assertTrue(playable.canSwitchSource)

        // Gruppen: „DE | Vollprogramm“ (A) und „GERMANY GENERAL“ (B) sind dieselbe Gruppe.
        val groups = live.categories(Combined.ID).first().drop(4)
        assertEquals(groups.joinToString { it.name }, 1, groups.count { it.name == "Deutschland · Vollprogramm" })

        // Film in beiden Zugängen: ein Eintrag, zwei Quellen; Abspielen startet eine echte Quelle.
        val movies = container.searchRepository.search(Combined.ID, "inception").filter { it.type == ContentType.MOVIE }
        assertEquals(movies.toString(), 1, movies.size)
        val moviePlayable = resolver.resolve(PlayRequest.Movie(movies.first().id, 0))!!
        assertEquals(2, moviePlayable.vodSources.size)
        assertTrue(moviePlayable.url.startsWith("http://stream.invalid/"))
        assertEquals("movie:${movies.first().id}", moviePlayable.progressKey)

        // Serie: A hat S01E01+E02, B hat S01E02+S02E01 → zusammen 3 Folgen; die doppelte hat zwei Quellen.
        val dark = container.searchRepository.search(Combined.ID, "dark").first { it.type == ContentType.SERIES }
        val episodes = container.seriesRepository.loadEpisodes(dark.id)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), episodes.map { it.season to it.episode })
        val e2 = resolver.resolve(PlayRequest.Episode(episodes[1].id, dark.id, 0))!!
        assertEquals(2, e2.vodSources.size)

        // Aufnahme/Vorschau ohne Quellenwahl: Die kombinierte ID wird zur Adresse einer Quelle.
        assertTrue(container.streamUrlResolver.resolve("", zdf.id).startsWith("http://stream.invalid/"))

        // Suche zeigt jeden Inhalt einmal.
        val pages = container.searchRepository.searchPages("zdf", Combined.ID)
        assertEquals("pages", 1, pages.playlists.size)
        assertEquals(pages.all.toString(), 1, pages.all.count { it.type == ContentType.LIVE && it.title == "ZDF" })

        // Zugang ausschließen → nur noch einer übrig → Kombination verschwindet.
        container.combined.settings.update { it.copy(excluded = setOf(b)) }
        assertFalse(container.combined.builder.rebuild("Test"))
        assertEquals(0, live.channelIds(Combined.ID, Category.ALL).size)
        container.combined.settings.update { it.copy(excluded = emptySet()) }
    }

    @Test
    fun matchingIsAutomaticAndReportsWhatItDid() = runBlocking {
        add("A", "/a.m3u")
        add("B", "/b.m3u")
        container.combined.builder.rebuild("Test")
        // „Das Erste“ (A) und „ARD ᴴᴰ“ (B) laufen ohne Zutun zusammen; der Aufbau meldet, was er erkannt hat.
        val stats = container.combined.builder.stats.value
        assertTrue(stats != null && stats.channels > 0)
        assertTrue(stats!!.identified > 0)
    }

    @Test
    fun combinedAppearsAsAccountFromTwoAccounts() = runBlocking {
        add("A", "/a.m3u")
        assertEquals(null, container.playlistRepository.getPlaylist(Combined.ID))
        add("B", "/b.m3u")
        assertNotNull(container.playlistRepository.getPlaylist(Combined.ID))
        assertEquals(Combined.NAME, container.playlistRepository.getPlaylist(Combined.ID)!!.name)
    }
}
