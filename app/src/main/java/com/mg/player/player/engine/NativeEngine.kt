package com.mg.player.player.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.mg.player.core.network.IPTV_USER_AGENT
import com.mg.player.core.util.StreamTarget
import com.mg.player.domain.model.ChapterInfo
import com.mg.player.domain.model.PlayerEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Android-System-MediaPlayer: sehr ressourcenschonender Hardware-Player als Fallback für ältere Geräte. */
@OptIn(UnstableApi::class)
class NativeEngine(context: Context) : VideoPlayerEngine {

    override val type = PlayerEngineType.NATIVE
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var currentHolder: SurfaceHolder? = null
    private var aspectLayout: AspectRatioFrameLayout? = null
    private var prepared = false
    private var muted = false

    override val positionMs: Long get() = if (prepared) player?.currentPosition?.toLong() ?: 0 else 0
    override val durationMs: Long get() = if (prepared) player?.duration?.toLong()?.coerceAtLeast(0) ?: 0 else 0

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
            currentHolder = surfaceHolder
            player?.setDisplay(surfaceHolder)
        }

        override fun surfaceChanged(surfaceHolder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
            player?.setDisplay(null)
            currentHolder = null
        }
    }

    override fun attachSurface(container: FrameLayout) {
        val aspect = AspectRatioFrameLayout(container.context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        val surfaceView = SurfaceView(container.context).apply {
            isFocusable = false
            keepScreenOn = true
            holder.addCallback(surfaceCallback)
        }
        aspect.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.removeAllViews()
        container.addView(aspect)
        aspectLayout = aspect
    }

    override fun detachSurface(container: FrameLayout) {
        // Schon an eine andere Fläche übergeben (Vorschau → Vollbild): die neue nicht wieder abhängen.
        val layout = aspectLayout?.takeIf { it.parent === container } ?: return
        player?.setDisplay(null)
        currentHolder = null
        container.removeView(layout)
        aspectLayout = null
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        val volume = if (muted) 0f else 1f
        runCatching { player?.setVolume(volume, volume) }
    }

    override fun prepare(request: StreamRequest) {
        releasePlayer()
        prepared = false
        _state.update { it.copy(status = PlaybackStatus.BUFFERING, isPlaying = false, errorMessage = null) }
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        mp.setScreenOnWhilePlaying(true)
        if (muted) mp.setVolume(0f, 0f)
        currentHolder?.let { mp.setDisplay(it) }
        mp.setOnPreparedListener {
            prepared = true
            if (request.startPositionMs > 0) it.seekTo(request.startPositionMs.toInt())
            it.start()
            _state.update { s -> s.copy(status = PlaybackStatus.READY, isPlaying = true, tracksVersion = s.tracksVersion + 1) }
        }
        mp.setOnVideoSizeChangedListener { _, width, height ->
            if (width > 0 && height > 0) aspectLayout?.setAspectRatio(width.toFloat() / height)
        }
        mp.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> _state.update { it.copy(status = PlaybackStatus.BUFFERING) }
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> _state.update { it.copy(status = PlaybackStatus.READY) }
            }
            false
        }
        mp.setOnErrorListener { _, what, extra ->
            _state.update { it.copy(status = PlaybackStatus.ERROR, isPlaying = false, errorMessage = "Systemfehler $what/$extra", errorKind = ErrorKind.OTHER) }
            true
        }
        mp.setOnCompletionListener {
            _state.update { it.copy(status = PlaybackStatus.ENDED, isPlaying = false) }
        }
        try {
            val target = StreamTarget.parse(request.url)
            val headers = target.extraHeaders + ("User-Agent" to (target.userAgent ?: IPTV_USER_AGENT))
            mp.setDataSource(appContext, target.url.toUri(), headers)
            mp.prepareAsync()
        } catch (e: Exception) {
            _state.update { it.copy(status = PlaybackStatus.ERROR, errorMessage = e.message) }
        }
    }

    override fun play() {
        if (!prepared) return
        player?.start()
        _state.update { it.copy(isPlaying = true) }
    }

    override fun pause() {
        if (!prepared) return
        player?.pause()
        _state.update { it.copy(isPlaying = false) }
    }

    override fun seekTo(positionMs: Long) {
        if (prepared) player?.seekTo(positionMs.coerceAtLeast(0).toInt())
    }

    override fun audioTracks(): List<TrackOption> {
        val mp = player ?: return emptyList()
        if (!prepared) return emptyList()
        val selected = try {
            mp.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)
        } catch (e: Exception) {
            -1
        }
        return mp.trackInfo.mapIndexedNotNull { index, info ->
            if (info.trackType != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) return@mapIndexedNotNull null
            val language = info.language?.takeIf { it != "und" } ?: "Spur ${index + 1}"
            TrackOption(index.toString(), language, index == selected)
        }
    }

    override fun subtitleTracks(): List<TrackOption> = emptyList()

    override fun selectAudioTrack(id: String) {
        val index = id.toIntOrNull() ?: return
        try {
            player?.selectTrack(index)
        } catch (e: Exception) {
            // Manche Geräte erlauben keinen Spurwechsel während der Wiedergabe.
        }
    }

    override fun selectSubtitleTrack(id: String?) = Unit

    override fun chapters(): List<ChapterInfo> = emptyList()

    /** Der System-Player meldet weder Bytes noch Bilder – nur Status und Position (Start- und Hänger-Erkennung). */
    override fun health(): HealthSnapshot? {
        val mp = player ?: return null
        val playing = prepared && runCatching { mp.isPlaying }.getOrDefault(false)
        return HealthSnapshot(status = _state.value.status, isPlaying = playing, positionMs = positionMs)
    }

    override fun stats(): StreamStats? {
        val mp = player ?: return null
        if (!prepared) return null
        // Im Fehlerzustand wirft MediaPlayer bei getVideoWidth je nach Gerät eine IllegalStateException –
        // die Stream-Info fragt jede Sekunde, das darf den Player nicht abstürzen lassen.
        return try {
            StreamStats(width = mp.videoWidth.takeIf { it > 0 }, height = mp.videoHeight.takeIf { it > 0 })
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun releasePlayer() {
        player?.let {
            it.setOnPreparedListener(null)
            it.setOnErrorListener(null)
            it.setOnCompletionListener(null)
            it.setOnInfoListener(null)
            it.setOnVideoSizeChangedListener(null)
            // reset()/release() warten bei hängender Verbindung auf den Netzwerk-Thread -> nicht auf Main.
            Thread({
                runCatching {
                    it.reset()
                    it.release()
                }
            }, "native-player-release").start()
        }
        player = null
        prepared = false
    }

    override fun release() = releasePlayer()
}
