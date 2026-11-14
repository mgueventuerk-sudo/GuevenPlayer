package com.mg.player.player.engine

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.net.toUri
import com.mg.player.core.log.AppLog
import com.mg.player.core.network.IPTV_USER_AGENT
import com.mg.player.core.util.StreamTarget
import com.mg.player.domain.model.ChapterInfo
import com.mg.player.domain.model.HwDecoding
import com.mg.player.domain.model.PlayerEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * LibVLC: eigene FFmpeg-basierte Engine für maximale Format-Kompatibilität
 * (MKV, AVI, HEVC, AC3, DTS, Untertitel) und Kapitel-Auslesung.
 */
class LibVlcEngine(context: Context) : VideoPlayerEngine {

    override val type = PlayerEngineType.LIBVLC
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null

    /**
     * setMedia/stop/release blockieren bei hängender Verbindung mehrere Sekunden – auf dem Main-Thread
     * wäre das ein ANR. Ein eigener Thread je Engine arbeitet sie der Reihe nach ab (Events kommen weiter auf Main);
     * hängt das Beenden eines toten Streams, blockiert das keine andere Engine.
     */
    private val worker = VlcWorker()
    private var videoLayout: VLCVideoLayout? = null
    private var viewsAttached = false
    private var muted = false

    override val positionMs: Long get() = player?.time?.coerceAtLeast(0) ?: 0
    override val durationMs: Long get() = player?.length?.coerceAtLeast(0) ?: 0

    private fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        // Eine LibVLC-Instanz für die ganze App: das Laden der Module kostet beim Start sonst jedes Mal Zeit.
        val vlc = SharedLibVlc.acquire(appContext)
        val mp = runCatching { MediaPlayer(vlc) }.getOrElse {
            SharedLibVlc.releaseUse()
            throw it
        }
        mp.setEventListener { event -> onEvent(event) }
        libVlc = vlc
        player = mp
        attachViewsIfPossible()
        return mp
    }

    private fun onEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> _state.update { it.copy(status = PlaybackStatus.BUFFERING) }

            MediaPlayer.Event.Buffering -> {
                val done = event.buffering >= 100f
                _state.update {
                    if (it.status == PlaybackStatus.ENDED) {
                        it
                    } else {
                        it.copy(status = if (done) PlaybackStatus.READY else PlaybackStatus.BUFFERING)
                    }
                }
            }

            MediaPlayer.Event.Playing -> {
                // LibVLC übernimmt die Lautstärke erst mit geöffneter Tonausgabe – daher hier (erneut) setzen.
                if (muted) player?.setVolume(0)
                _state.update {
                    it.copy(status = PlaybackStatus.READY, isPlaying = true, errorMessage = null, videoFrameRate = currentFrameRate() ?: it.videoFrameRate)
                }
            }

            MediaPlayer.Event.Paused -> _state.update { it.copy(isPlaying = false) }

            MediaPlayer.Event.EndReached -> _state.update { it.copy(status = PlaybackStatus.ENDED, isPlaying = false) }

            MediaPlayer.Event.EncounteredError -> _state.update {
                it.copy(status = PlaybackStatus.ERROR, isPlaying = false, errorMessage = "Stream konnte nicht geöffnet werden", errorKind = ErrorKind.NETWORK)
            }

            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> _state.update {
                it.copy(tracksVersion = it.tracksVersion + 1, videoFrameRate = currentFrameRate() ?: it.videoFrameRate)
            }

            else -> Unit
        }
    }

    private fun currentFrameRate(): Float? {
        val track = player?.currentVideoTrack ?: return null
        return if (track.frameRateDen > 0 && track.frameRateNum > 0) track.frameRateNum.toFloat() / track.frameRateDen else null
    }

    private fun attachViewsIfPossible() {
        val mp = player ?: return
        val layout = videoLayout ?: return
        if (!viewsAttached) {
            mp.attachViews(layout, null, true, false)
            viewsAttached = true
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        player?.takeUnless { it.isReleased }?.setVolume(if (muted) 0 else 100)
    }

    override fun attachSurface(container: FrameLayout) {
        val layout = VLCVideoLayout(container.context).apply {
            isFocusable = false
            keepScreenOn = true
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.removeAllViews()
        container.addView(layout)
        videoLayout = layout
        viewsAttached = false
        attachViewsIfPossible()
    }

    override fun detachSurface(container: FrameLayout) {
        // Schon an eine andere Fläche übergeben (Vorschau → Vollbild): die neue nicht wieder abhängen.
        val layout = videoLayout?.takeIf { it.parent === container } ?: return
        if (viewsAttached) player?.detachViews()
        viewsAttached = false
        container.removeView(layout)
        videoLayout = null
    }

    override fun prepare(request: StreamRequest) {
        val mp = ensurePlayer()
        val vlc = libVlc ?: return
        val caching = request.bufferProfile.toConfig().vlcCachingMs
        val target = StreamTarget.parse(request.url)
        _state.update { it.copy(status = PlaybackStatus.BUFFERING, errorMessage = null, errorKind = ErrorKind.NONE) }
        worker.run {
            val media = Media(vlc, target.url.toUri()).apply {
                when (request.hwDecoding) {
                    HwDecoding.AUTO -> setHWDecoderEnabled(true, false)
                    HwDecoding.ON -> setHWDecoderEnabled(true, true)
                    HwDecoding.OFF -> setHWDecoderEnabled(false, false)
                }
                addOption(":network-caching=$caching")
                addOption(":live-caching=$caching")
                addOption(":http-user-agent=${target.userAgent ?: IPTV_USER_AGENT}")
                // Bevorzugte Tonsprachen wie bei ExoPlayer – sonst nahm LibVLC die Standardspur der Datei.
                addOption(":audio-language=${AudioPreference.trackCodes().joinToString(",")}")
                target.headers[StreamTarget.REFERER]?.let { addOption(":http-referrer=$it") }
                if (request.startPositionMs > 0) addOption(":start-time=${request.startPositionMs / 1000.0}")
            }
            if (!mp.isReleased) {
                mp.setMedia(media)
                mp.play()
            }
            media.release()
        }
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.setTime(positionMs.coerceAtLeast(0))
    }

    override fun audioTracks(): List<TrackOption> {
        val mp = player ?: return emptyList()
        val current = mp.audioTrack
        return mp.audioTracks.orEmpty()
            .filter { it.id >= 0 }
            .map { TrackOption(it.id.toString(), it.name ?: "Spur ${it.id}", it.id == current) }
    }

    override fun subtitleTracks(): List<TrackOption> {
        val mp = player ?: return emptyList()
        val current = mp.spuTrack
        return mp.spuTracks.orEmpty()
            .filter { it.id >= 0 }
            .map { TrackOption(it.id.toString(), it.name ?: "Untertitel ${it.id}", it.id == current) }
    }

    override fun selectAudioTrack(id: String) {
        id.toIntOrNull()?.let { player?.setAudioTrack(it) }
    }

    override fun selectSubtitleTrack(id: String?) {
        player?.setSpuTrack(id?.toIntOrNull() ?: -1)
    }

    /** Stufe 1 der Intro-Erkennung: Kapitel direkt aus dem Stream. */
    override fun chapters(): List<ChapterInfo> {
        val mp = player ?: return emptyList()
        val chapters = mp.getChapters(-1) ?: return emptyList()
        return chapters.map { ChapterInfo(it.name.orEmpty(), it.timeOffset, it.timeOffset + it.duration) }
    }

    /**
     * Zähler aus der LibVLC-Statistik: angezeigte Bilder, ausgegebene Tonblöcke, gelesene Bytes. Die Statistik
     * aktualisiert LibVLC etwa einmal pro Sekunde – für Schwellen ab mehreren Sekunden reicht das.
     */
    override fun health(): HealthSnapshot? {
        val mp = player ?: return null
        if (mp.isReleased) return null
        val media = mp.media
        val stats = runCatching { media?.stats }.getOrNull()
        media?.release()
        val videoTracks = runCatching { mp.videoTracksCount }.getOrDefault(-1)
        val audioTracks = runCatching { mp.audioTracksCount }.getOrDefault(-1)
        return HealthSnapshot(
            status = _state.value.status,
            isPlaying = mp.isPlaying,
            positionMs = mp.time.coerceAtLeast(0),
            bytesLoaded = stats?.readBytes?.toLong()?.and(0xFFFFFFFFL),
            renderedFrames = stats?.displayedPictures?.toLong(),
            audioUnits = stats?.playedAbuffers?.toLong(),
            // Vor dem Einlesen der Spuren meldet LibVLC 0 – erst nach dem Start als „keine Spur“ werten.
            hasVideoTrack = videoTracks.takeIf { it >= 0 && _state.value.status == PlaybackStatus.READY }?.let { it > 0 },
            hasAudioTrack = audioTracks.takeIf { it >= 0 && _state.value.status == PlaybackStatus.READY }?.let { it > 0 },
            droppedFrames = stats?.lostPictures?.toLong(),
        )
    }

    override fun stats(): StreamStats? {
        val mp = player ?: return null
        val video = mp.currentVideoTrack
        val media = mp.media
        val stats = runCatching { media?.stats }.getOrNull()
        val audio = runCatching {
            media?.let { m -> (0 until m.trackCount).map { m.getTrack(it) }.filterIsInstance<IMedia.AudioTrack>().firstOrNull() }
        }.getOrNull()
        media?.release()
        return StreamStats(
            width = video?.width?.takeIf { it > 0 },
            height = video?.height?.takeIf { it > 0 },
            frameRate = video?.let { if (it.frameRateDen > 0 && it.frameRateNum > 0) it.frameRateNum.toFloat() / it.frameRateDen else null },
            videoCodec = video?.codec,
            videoBitrate = video?.bitrate?.takeIf { it > 0 },
            audioCodec = audio?.codec,
            audioChannels = audio?.channels?.takeIf { it > 0 },
            // readBytes ist ein Int (läuft nach 2 GB über) – für die Rate pro Sekunde reicht das.
            bytesLoaded = stats?.readBytes?.toLong()?.and(0xFFFFFFFFL) ?: 0,
            droppedFrames = stats?.lostPictures,
        )
    }

    override fun release() {
        player?.let { mp ->
            mp.setEventListener(null)
            if (viewsAttached) mp.detachViews()
            // Die LibVLC-Referenz erst nach dem nativen MediaPlayer freigeben. Sonst kann onTrimMemory()
            // die gemeinsame Instanz zerstören, während stop()/release() noch auf dem Worker laufen.
            worker.finish {
                runCatching { mp.stop() }
                runCatching { mp.release() }
                    .onSuccess { SharedLibVlc.releaseUse() }
                    .onFailure { AppLog.log("Player", "LibVLC-Freigabe fehlgeschlagen: ${it.javaClass.simpleName}") }
            }
        }
        viewsAttached = false
        player = null
        libVlc = null
    }
}
