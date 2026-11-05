package com.mg.player.player.engine

import android.app.ActivityManager
import android.content.Context
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import com.mg.player.core.network.IPTV_USER_AGENT
import com.mg.player.core.util.StreamTarget
import com.mg.player.domain.model.ChapterInfo
import com.mg.player.domain.model.PlayerEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AndroidX Media3 (ExoPlayer): Standard für Live-TV und Filme, optimiert auf schnelles Umschalten.
 * Beim Zappen bleibt die Player-Instanz bestehen – nur die Adresse wird getauscht. Die Verbindungen laufen
 * über den gemeinsamen OkHttp-Pool (Keep-Alive zum Anbieter spart pro Senderwechsel den Verbindungsaufbau).
 */
@OptIn(UnstableApi::class)
class Media3Engine(context: Context, httpClient: OkHttpClient) : VideoPlayerEngine {

    override val type = PlayerEngineType.MEDIA3
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private val allowInsecureHttpForCurrentStream = AtomicBoolean(false)

    /** Teilt Verbindungs-Pool und Threads mit der App, aber mit Timeouts passend fürs Streaming. */
    private val streamClient = httpClient.newBuilder()
        .addNetworkInterceptor { chain ->
            if (chain.request().url.isHttps || allowInsecureHttpForCurrentStream.get()) {
                chain.proceed(chain.request())
            } else {
                throw IOException("HTTP ist für diesen IPTV-Zugang nicht freigegeben")
            }
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val bytes = Media3Support.ByteCounter()
    private val bufferLimit = appContext.getSystemService(ActivityManager::class.java).let { am ->
        // largeHeap ist im Manifest gesetzt -> largeMemoryClass ist der tatsächliche Heap.
        BufferLimit.forDevice(heapMb = am?.largeMemoryClass ?: 192, lowRamDevice = am?.isLowRamDevice == true)
    }

    private var player: ExoPlayer? = null
    private val cushion = LiveCushionController()
    private var playerView: PlayerView? = null
    private var muted = false
    private var httpFactory: OkHttpDataSource.Factory? = null
    private var builtFor: Media3Config? = null
    private var lastRequest: StreamRequest? = null
    private var hlsRetried = false
    private var droppedFrames = 0
    private var audioErrors = 0
    private var videoErrors = 0

    override val positionMs: Long get() = player?.currentPosition?.coerceAtLeast(0) ?: 0

    override val renderedFrames: Long?
        get() = player?.videoDecoderCounters?.let { counters ->
            counters.ensureUpdated()
            counters.renderedOutputBufferCount.toLong()
        }
    override val durationMs: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publishState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }
            // TS erwartet, aber HLS geliefert (Adresse ohne .m3u8): einmal als HLS versuchen.
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && retryAsHls()) return
            val (kind, http) = Media3Support.classify(error)
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    isPlaying = false,
                    errorMessage = http?.let { code -> "HTTP $code" } ?: error.errorCodeName,
                    formatUnsupported = kind == ErrorKind.FORMAT,
                    errorKind = kind,
                    httpCode = http,
                    errorCause = Media3Support.cause(error),
                    retryAfterSec = Media3Support.retryAfterSec(error),
                )
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val fps = Media3Support.known(player?.videoFormat?.frameRate)
            // Ton- oder Videospur vorhanden, aber keine davon abspielbar (typisch: DTS-Ton) -> Fallback-Engine.
            val unsupported = Media3Support.unplayable(tracks, C.TRACK_TYPE_AUDIO) || Media3Support.unplayable(tracks, C.TRACK_TYPE_VIDEO)
            _state.update {
                it.copy(
                    tracksVersion = it.tracksVersion + 1,
                    videoFrameRate = fps ?: it.videoFrameRate,
                    formatUnsupported = it.formatUnsupported || unsupported,
                )
            }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@Media3Engine.droppedFrames += droppedFrames
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            audioErrors++
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            audioErrors++
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            videoErrors++
        }
    }

    private fun publishState() {
        val p = player ?: return
        val status = when (p.playbackState) {
            Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            Player.STATE_READY -> PlaybackStatus.READY
            Player.STATE_ENDED -> PlaybackStatus.ENDED
            else -> if (p.playerError != null) PlaybackStatus.ERROR else PlaybackStatus.IDLE
        }
        _state.update {
            it.copy(
                status = status,
                isPlaying = p.isPlaying,
                errorMessage = if (status == PlaybackStatus.ERROR) it.errorMessage else null,
                errorKind = if (status == PlaybackStatus.ERROR) it.errorKind else ErrorKind.NONE,
            )
        }
    }

    private fun buildPlayer(config: Media3Config): ExoPlayer {
        val factory = OkHttpDataSource.Factory(streamClient)
            .setUserAgent(IPTV_USER_AGENT)
            .setTransferListener(bytes)
        httpFactory = factory
        return Media3Build.player(appContext, config, bufferLimit, factory).also {
            it.addListener(listener)
            it.addAnalyticsListener(analytics)
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        player?.volume = if (muted) 0f else 1f
    }

    override fun attachSurface(container: FrameLayout) {
        val view = Media3Build.view(container.context, player)
        container.removeAllViews()
        container.addView(view)
        playerView = view
    }

    override fun detachSurface(container: FrameLayout) {
        val view = playerView?.takeIf { it.parent === container } ?: return // schon an die Vollbild-Fläche übergeben
        view.player = null
        container.removeView(view)
        playerView = null
    }

    override fun prepare(request: StreamRequest) {
        // Vor dem Widerruf laufende HLS-Abrufe stoppen, damit kein HTTP-Untersegment der alten Quelle nachläuft.
        if (lastRequest?.allowInsecureHttp == true && !request.allowInsecureHttp) player?.clearMediaItems()
        allowInsecureHttpForCurrentStream.set(request.allowInsecureHttp)
        val config = Media3Config(request.bufferProfile, request.hwDecoding, request.tunneling && request.isLive, request.audioPassthrough)
        if (player == null || builtFor != config) {
            // Erste Wiedergabe oder geänderte Einstellungen (z. B. Catch-up nach Live ohne Tunneling).
            releasePlayer()
            player = buildPlayer(config).also {
                playerView?.player = it
                if (muted) it.volume = 0f
            }
            builtFor = config
        }
        lastRequest = request
        hlsRetried = false
        val target = StreamTarget.parse(request.url)
        httpFactory?.setUserAgent(target.userAgent ?: IPTV_USER_AGENT)
        httpFactory?.setDefaultRequestProperties(target.extraHeaders)
        start(target.url, Media3Support.mimeTypeFor(target.url), request.startPositionMs)
    }

    private fun start(url: String, mimeType: String?, positionMs: Long) {
        val p = player ?: return
        bytes.bytes.set(0)
        droppedFrames = 0
        audioErrors = 0
        videoErrors = 0
        val request = lastRequest
        val item = MediaItem.Builder().setUri(url).apply {
            mimeType?.let(::setMimeType)
            // Live: näher am Live-Rand starten (schnellerer Senderwechsel), leichte Tempo-Anpassung hält den Abstand.
            // Der Live-Puffer gilt als Mindestabstand (HLS regelt ihn selbst über das Tempo).
            if (request?.isLive == true) {
                liveTargetOffsetMs(request.bufferProfile)?.let { maxOf(it, request.liveCushionMs) }?.let { offset ->
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(offset).setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build(),
                    )
                }
            }
        }.build()
        _state.update {
            it.copy(status = PlaybackStatus.BUFFERING, errorMessage = null, formatUnsupported = false, errorKind = ErrorKind.NONE, httpCode = null)
        }
        // Alte Verbindung zuerst schließen, dann die neue öffnen: Zugänge mit nur einer Verbindung lehnen den neuen Sender
        // sonst ab, solange der Anbieter den alten Stream noch zählt (Umschalten, Vorschau → anderer Sender).
        p.stop()
        cushion.reset(p)
        p.setMediaItem(item, positionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = request?.autoPlay != false
        cushion.start(p, request?.takeIf { it.isLive && builtFor?.tunneling != true }?.liveCushionMs ?: 0)
    }

    private fun retryAsHls(): Boolean {
        val request = lastRequest ?: return false
        val url = StreamTarget.parse(request.url).url
        if (hlsRetried || Media3Support.mimeTypeFor(url) == MimeTypes.APPLICATION_M3U8) return false
        hlsRetried = true
        start(url, MimeTypes.APPLICATION_M3U8, request.startPositionMs)
        return true
    }

    override fun play() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun audioTracks(): List<TrackOption> = player?.let { Media3Support.tracksOf(it, C.TRACK_TYPE_AUDIO) }.orEmpty()

    override fun subtitleTracks(): List<TrackOption> = player?.let { Media3Support.tracksOf(it, C.TRACK_TYPE_TEXT) }.orEmpty()

    override fun selectAudioTrack(id: String) {
        player?.let { Media3Support.selectTrack(it, C.TRACK_TYPE_AUDIO, id) }
    }

    override fun selectSubtitleTrack(id: String?) {
        val p = player ?: return
        if (id == null) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            Media3Support.selectTrack(p, C.TRACK_TYPE_TEXT, id)
        }
    }

    override fun chapters(): List<ChapterInfo> = emptyList()

    /**
     * Zähler für den Wiedergabe-Wächter. Bei Tunneling zählt der Decoder keine gezeigten Bilder und beim
     * Ton-Durchreichen keine Tonblöcke zuverlässig – dann bleibt der Wert null und die Erkennung aus.
     */
    override fun health(): HealthSnapshot? {
        val p = player ?: return null
        val config = builtFor
        val tracks = p.currentTracks
        val video = p.videoDecoderCounters?.also { it.ensureUpdated() }
        val audio = p.audioDecoderCounters?.also { it.ensureUpdated() }
        val tracksKnown = !tracks.isEmpty
        return HealthSnapshot(
            status = _state.value.status,
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            bytesLoaded = bytes.bytes.get(),
            renderedFrames = if (config?.tunneling == true) null else video?.renderedOutputBufferCount?.toLong() ?: 0L,
            audioUnits = if (config?.passthrough == true) null else audio?.renderedOutputBufferCount?.toLong() ?: 0L,
            hasVideoTrack = if (tracksKnown) tracks.containsType(C.TRACK_TYPE_VIDEO) else null,
            hasAudioTrack = if (tracksKnown) tracks.containsType(C.TRACK_TYPE_AUDIO) else null,
            audioErrors = audioErrors,
            videoErrors = videoErrors,
            droppedFrames = droppedFrames.toLong(),
            liveOffsetMs = if (p.isCurrentMediaItemLive) p.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0 } else null,
        )
    }

    override fun seekToLiveEdge(): Boolean {
        val p = player ?: return false
        if (!p.isCurrentMediaItemLive) return false
        p.seekToDefaultPosition()
        p.playWhenReady = true
        return true
    }

    override fun refreshTrack(video: Boolean): Boolean {
        val p = player ?: return false
        val type = if (video) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
        val params = p.trackSelectionParameters
        // Abwählen und wieder wählen: Der Renderer wird freigegeben und mit neuem Decoder gestartet.
        p.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(type, true).build()
        p.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(type, false).build()
        return true
    }

    override fun stats(): StreamStats? = player?.let { Media3Build.stats(it, bytes.bytes.get(), droppedFrames) }

    private fun releasePlayer() {
        cushion.stop()
        player?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        player = null
        httpFactory = null
        builtFor = null
        playerView?.player = null
    }

    /** Gibt Decoder, Netzwerkverbindung und Puffer vollständig frei. */
    override fun release() {
        releasePlayer()
        _state.update { it.copy(status = PlaybackStatus.IDLE, isPlaying = false) }
    }
}
