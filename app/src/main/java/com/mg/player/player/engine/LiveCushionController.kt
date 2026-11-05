package com.mg.player.player.engine

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Live-Puffer für Streams, die Media3 nicht als „live“ kennt (MPEG-TS der meisten IPTV-Zugänge, ab 3.51): Der Anbieter
 * liefert in Echtzeit, der Puffer vor der Wiedergabeposition wächst also nie von selbst. Spielt der Player 5 % langsamer
 * (Tonhöhe bleibt), wächst er um 3 s pro Minute – bis der Zielwert erreicht ist, dann wieder normales Tempo. Kurze
 * Aussetzer des Anbieters zehren nur diesen Vorrat auf, statt als Hänger sichtbar zu werden.
 * HLS-Live regelt Media3 selbst (Zielabstand über die Live-Konfiguration) – dort bleibt dieser Regler aus.
 */
@OptIn(UnstableApi::class)
internal class LiveCushionController(private val handler: Handler = Handler(Looper.getMainLooper())) {

    private var player: ExoPlayer? = null
    private var targetMs = 0L
    private var slow = false

    private val loop = object : Runnable {
        override fun run() {
            val p = player ?: return
            adjust(p)
            handler.postDelayed(this, CHECK_MS)
        }
    }

    fun start(p: ExoPlayer, targetMs: Long) {
        stop()
        if (targetMs <= 0) return
        player = p
        this.targetMs = targetMs
        handler.postDelayed(loop, CHECK_MS)
    }

    /** Normales Tempo (vor jedem neuen Stream über [start]/[stop]). */
    private fun reset(p: ExoPlayer) {
        if (slow) p.setPlaybackSpeed(1f)
        slow = false
    }

    fun stop() {
        handler.removeCallbacks(loop)
        player?.let(::reset)
        player = null
    }

    private fun adjust(p: ExoPlayer) {
        // HLS-Live: Media3 hält den Abstand selbst. Pause/Puffern: nichts tun.
        if (p.isCurrentMediaItemLive || !p.isPlaying) return
        val ahead = p.totalBufferedDuration
        val wantSlow = decide(ahead, targetMs, slow)
        if (wantSlow != slow) {
            slow = wantSlow
            p.setPlaybackSpeed(if (slow) SLOW_SPEED else 1f)
        }
    }

    companion object {
        const val SLOW_SPEED = 0.95f
        private const val CHECK_MS = 1_000L

        /** Unterhalb dieses Abstands zum Ziel wieder verlangsamen (verhindert ständiges Hin und Her). */
        private const val HYSTERESIS_MS = 1_000L

        /** Langsamer, solange der Vorrat unter dem Ziel liegt; erst ab dem Ziel wieder normal. */
        internal fun decide(aheadMs: Long, targetMs: Long, slowNow: Boolean): Boolean = when {
            targetMs <= 0 -> false
            slowNow -> aheadMs < targetMs
            else -> aheadMs < targetMs - HYSTERESIS_MS
        }
    }
}
