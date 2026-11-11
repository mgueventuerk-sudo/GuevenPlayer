package com.mg.player.ui.accounts

import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountComponentsTest {

    @Test
    fun httpEpgUrlIsMarkedEvenWhenProviderUsesHttps() {
        assertTrue(playlist(customEpgUrl = "http://epg.example/guide.xml").isPlainHttp())
        assertTrue(playlist(headerEpgUrl = "http://epg.example/guide.xml").isPlainHttp())
    }

    @Test
    fun httpsUrlsAreNotMarkedAsCleartext() {
        assertFalse(playlist(customEpgUrl = "https://epg.example/guide.xml").isPlainHttp())
    }

    @Test
    fun updatedLabelShowsNotLoadedOrAFormattedDate() {
        assertEquals("noch nicht geladen", playlist().updatedLabel())
        // 01.01.2024 12:34 UTC – unabhaengig von der Zeitzone des Testrechners ueberprueft via Pattern.
        val label = playlist(lastUpdatedMs = 1_704_111_240_000L).updatedLabel()
        assertTrue(label, Regex("""aktualisiert \d{2}\.\d{2}\. \d{2}:\d{2}""").matches(label))
    }

    private fun playlist(customEpgUrl: String = "", headerEpgUrl: String = "", lastUpdatedMs: Long = 0) = Playlist(
        id = 1,
        name = "Test",
        type = PlaylistType.XTREAM,
        serverUrl = "https://provider.example",
        username = "user",
        password = "password",
        m3uUrl = "",
        customEpgUrl = customEpgUrl,
        headerEpgUrl = headerEpgUrl,
        lastUpdatedMs = lastUpdatedMs,
    )
}
