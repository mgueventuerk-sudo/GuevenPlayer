package com.mg.player.player.engine

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.HwDecoding

/** Einstellungen, mit denen eine Media3-Instanz gebaut wurde – ändern sie sich, wird neu gebaut. */
internal data class Media3Config(val buffer: BufferProfile, val hw: HwDecoding, val tunneling: Boolean, val passthrough: Boolean)

/** Bau von Player, Bildfläche und Statistik für [Media3Engine]. */
@OptIn(UnstableApi::class)
internal object Media3Build {

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    fun player(
        context: Context,
        config: Media3Config,
        bufferLimit: BufferLimit,
        factory: OkHttpDataSource.Factory,
        handleAudioFocus: Boolean = true,
    ): ExoPlayer {
        val buffer = config.buffer.toConfig()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(buffer.minMs, buffer.maxMs, buffer.playbackMs, buffer.rebufferMs)
            .setTargetBufferBytes(bufferLimit.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(bufferLimit.prioritizeTime)
            .build()
        val renderers = Media3Renderers(context, config.passthrough)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(Media3Support.codecSelector(config.hw))
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        // Deutsch als bevorzugte Tonspur; Tunneling (nur Live, falls Gerät/Stream es können) entlastet schwache Sticks –
        // bei Filmen verzögert es auf manchen Geräten den Start und stört Ton-Passthrough.
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages("de", "deu", "ger")
                    .setTunnelingEnabled(config.tunneling),
            )
        }
        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(Media3Support.sourceFactory(factory, extractors))
            // Stumme Bildbeweis-/Standbild-/Ersatz-Player dürfen dem sichtbaren Player keinen Fokus wegnehmen.
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /** Bildfläche ohne eigene Bedienelemente und ohne Fokus (die Steuerung liegt in Compose). */
    fun view(context: Context, player: ExoPlayer?): PlayerView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        // Beim Quellenwechsel (und Zappen) bleibt das letzte Bild stehen, bis das neue kommt – kein Schwarzbild (ab 3.51).
        setKeepContentOnPlayerReset(true)
        keepScreenOn = true
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        this.player = player
    }

    fun stats(p: ExoPlayer, bytesLoaded: Long, droppedFrames: Int): StreamStats {
        val video = p.videoFormat
        val audio = p.audioFormat
        return StreamStats(
            width = Media3Support.known(video?.width),
            height = Media3Support.known(video?.height),
            frameRate = Media3Support.known(video?.frameRate),
            videoCodec = Media3Support.codecName(video),
            videoBitrate = Media3Support.bitrateOf(video),
            audioCodec = Media3Support.codecName(audio),
            audioChannels = Media3Support.known(audio?.channelCount),
            bytesLoaded = bytesLoaded,
            bufferedMs = p.totalBufferedDuration,
            droppedFrames = droppedFrames,
        )
    }
}
