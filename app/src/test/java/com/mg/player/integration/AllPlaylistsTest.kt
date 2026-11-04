package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.repository.PlaybackRepositoryImpl
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.ui.player.AccountChoice
import com.mg.player.ui.player.AccountGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Suche über alle Zugänge und Verbindungsanzeige ("1/1") mit zwei echten Zugängen gegen nachgebaute Server. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AllPlaylistsTest {

    private val first = FakeXtreamServer()
    private val second = FakeXtreamServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var a = 0L
    private var b = 0L
    private lateinit var secondBase: String
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() = runBlocking {
        val baseA = first.start()
        secondBase = second.start()
        a = setupStep("Anbieter A anlegen") {
            container.playlistRepository.addPlaylist(
                PlaylistInput(
                    name = "Anbieter A",
                    type = PlaylistType.XTREAM,
                    serverUrl = baseA,
                    username = "u",
                    password = "p",
                    allowInsecureHttp = true,
                ),
            )
        }
        b = setupStep("Anbieter B anlegen") {
            container.playlistRepository.addPlaylist(
                PlaylistInput(
                    name = "Anbieter B",
                    type = PlaylistType.XTREAM,
                    serverUrl = secondBase,
                    username = "u",
                    password = "p",
                    allowInsecureHttp = true,
                ),
            )
        }
        setupStep("Anbieter A laden") { container.playlistRepository.refreshContent(a) {} }
        setupStep("Anbieter B laden") { container.playlistRepository.refreshContent(b) {} }
    }

    @After
    fun tearDown() {
        first.shutdown()
        second.shutdown()
        playbackScope.cancel()
    }

    @Test
    fun searchFindsTitlesInEveryPlaylist() = runBlocking {
        val hits = container.searchRepository.searchAll("Inception", activePlaylistId = b)
        val movies = hits.filter { it.type == ContentType.MOVIE }
        assertEquals(listOf("$b:M:501", "$a:M:501"), movies.map { it.id })
        assertEquals(listOf("Anbieter B", "Anbieter A"), movies.map { it.source })
        assertEquals(listOf(b, a), movies.map { it.playlistId })
        assertTrue(movies.first().subtitle.orEmpty().endsWith("Anbieter B"))

        val zdf = container.searchRepository.searchAll("zdf", activePlaylistId = a).filter { it.type == ContentType.LIVE }
        assertEquals(setOf(a, b), zdf.map { it.playlistId }.toSet())
        // Nur der aktive Zugang wie bisher: keine Zugangsnamen, keine fremden Treffer.
        val only = container.searchRepository.search(a, "Inception")
        assertTrue(only.all { it.id.startsWith("$a:") && it.source == null })
    }

    @Test
    fun connectionsAreShownPerPlaylist() = runBlocking {
        first.activeConnections = 1
        first.maxConnections = 1
        second.activeConnections = 0
        second.maxConnections = 2
        // M3U-Link im Xtream-Format: Verbindungen kommen ebenfalls von player_api.php.
        val m3u = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "M3U", type = PlaylistType.M3U, m3uUrl = "$secondBase/get.php?username=u&password=p&type=m3u_plus", allowInsecureHttp = true),
        )
        val plain = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Liste", type = PlaylistType.M3U, m3uUrl = "http://liste.invalid/kanaele.m3u"),
        )
        val monitor = container.connectionMonitor
        monitor.refresh(force = true)
        val status = monitor.status.value
        assertEquals("1/1", status[a]?.label)
        assertTrue(status[a]!!.inUse && status[a]!!.full)
        assertEquals("0/2", status[b]?.label)
        assertFalse(status[b]!!.inUse)
        assertEquals("0/2", status[m3u]?.label)
        assertNull("normale M3U-Liste hat keine Angabe", status[plain])

        // Gedrosselt: Eine erneute Abfrage gleich danach fragt nicht noch einmal.
        second.activeConnections = 2
        monitor.refresh()
        assertEquals("0/2", monitor.status.value[b]?.label)
        monitor.refresh(force = true)
        assertEquals("2/2", monitor.status.value[b]?.label)

        // Gelöschter Zugang verschwindet aus der Anzeige.
        container.playlistRepository.deletePlaylist(b)
        monitor.refresh(force = true)
        assertNull(monitor.status.value[b])
    }

    @Test
    fun counterpartsAcrossPlaylists() = runBlocking {
        val finder = container.counterpartFinder
        // ZDF gibt es bei B in HD, FHD und UHD: geliefert wird genau eins (die beste Qualität).
        val zdf = finder.forItem("$a:L:101").single()
        assertTrue(zdf.itemId in listOf("$b:L:101", "$b:L:102", "$b:L:103"))
        assertEquals(listOf("Anbieter B"), finder.forItem("$a:L:101").map { it.playlistName })
        assertEquals(listOf("$b:M:501"), finder.forItem("$a:M:501").map { it.itemId })
        assertEquals(listOf("$a:S:701"), finder.forItem("$b:S:701").map { it.itemId })
        // „ZDF HD“ hat ein Gegenstück, aber nicht „ZDFneo“ als ZDF.
        assertTrue(finder.forItem("$a:L:107").none { it.itemId == "$b:L:101" })
    }

    @Test
    fun progressAndFavoritesAreShared() = runBlocking {
        val playback = PlaybackRepositoryImpl(
            db = container.database,
            counterparts = container.counterpartFinder,
            scope = playbackScope,
            noHistory = { false },
        )
        playback.saveProgress(PlaybackProgress.movieKey("$a:M:501"), 1_200_000, 7_000_000, null)
        waitFor { playback.progress(PlaybackProgress.movieKey("$b:M:501"))?.positionMs == 1_200_000L }
        // Filme ohne Jahr (z. B. „Der Pate [4K]“) gelten bewusst nicht als gleich – daher ein Film mit Jahr.
        playback.toggleFavorite(ContentType.MOVIE, "$b:M:505")
        waitFor { container.database.favoriteDao().exists(ContentType.MOVIE.name, "$a:M:505") }
        playback.toggleFavorite(ContentType.MOVIE, "$b:M:505")
        waitFor { !container.database.favoriteDao().exists(ContentType.MOVIE.name, "$a:M:505") }
    }

    @Test
    fun busyAccountAsksAndOffersAnotherAccount() = runBlocking {
        first.activeConnections = 1
        first.maxConnections = 1
        second.activeConnections = 0
        second.maxConnections = 2
        container.connectionMonitor.refresh(force = true)
        val gate = AccountGate(container)
        val choice = async { gate.beforeStart("$a:L:101") }
        waitFor { gate.prompt.value != null }
        val prompt = gate.prompt.value!!
        assertEquals("Zugang belegt", prompt.title)
        assertEquals(listOf(b), prompt.alternatives.map { it.playlistId })
        gate.answer(AccountChoice.Switch(prompt.alternatives.first()))
        assertEquals(AccountChoice.Switch(prompt.alternatives.first()), choice.await())

        // Eigene, gerade beendete Wiedergabe: keine Nachfrage.
        container.connectionMonitor.markOwnUse(a)
        assertEquals(AccountChoice.Continue, gate.beforeStart("$a:L:101"))
        // Freier Zugang: keine Nachfrage.
        assertEquals(AccountChoice.Continue, gate.beforeStart("$b:L:101"))
    }

    @Test
    fun failureOffersEachAccountOnlyOnce() = runBlocking {
        container.connectionMonitor.refresh(force = true)
        val gate = AccountGate(container)
        val choice = async { gate.afterFailure("$a:M:501") }
        waitFor { gate.prompt.value != null }
        assertEquals("Stream ausgefallen", gate.prompt.value!!.title)
        gate.answer(AccountChoice.Cancel)
        assertEquals(AccountChoice.Cancel, choice.await())
        // B wurde schon angeboten, A ist ausgefallen: nichts mehr anzubieten.
        assertEquals(AccountChoice.Cancel, gate.afterFailure("$b:M:501"))
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(25) }
    }

    private suspend fun <T> setupStep(name: String, action: suspend () -> T): T =
        try {
            withTimeout(30_000) { action() }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("AllPlaylistsTest setup timed out: $name", error)
        }
}
