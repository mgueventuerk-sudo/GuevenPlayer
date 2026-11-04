package com.mg.player.ui.player

import com.mg.player.core.log.AppLog
import com.mg.player.core.util.StreamMemory
import com.mg.player.data.combined.Combined
import com.mg.player.player.engine.ErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Automatische Failover-Kaskade für zusammengefasste Live-Sender: Lädt der aktuelle Stream nicht
 * (Fehler oder nach 3 Sekunden noch kein Bild) oder puffert er unterwegs länger als 3 Sekunden, wird nahtlos die
 * nächstbeste Variante gestartet (z. B. 4K -> FHD -> HD -> SD). Die Variante, die beim Start funktioniert, wird als
 * neuer Primär-Stream dieses Senders gemerkt (auch über einen Neustart) – beim Zurückzappen keine Wartezeit.
 */
class LiveFailover(
    private val scope: CoroutineScope,
    private val memory: StreamMemory,
    private val switchTo: suspend (Playable, Int) -> Unit,
    private val notify: (String) -> Unit,
    /** Ohne Netz liegt es nicht an der Quelle – dann nicht umschalten (sonst landet man dauerhaft auf SD). */
    private val isOnline: () -> Boolean = { true },
) {
    private var current: Playable? = null
    private var watchdog: Job? = null
    private var stallWatch: Job? = null
    private var switching = false

    /** Lief der aktuelle Stream schon einmal? Erst danach zählt Puffern als Hänger (vorher wacht [watchdog]). */
    private var playedOnce = false

    /**
     * Ob die Variante, die nach einem Wechsel läuft, gemerkt wird. Nicht, wenn der Wechsel nur wegen
     * Verbindungsfehlern kam (z. B. Internet am Router weg, WLAN aber verbunden): Sonst bliebe man nach der
     * Störung bis zum App-Neustart auf der schlechteren Qualität.
     */
    private var rememberWorking = true

    /** Gerade wird auf eine andere Variante umgeschaltet. */
    val busy: Boolean get() = switching

    /** Nach jedem Start eines Live-Streams aufrufen. */
    fun onPrepared(playable: Playable) {
        // Neuer Sender (nicht nur andere Variante desselben): wieder normal merken.
        if (current?.id != playable.id) rememberWorking = true
        current = playable
        switching = false
        playedOnce = false
        watchdog?.cancel()
        stallWatch?.cancel()
        if (!canFailOver(playable)) return
        watchdog = scope.launch {
            delay(if (playable.isLive) START_TIMEOUT_MS else VOD_START_TIMEOUT_MS)
            if (isOnline()) failOver()
        }
    }

    /** Bild läuft: Watchdog aus, funktionierende Variante für diese Sitzung merken. */
    fun onPlaying() {
        watchdog?.cancel()
        stallWatch?.cancel()
        if (playedOnce) return
        playedOnce = true
        val playable = current?.takeIf { rememberWorking && it.sourceCount > 1 } ?: return
        rememberSource(playable)
    }

    /** „Alle Zugänge“ (Film/Folge): bewährte Quelle je Inhalt; Live: je Sender-Gruppe wie bisher. */
    private fun rememberSource(playable: Playable) {
        val source = playable.activeVodSource
        val channel = playable.channel
        val variant = playable.activeVariant
        when {
            source != null -> memory.remember(Combined.ID, playable.id, source.itemId)
            channel != null && variant != null -> memory.remember(channel.playlistId, channel.groupKey, variant.id)
        }
    }

    /**
     * Bei einem Fehler des Streams selbst (HTTP-Fehler, Format, keine abspielbare Spur).
     * [remember] = false: Wechsel wegen reiner Verbindungsfehler – die neue Variante nicht dauerhaft bevorzugen.
     * true = es wird auf eine andere Variante gewechselt (kein Neuversuch nötig).
     */
    fun onStreamProblem(remember: Boolean = true): Boolean {
        val switched = failOver()
        if (switched && !remember) rememberWorking = false
        return switched
    }

    /**
     * Der Stream puffert, nachdem er schon lief: Dauert das länger als [STALL_SWITCH_MS], zur nächsten Variante.
     * Nicht merken – ein einzelner Hänger kann am Netz liegen; beim nächsten Zappen gilt wieder die erste Wahl.
     */
    fun onBuffering() {
        val playable = current ?: return
        if (!playedOnce || stallWatch?.isActive == true || !canFailOver(playable)) return
        stallWatch = scope.launch {
            delay(if (playable.isLive) STALL_SWITCH_MS else VOD_STALL_SWITCH_MS)
            if (isOnline() && failOver()) rememberWorking = false
        }
    }

    /** Pause o. Ä.: Puffern zählt nicht mehr als Hänger. */
    fun onIdle() {
        stallWatch?.cancel()
    }

    /** Wiedergabe beendet oder anderer Inhalt: nichts mehr überwachen. */
    fun stop() {
        watchdog?.cancel()
        stallWatch?.cancel()
        current = null
    }

    private fun failOver(): Boolean {
        val playable = current ?: return false
        if (switching || !canFailOver(playable)) return false
        switching = true
        watchdog?.cancel()
        stallWatch?.cancel()
        val nextIndex = playable.nextSourceIndex() ?: return false.also { switching = false }
        notify("Primär-Stream nicht erreichbar – wechsle automatisch zu ${playable.sourceName(nextIndex)} …")
        scope.launch {
            try {
                switchTo(playable, nextIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Adresse der Variante nicht auflösbar o. Ä.: kein Absturz, normale Fehlerbehandlung übernimmt.
                AppLog.error("Player", "Quellenwechsel fehlgeschlagen", e)
            } finally {
                // Kam kein Start der neuen Variante zustande, ist der Wechsel vorbei (sonst bliebe "busy" hängen).
                if (current === playable) switching = false
            }
        }
        return true
    }

    /** Live mit Varianten oder Film/Folge aus „Alle Zugänge“ mit weiterer Quelle. */
    private fun canFailOver(playable: Playable): Boolean = playable.canSwitchSource

    private companion object {
        /** So lange darf ein Sender bis zum ersten Bild laden, bevor die nächste Variante startet. */
        const val START_TIMEOUT_MS = 3_000L

        /** So lange darf ein laufender Sender puffern, bevor die nächste Variante startet. */
        const val STALL_SWITCH_MS = 3_000L

        /** Film/Folge: Start und Hänger dürfen länger dauern (Plan „Alle Zugänge“: 8 s). */
        const val VOD_START_TIMEOUT_MS = 8_000L
        const val VOD_STALL_SWITCH_MS = 8_000L
    }
}

/**
 * Wann ein Fehler an der Quelle liegt (dann nächste Variante) und wann am Netz (dann dieselbe Quelle
 * erneut versuchen). Ein kurzer WLAN-Aussetzer darf die Qualität nicht dauerhaft herunterschalten.
 */
object FailoverPolicy {
    /** Reine Verbindungsfehler bei funktionierendem Netz: erst nach so vielen Neuversuchen die Quelle wechseln. */
    const val NETWORK_RETRIES_BEFORE_SWITCH = 2

    fun isStreamProblem(kind: ErrorKind, httpCode: Int?, online: Boolean, retriesSoFar: Int): Boolean = when {
        !online -> false
        kind == ErrorKind.NETWORK && httpCode == null -> retriesSoFar >= NETWORK_RETRIES_BEFORE_SWITCH
        else -> true
    }
}

/**
 * Auto Frame Rate mit Verzögerung beim Live-TV: Der Bildwiederholraten-Wechsel des Fernsehers
 * (HDMI-Handshake, oft sekundenlang schwarz) erfolgt erst, wenn man 3 Sekunden auf einem Sender bleibt.
 * Filme und Serien schalten sofort um (z. B. auf 24 Hz).
 */
class FrameRateSwitcher(private val scope: CoroutineScope, private val apply: suspend (Float) -> Unit) {
    private var lastApplied: Float? = null
    private var pending: Job? = null

    fun onFrameRate(fps: Float, isLive: Boolean) {
        if (fps == lastApplied) return
        pending?.cancel()
        pending = scope.launch {
            if (isLive) delay(LIVE_DEBOUNCE_MS)
            lastApplied = fps
            apply(fps)
        }
    }

    /** Beim Senderwechsel: eine noch ausstehende Umschaltung verwerfen. */
    fun cancelPending() {
        pending?.cancel()
    }

    fun reset() {
        pending?.cancel()
        lastApplied = null
    }

    private companion object {
        const val LIVE_DEBOUNCE_MS = 3_000L
    }
}
