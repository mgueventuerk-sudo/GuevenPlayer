package com.mg.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.player.engine.Media3Renderers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** „Kein Ton“: FFmpeg steht als Reserve-Decoder hinter den Geräte-Decodern bereit (MP2, AC3, E-AC3, DTS …). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Media3RenderersTest {

    @Test
    fun ffmpegFollowsDeviceAudioDecoder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(false, true).forEach { passthrough ->
            val renderers = Media3Renderers(context, passthrough).createRenderers(
                Handler(Looper.getMainLooper()),
                object : VideoRendererEventListener {},
                object : AudioRendererEventListener {},
                { _ -> },
                { _ -> },
            )
            val device = renderers.indexOfFirst { it is MediaCodecAudioRenderer }
            val ffmpeg = renderers.indexOfFirst { it is FfmpegAudioRenderer }
            assertEquals(1, renderers.count { it is FfmpegAudioRenderer })
            assertTrue("Geräte-Decoder zuerst (passthrough=$passthrough)", device in 0 until ffmpeg)
        }
    }
}
