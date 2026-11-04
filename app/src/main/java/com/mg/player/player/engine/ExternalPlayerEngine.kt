package com.mg.player.player.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import androidx.core.net.toUri
import com.mg.player.core.util.StreamTarget
import com.mg.player.domain.model.ChapterInfo
import com.mg.player.domain.model.PlayerEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Übergibt den Stream per Intent an einen installierten Player (VLC, MX Player, Just Player, Kodi …). */
class ExternalPlayerEngine(context: Context) : VideoPlayerEngine {

    override val type = PlayerEngineType.EXTERNAL
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val positionMs: Long = 0
    override val durationMs: Long = 0

    override fun attachSurface(container: FrameLayout) = Unit
    override fun detachSurface(container: FrameLayout) = Unit

    override fun prepare(request: StreamRequest) {
        val target = StreamTarget.parse(request.url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target.url.toUri(), "video/*")
            // MX Player / Just Player: Header als ["Name", "Wert", …]
            if (target.headers.isNotEmpty()) putExtra("headers", target.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            putExtra("title", request.title)
            putExtra("position", request.startPositionMs.toInt())
            putExtra("from_start", request.startPositionMs <= 0)
            putExtra("return_result", false)
        }
        val chooser = Intent.createChooser(intent, "Externen Player wählen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        _state.value = try {
            appContext.startActivity(chooser)
            EngineState(status = PlaybackStatus.HANDED_OFF)
        } catch (e: ActivityNotFoundException) {
            EngineState(status = PlaybackStatus.ERROR, errorMessage = "Kein externer Player installiert")
        }
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun audioTracks(): List<TrackOption> = emptyList()
    override fun subtitleTracks(): List<TrackOption> = emptyList()
    override fun selectAudioTrack(id: String) = Unit
    override fun selectSubtitleTrack(id: String?) = Unit
    override fun chapters(): List<ChapterInfo> = emptyList()
    override fun release() = Unit
}
