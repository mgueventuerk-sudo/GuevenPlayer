package com.mg.player.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaylistHttpOptInTest {

    @Test
    fun providerHttpUrlsRequireAnExplicitPerPlaylistOptIn() = runBlocking {
        val container = ApplicationProvider.getApplicationContext<PlayerApp>().container
        val id = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "HTTP-Test", type = PlaylistType.M3U, m3uUrl = "https://provider.example/list.m3u"),
        )
        try {
            assertFalse(container.playlistRepository.getPlaylist(id)!!.allowInsecureHttp)
            val denied = runCatching { container.streamUrlResolver.resolve("http://provider.example/live/1.ts", "$id:L:1") }.exceptionOrNull()
            assertTrue(denied is IOException)

            container.playlistRepository.setAllowInsecureHttp(id, true)
            assertEquals("http://provider.example/live/1.ts", container.streamUrlResolver.resolve("http://provider.example/live/1.ts", "$id:L:1"))
        } finally {
            container.playlistRepository.deletePlaylist(id)
        }
    }
}
