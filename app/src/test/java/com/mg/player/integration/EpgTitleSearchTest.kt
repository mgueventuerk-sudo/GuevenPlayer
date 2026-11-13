package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.screenshots.ShowcaseServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * EPG-Sendungstitel sind jetzt Teil des Suchindex (SearchDao.epgProgramSource) - eine Suche nach einer laufenden
 * Einzelsendung wie "Tatort" fand bisher nichts, obwohl das EPG den Titel längst kennt (Funktions-Audit). Ein
 * Treffer verweist auf den Sender, der die Sendung zeigt - es gibt keine eigene Such-Kategorie für Sendungen.
 * "Tatort" steht in ShowcaseServer.PROGRAMS[1], auf dem Kanal mit epgId "daserste.de" (DE: Das Erste HD).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EpgTitleSearchTest {

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun searchingAnEpgProgramTitleFindsTheAiringChannel() = runBlocking {
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = server.start(), username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        container.epgRepository.refresh(container.playlistRepository.getPlaylist(pid)!!) {}
        container.searchIndexer.rebuild(pid, force = true)

        val hits = container.searchRepository.search(pid, "Tatort")
        assertTrue(
            "Suche nach einer EPG-Sendung sollte den Sender finden, der sie zeigt. Treffer: ${hits.map { it.title }}",
            hits.any { it.type == ContentType.LIVE && it.title.contains("Erste", ignoreCase = true) },
        )
    }

    @Test
    fun rebuildingTheIndexAgainDoesNotDuplicateHits() = runBlocking {
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = server.start(), username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        container.epgRepository.refresh(container.playlistRepository.getPlaylist(pid)!!) {}
        container.searchIndexer.rebuild(pid, force = true)
        container.searchIndexer.rebuild(pid, force = true)

        val hits = container.searchRepository.search(pid, "Tatort")
        assertTrue(
            "Derselbe Sender sollte nicht mehrfach auftauchen. Treffer: ${hits.map { it.id }}",
            hits.filter { it.type == ContentType.LIVE }.map { it.id }.distinct().size == hits.count { it.type == ContentType.LIVE },
        )
    }
}
