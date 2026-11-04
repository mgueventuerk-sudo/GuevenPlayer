package com.mg.player.player.engine

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Renderer für ExoPlayer mit Blick auf „kein Ton“:
 * - Ohne [passthrough] geht der Ton immer als PCM raus; Dolby/DTS dekodiert das Gerät selbst. Beim Durchreichen
 *   meldet manches Fire TV bzw. mancher Fernseher ein Format als abspielbar, gibt es dann aber stumm wieder.
 * - FFmpeg dekodiert als Reserve, wofür das Gerät keinen Decoder hat: MP2 (ARD/ZDF/Dritte über TS), AC3/E-AC3
 *   und DTS auf Handys. Zuerst kommen weiter die Geräte-Decoder (schneller, sparsamer).
 */
@OptIn(UnstableApi::class)
internal class Media3Renderers(context: Context, private val passthrough: Boolean) : DefaultRenderersFactory(context) {

    // Builder bewusst ohne Context: Mit Context ermittelt die Sink die Fähigkeiten von HDMI/TV selbst (= Passthrough).
    @Suppress("DEPRECATION")
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        if (passthrough) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
