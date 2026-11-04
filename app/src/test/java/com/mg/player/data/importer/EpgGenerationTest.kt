package com.mg.player.data.importer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.screenshots.ShowcaseServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * EPG mit Generationen: Ein zweiter Import ersetzt den ersten vollständig (keine Doppelten, alte gelöscht), und ein
 * abgebrochener Import lässt das sichtbare EPG unverändert.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EpgGenerationTest {
    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = server.shutdown()

    private fun count(sql: String): Int = container.database.openHelper.readableDatabase.query(sql).use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun secondImportReplacesFirstAndFailureKeepsOld() = runBlocking {
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Show", type = PlaylistType.XTREAM, serverUrl = server.start(), username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        val epg = container.epgRepository
        val playlist = container.playlistRepository.getPlaylist(pid)!!
        epg.refresh(playlist) {}
        val visible = count("SELECT COUNT(*) FROM epg_programs")
        assertTrue(visible > 0)

        // Zweiter Import: gleich viele Sendungen, die alte Generation ist weg.
        epg.refresh(playlist) {}
        assertEquals(visible, count("SELECT COUNT(*) FROM epg_programs"))
        assertEquals(1, count("SELECT COUNT(DISTINCT generation) FROM epg_programs"))
        assertTrue(container.database.epgDao().hasPrograms(pid))

        // Quelle fällt aus: nichts ändert sich, keine halben Reste.
        server.shutdown()
        epg.refresh(playlist) {}
        assertEquals(visible, count("SELECT COUNT(*) FROM epg_programs"))
        assertTrue(container.database.epgDao().hasPrograms(pid))
    }
}
