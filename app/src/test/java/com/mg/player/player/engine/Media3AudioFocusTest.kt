package com.mg.player.player.engine

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.HwDecoding
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@OptIn(UnstableApi::class)
class Media3AudioFocusTest {
    @Test fun mutedBackgroundPlayerDoesNotRequestAudioFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        val config = Media3Config(BufferProfile.STANDARD, HwDecoding.AUTO, tunneling = false, passthrough = false)
        val player = Media3Build.player(
            context,
            config,
            BufferLimit.forDevice(heapMb = 192, lowRamDevice = false),
            OkHttpDataSource.Factory(OkHttpClient()),
            handleAudioFocus = false,
        )
        try {
            player.setMediaItem(MediaItem.fromUri("file:///nonexistent-live.ts"))
            player.prepare()
            player.play()
            assertNull(audio.lastAudioFocusRequest)
        } finally {
            player.release()
        }
    }
}
