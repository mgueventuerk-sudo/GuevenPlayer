package com.mg.player.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.security.SecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StreamSecretStorageTest {

    @Before
    fun setUp() {
        SecretCipher.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun encryptsCredentialsInQueryAndHeadersAndCanResolveThem() {
        val raw = "https://cdn.example/live.m3u8?token=hidden-token|Authorization=Bearer%20hidden-header"
        val stored = StreamSecretStorage.protect(raw)
        assertTrue(StreamSecretStorage.isProtected(stored))
        assertFalse("hidden-token" in stored)
        assertFalse("hidden-header" in stored)
        assertEquals(raw, StreamSecretStorage.reveal(stored))
    }

    @Test
    fun detectsUrlUserInfoAndProviderCredentialPaths() {
        assertTrue(StreamSecretStorage.containsSecret("https://alice:secret@example.test/channel.ts"))
        assertTrue(StreamSecretStorage.containsSecret("http://provider.test/live/alice/secret/12.ts"))
        assertTrue(StreamSecretStorage.containsSecret("https://cdn.test/a.m3u8|X-Auth-Token=abc"))
    }

    @Test
    fun leavesPublicStreamsAndPlaybackHeadersUnchanged() {
        val raw = "https://cdn.example/live.m3u8|User-Agent=VLC%2F3.0&Referer=https%3A%2F%2Fexample.test"
        assertFalse(StreamSecretStorage.containsSecret(raw))
        assertEquals(raw, StreamSecretStorage.protect(raw))
    }
}
