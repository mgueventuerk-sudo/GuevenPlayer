package com.mg.player.player.engine

import android.os.Handler
import android.os.Looper
import com.mg.player.domain.model.PlayerEngineType

/**
 * Übergabe der laufenden Live-Vorschau an den Vollbild-Player: Derselbe Sender läuft einfach weiter, statt neu
 * verbunden und gepuffert zu werden. Nimmt der Player die Engine nicht innerhalb kurzer Zeit ab (anderer Sender,
 * Abbruch, PIN-Abfrage abgelehnt), wird sie freigegeben – es spielt nie etwas unsichtbar weiter.
 *
 * Umgekehrt genauso: Zurück aus dem Vollbild zur Senderliste (nur wenn sie mit Vorschau wartet, [expectReturn]),
 * dann spielt der zuletzt gesehene Sender in der Vorschau weiter.
 */
class PreviewHandoff(private val clock: () -> Long = System::currentTimeMillis) {

    private class Pending(val channelId: String, val url: String, val type: PlayerEngineType, val engine: VideoPlayerEngine, val atMs: Long)

    private val main = Handler(Looper.getMainLooper())
    private var pending: Pending? = null

    /** Die Senderliste (TV, mit Vorschau) hat den Vollbild-Player geöffnet und nimmt den Sender danach zurück. */
    @Volatile
    var expectReturn: Boolean = false
    private val expire = Runnable { clear() }

    /** Die Vorschau-Engine von [channelId] (gestartet mit [url], Typ [type]) zur Übernahme bereitlegen. */
    @Synchronized
    fun offer(channelId: String, url: String, type: PlayerEngineType, engine: VideoPlayerEngine) {
        clear()
        pending = Pending(channelId, url, type, engine, clock())
        main.postDelayed(expire, TIMEOUT_MS)
    }

    /**
     * Die Engine, wenn sie genau diesen Stream ([url]) mit der gewünschten Engine-Art spielt – sonst null
     * (dann wird die bereitgelegte freigegeben und der Player startet wie gewohnt).
     */
    @Synchronized
    fun take(channelId: String, url: String, type: PlayerEngineType): VideoPlayerEngine? {
        val p = pending ?: return null
        pending = null
        main.removeCallbacks(expire)
        val fits = p.channelId == channelId && p.url == url && p.type == type && clock() - p.atMs <= TIMEOUT_MS
        if (!fits) p.engine.release()
        return p.engine.takeIf { fits }
    }

    @Synchronized
    fun clear() {
        main.removeCallbacks(expire)
        pending?.engine?.release()
        pending = null
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
