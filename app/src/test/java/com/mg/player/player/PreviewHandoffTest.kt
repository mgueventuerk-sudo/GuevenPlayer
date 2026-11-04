package com.mg.player.player

import android.os.Looper
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.domain.model.ChapterInfo
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.PreviewHandoff
import com.mg.player.player.engine.StreamRequest
import com.mg.player.player.engine.TrackOption
import com.mg.player.player.engine.VideoPlayerEngine
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Vorschau → Vollbild: Derselbe Stream wird übernommen, alles andere sauber freigegeben (nichts spielt unsichtbar weiter). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PreviewHandoffTest {

    private class FakeEngine : VideoPlayerEngine {
        var released = 0
        var prepared = 0
        override val type = PlayerEngineType.MEDIA3
        override val state: StateFlow<EngineState> = MutableStateFlow(EngineState())
        override val positionMs = 0L
        override val durationMs = 0L
        override fun attachSurface(container: FrameLayout) = Unit
        override fun detachSurface(container: FrameLayout) = Unit
        override fun prepare(request: StreamRequest) {
            prepared++
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun audioTracks(): List<TrackOption> = emptyList()
        override fun subtitleTracks(): List<TrackOption> = emptyList()
        override fun selectAudioTrack(id: String) = Unit
        override fun selectSubtitleTrack(id: String?) = Unit
        override fun chapters(): List<ChapterInfo> = emptyList()
        override fun release() {
            released++
        }
    }

    @Test
    fun sameStreamIsTakenOverWithoutReload() {
        val handoff = PreviewHandoff()
        val engine = FakeEngine()
        handoff.offer("1:L:101", "http://s/live/1.ts", PlayerEngineType.MEDIA3, engine)
        assertSame(engine, handoff.take("1:L:101", "http://s/live/1.ts", PlayerEngineType.MEDIA3))
        assertEquals(0, engine.released)
        assertEquals(0, engine.prepared)
        // Nur einmal abzuholen.
        assertNull(handoff.take("1:L:101", "http://s/live/1.ts", PlayerEngineType.MEDIA3))
    }

    @Test
    fun otherStreamOrEngineIsReleased() {
        val handoff = PreviewHandoff()
        val a = FakeEngine()
        handoff.offer("1:L:101", "http://s/live/1.ts", PlayerEngineType.MEDIA3, a)
        assertNull(handoff.take("1:L:101", "http://s/live/2.ts", PlayerEngineType.MEDIA3))
        assertEquals(1, a.released)
        val b = FakeEngine()
        handoff.offer("1:L:101", "http://s/live/1.ts", PlayerEngineType.MEDIA3, b)
        assertNull(handoff.take("1:L:101", "http://s/live/1.ts", PlayerEngineType.LIBVLC))
        assertEquals(1, b.released)
    }

    @Test
    fun unclaimedEngineIsReleasedAfterTimeout() {
        val handoff = PreviewHandoff()
        val engine = FakeEngine()
        handoff.offer("1:L:101", "http://s/live/1.ts", PlayerEngineType.MEDIA3, engine)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(1, engine.released)
        assertNull(handoff.take("1:L:101", "http://s/live/1.ts", PlayerEngineType.MEDIA3))
    }
}
