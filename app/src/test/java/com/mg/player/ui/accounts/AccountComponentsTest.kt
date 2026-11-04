package com.mg.player.ui.accounts

import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
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

    private fun playlist(customEpgUrl: String = "", headerEpgUrl: String = "") = Playlist(
        id = 1,
        name = "Test",
        type = PlaylistType.XTREAM,
        serverUrl = "https://provider.example",
        username = "user",
        password = "password",
        m3uUrl = "",
        customEpgUrl = customEpgUrl,
        headerEpgUrl = headerEpgUrl,
        lastUpdatedMs = 0,
    )
}
