package com.mg.player.integration

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.filter.CountrySelection
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Suche mit einem Xtream-Zugang – so eingerichtet wie in der App (Import, Länderauswahl Deutschland). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class XtreamSearchTest {

    private val xtream = FakeXtreamServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        val base = xtream.start()
        playlistId = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Mein Anbieter", type = PlaylistType.XTREAM, serverUrl = base, username = "user", password = "pass", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(playlistId) {}
        container.countryFilter.apply(playlistId, CountrySelection(setOf("DE"), keepUnknown = true))
        container.settingsRepository.update { it.copy(activePlaylistId = playlistId) }
    }

    @After
    fun tearDown() = xtream.shutdown()

    private fun search(q: String) = runBlocking { container.searchRepository.search(playlistId, q) }

    private fun assertFinds(q: String, type: ContentType, title: String) {
        val hits = search(q)
        assertTrue("„$q“ sollte $title finden, gefunden: $hits", hits.any { it.type == type && it.title.contains(title, ignoreCase = true) })
    }

    @Test fun channelByName() = assertFinds("zdf", ContentType.LIVE, "ZDF")

    @Test fun twoLettersUseIndex() = assertFinds("zd", ContentType.LIVE, "ZDF")

    @Test fun twoLettersMovie() = assertFinds("in", ContentType.MOVIE, "Inception")

    @Test fun upperCase() = assertFinds("ZDF", ContentType.LIVE, "ZDF")

    @Test fun withTrailingSpace() = assertFinds("zdf ", ContentType.LIVE, "ZDF")

    @Test fun countryPrefixTyped() = assertFinds("de zdf", ContentType.LIVE, "ZDF")

    @Test fun channelWithPrefixAndSymbols() = assertFinds("rtl", ContentType.LIVE, "RTL")

    @Test fun channelWithDots() = assertFinds("sat", ContentType.LIVE, "SAT")

    @Test fun channelWithDash() = assertFinds("n-tv", ContentType.LIVE, "n-tv")

    @Test fun channelNtvWithoutDash() = assertFinds("ntv", ContentType.LIVE, "n-tv")

    @Test fun channelMidWord() = assertFinds("neo", ContentType.LIVE, "ZDFneo")

    @Test fun channelTwoWords() = assertFinds("das erste", ContentType.LIVE, "Erste")

    @Test fun movieByTitle() = assertFinds("inception", ContentType.MOVIE, "Inception")

    @Test fun movieWithBadge() = assertFinds("pate", ContentType.MOVIE, "Pate")

    @Test fun movieWithUmlaut() = assertFinds("känguru", ContentType.MOVIE, "Känguru")

    @Test fun movieUmlautTypedWithout() = assertFinds("kanguru", ContentType.MOVIE, "Känguru")

    @Test fun seriesByTitle() = assertFinds("dark", ContentType.SERIES, "Dark")

    @Test fun seriesTwoWords() = assertFinds("babylon", ContentType.SERIES, "Babylon")

    @Test fun voiceInputWithSpaces() = assertFinds("Z D F", ContentType.LIVE, "ZDF")

    @Test fun fancyFontChannel() = assertFinds("prosieben", ContentType.LIVE, "ProSieben")

    /** Kapitälchen-Namen ("ᴢᴅꜰ"), wie viele Anbieter sie nutzen – samt Ländererkennung der Gruppe "ᴅᴇ ┃ ꜱᴘᴏʀᴛ". */
    @Test fun smallCapitalsChannel() = assertFinds("bundesliga", ContentType.LIVE, "BUNDESLIGA")

    @Test fun germanUmlautSpelling() = assertFinds("kaenguru", ContentType.MOVIE, "Känguru")

    /** Index vorhanden, aber leer/veraltet: Die Suche findet trotzdem und baut den Index neu auf. */
    @Test
    fun staleIndexStillFindsAndHeals() {
        runBlocking { container.searchIndexer.clear(playlistId) }
        ApplicationProvider.getApplicationContext<PlayerApp>().getSharedPreferences("search_index", 0)
            .edit().putString("key_v2_$playlistId", "$playlistId.0").commit()
        assertFinds("zdf", ContentType.LIVE, "ZDF")
        val deadline = System.currentTimeMillis() + 10_000
        while (runBlocking { container.searchIndexer.indexedCount(playlistId) } == 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        assertTrue("Index wurde nicht neu aufgebaut", runBlocking { container.searchIndexer.indexedCount(playlistId) } > 0)
    }

    /** Nach einem App-Update (Index im alten Format): sofort Treffer über die direkte Suche. */
    @Test
    fun oldIndexFormatFallsBackToTables() {
        ApplicationProvider.getApplicationContext<PlayerApp>().getSharedPreferences("search_index", 0)
            .edit().remove("ready_v2_$playlistId").remove("key_v2_$playlistId").commit()
        assertFinds("rtl", ContentType.LIVE, "RTL")
    }

    /** Neuaufbau unter neuem Schlüssel: Danach gibt es genau einen Stand, der alte ist gelöscht. */
    @Test
    fun rebuildSwitchesAndRemovesOldIndex() {
        runBlocking {
            container.searchIndexer.rebuild(playlistId)
            container.searchIndexer.rebuild(playlistId)
        }
        val keys = container.database.openHelper.readableDatabase
            .query("SELECT COUNT(DISTINCT playlistId) FROM search_index WHERE playlistId = '$playlistId' OR playlistId LIKE '$playlistId.%'")
            .use {
                it.moveToFirst()
                it.getInt(0)
            }
        assertEquals(1, keys)
        assertFinds("zdf", ContentType.LIVE, "ZDF")
    }

    @Test
    fun hiddenCountriesAreNotFound() {
        assertTrue(search("trt").none { it.type == ContentType.LIVE })
    }
}
