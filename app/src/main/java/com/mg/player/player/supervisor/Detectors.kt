package com.mg.player.player.supervisor

import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus

/** Was ein Detektor über den laufenden Start wissen muss. */
data class DetectContext(val session: SessionInfo, val preparedAtMs: Long, val config: SupervisorConfig) {
    /** Live mit Ersatzquelle: Ein Wechsel ist billig, also früher reagieren. */
    val quickSwitch: Boolean get() = session.isLive && session.canSwitchVariant

    fun limit(withAlternative: Long, live: Long, vod: Long): Long = when {
        quickSwitch -> withAlternative
        session.isLive -> live
        else -> vod
    }
}

/**
 * Erkennt genau ein Symptom aus der Folge von [HealthSnapshot]s. Rein (keine Android-Abhängigkeit, keine Uhr),
 * die Zeit kommt als Parameter – dadurch mit Signal-Skripten testbar. Ein Detektor meldet ein Symptom nur einmal
 * je Episode und schlägt erst wieder an, wenn sich die Lage zwischendurch normalisiert hat.
 */
interface Detector {
    fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding?

    /** Neuer Start (prepare): alles vergessen. */
    fun reset()
}

/** So kurz zurück gilt ein Zähler noch als „läuft“. */
private const val RECENT_MS = 2_000L
private const val BYTES_PER_KB = 1_024L

private val HealthSnapshot.playing: Boolean get() = status == PlaybackStatus.READY && isPlaying

/** S1/S2: Der Start kommt nicht zustande – gar keine Daten oder Daten, aber keine Wiedergabe. */
class StartDetector : Detector {
    private var done = false
    private var firstDataAt = -1L

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        if (s.status == PlaybackStatus.READY || s.status == PlaybackStatus.ENDED) done = true
        if (done || s.status == PlaybackStatus.ERROR) return null
        val bytes = s.bytesLoaded ?: 0L
        if (bytes > 0 && firstDataAt < 0) firstDataAt = now
        val finding = if (bytes > 0) noPicture(bytes, now, ctx) else noData(now, ctx)
        if (finding != null) done = true
        return finding
    }

    private fun noData(now: Long, ctx: DetectContext): Finding? {
        val waited = now - ctx.preparedAtMs
        val cfg = ctx.config
        if (waited < ctx.limit(cfg.startNoDataWithAlternative, cfg.startNoDataLive, cfg.startNoDataVod)) return null
        return Finding(Symptom.START_NO_DATA, "seit $waited ms keine Daten")
    }

    private fun noPicture(bytes: Long, now: Long, ctx: DetectContext): Finding? {
        val waited = now - firstDataAt
        val cfg = ctx.config
        if (waited < if (ctx.quickSwitch) cfg.startNoPictureWithAlternative else cfg.startNoPicture) return null
        return Finding(Symptom.START_NO_PICTURE, "${bytes / BYTES_PER_KB} KB geladen, seit $waited ms nicht startbereit")
    }

    override fun reset() {
        done = false
        firstDataAt = -1L
    }
}

/**
 * S3: Hänger nach dem Start – die Engine puffert zu lange, oder sie meldet „spielt“, aber die Position steht
 * (LibVLC und der System-Player melden kein Puffern zuverlässig und keine Bildzahl).
 */
class StallDetector : Detector {
    private var started = false
    private var bufferingSince = -1L
    private var bufferedBytes = -1L
    private var bytesStuckSince = 0L
    private var lastPosition = -1L
    private var positionStuckSince = -1L
    private var fired = false

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        if (s.playing) started = true
        if (!started) return null
        val cfg = ctx.config
        val max = ctx.limit(cfg.stallWithAlternative, cfg.stallLive, cfg.stallVod)
        return when {
            // „hart“: Auch wenn Daten nachkommen, ist Dauerpuffern irgendwann ein Hänger.
            s.status == PlaybackStatus.BUFFERING -> {
                // Ohne Datenmenge ist „kein Nachschub“ nicht feststellbar: nicht schon nach 2 s wechseln.
                val limit = if (s.bytesLoaded == null && ctx.quickSwitch) cfg.stallWithAlternativeNoByteCount else max
                buffering(s, now, limit, if (ctx.session.isLive) cfg.stallLive else cfg.stallVod)
            }

            s.playing -> positionCheck(s.positionMs, now, max)

            else -> {
                // Pause, Fehler, Ende: kein Hänger.
                clear()
                null
            }
        }
    }

    /**
     * Puffern allein ist kein Hänger: Solange Daten nachkommen, füllt die Engine nur den Puffer (nach dem
     * Umschalten normal). Fire-TV-Protokoll vom 01.11.: „puffert seit 3001 ms → andere Quelle“, danach
     * „behoben nach 0,6 s“ – die Quelle wurde umsonst gewechselt. Jetzt muss der Nachschub stehen.
     */
    private fun buffering(s: HealthSnapshot, now: Long, max: Long, hard: Long): Finding? {
        positionStuckSince = -1L
        if (bufferingSince < 0) bufferingSince = now
        val bytes = s.bytesLoaded ?: -1L
        if (bytes != bufferedBytes) {
            bufferedBytes = bytes
            bytesStuckSince = now
        }
        val waited = now - bufferingSince
        val starving = bytes < 0 || now - bytesStuckSince >= max
        return fireOnce(waited >= max && (starving || waited >= hard)) {
            val why = if (starving) "keine neuen Daten seit ${now - bytesStuckSince} ms" else "Daten kommen, Wiedergabe startet nicht"
            Finding(Symptom.STALL, "puffert seit $waited ms, $why")
        }
    }

    private fun positionCheck(position: Long, now: Long, max: Long): Finding? {
        bufferingSince = -1L
        if (position != lastPosition) {
            lastPosition = position
            positionStuckSince = -1L
            fired = false
            return null
        }
        if (positionStuckSince < 0) positionStuckSince = now
        val waited = now - positionStuckSince
        return fireOnce(waited >= max) { Finding(Symptom.STALL, "spielt, aber Position steht seit $waited ms") }
    }

    private inline fun fireOnce(condition: Boolean, finding: () -> Finding): Finding? {
        if (!condition || fired) return null
        fired = true
        return finding()
    }

    private fun clear() {
        bufferingSince = -1L
        bufferedBytes = -1L
        bytesStuckSince = 0L
        positionStuckSince = -1L
        fired = false
    }

    override fun reset() {
        clear()
        started = false
        lastPosition = -1L
    }
}

/** Verfolgt, ob ein steigender Zähler (Bilder, Tonblöcke) sich bewegt, und seit wann er steht. */
internal class ProgressTracker {
    var movedAt = -1L
        private set
    var firstMoveAt = -1L
        private set
    private var last = -1L
    private var stillSince = -1L

    val hasMoved: Boolean get() = firstMoveAt >= 0

    /** true = hat sich seit dem letzten Aufruf bewegt. */
    fun update(value: Long?, now: Long): Boolean {
        if (value == null) return false
        val moved = last in 0 until value
        if (moved) {
            movedAt = now
            if (firstMoveAt < 0) firstMoveAt = now
            stillSince = -1L
        } else if (stillSince < 0) {
            stillSince = now
        }
        last = value
        return moved
    }

    fun movingRecently(now: Long): Boolean = movedAt >= 0 && now - movedAt <= RECENT_MS

    fun stillFor(now: Long): Long = if (stillSince < 0) 0 else now - stillSince

    fun reset() {
        last = -1L
        movedAt = -1L
        firstMoveAt = -1L
        stillSince = -1L
    }
}

/** S4: Standbild – vorher kamen Bilder, jetzt stehen sie, obwohl die Engine „spielt“. */
class FrozenPictureDetector : Detector {
    private val frames = ProgressTracker()
    private val audio = ProgressTracker()
    private var fired = false

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        if (s.renderedFrames == null || !s.playing) {
            reset()
            return null
        }
        if (frames.update(s.renderedFrames, now)) fired = false
        audio.update(s.audioUnits, now)
        val still = frames.stillFor(now)
        if (fired || !frames.hasMoved || still < ctx.config.frozenPicture) return null
        fired = true
        val sound = when {
            s.audioUnits == null -> "Ton unbekannt"
            audio.movingRecently(now) -> "Ton läuft"
            else -> "Ton steht auch"
        }
        return Finding(Symptom.FROZEN_PICTURE, "kein neues Bild seit $still ms, $sound")
    }

    override fun reset() {
        frames.reset()
        audio.reset()
        fired = false
    }
}

/**
 * S5/S6: Nur Ton oder nur Bild. Unterscheidet echte Störungen von Sendern, die gar keine Video- bzw. Tonspur haben
 * (Radio, stumme Kanäle) – dort gibt es nur einen Hinweis, keine Maßnahme.
 */
class AvPresenceDetector : Detector {
    private val frames = ProgressTracker()
    private val audio = ProgressTracker()
    private var lastAudioErrors = -1
    private var audioErrorBurst = 0
    private val fired = HashSet<Symptom>()

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        if (!s.playing) {
            frames.reset()
            audio.reset()
            return null
        }
        frames.update(s.renderedFrames, now)
        audio.update(s.audioUnits, now)
        // Fire-TV-Protokoll vom 31.10.: Ein einzelner Ausgabefehler (Tonformat-Wechsel, anderer Ton-Fokus) löste
        // dreimal „Bild ohne Ton“ aus – jedes Mal „behoben nach 0,0 s“, der Ton war nie weg. Darum zählt jetzt nur
        // eine Häufung ([SupervisorConfig.audioErrorBurst] in Folge) oder ein wirklich stehender Tonzähler.
        if (lastAudioErrors >= 0 && s.audioErrors > lastAudioErrors) audioErrorBurst++ else if (s.audioErrors == lastAudioErrors) audioErrorBurst = 0
        lastAudioErrors = s.audioErrors
        val newAudioErrors = audioErrorBurst >= ctx.config.audioErrorBurst
        val finding = audioOnly(s, now, ctx) ?: videoOnly(s, now, ctx, newAudioErrors)
        return finding?.takeIf { fired.add(it.symptom) }
    }

    private fun audioOnly(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        if (s.audioUnits == null || !audio.hasMoved) return null
        val audioFor = now - audio.firstMoveAt
        return when {
            s.hasVideoTrack == false && audioFor >= ctx.config.radioHint -> Finding(Symptom.RADIO_ONLY, "keine Videospur, Ton läuft")

            s.hasVideoTrack == true && neverShowedPicture(s) && audioFor >= ctx.config.audioWithoutVideo ->
                Finding(Symptom.AUDIO_WITHOUT_VIDEO, "Ton seit $audioFor ms, noch kein Bild (Videofehler: ${s.videoErrors})")

            else -> null
        }
    }

    private fun neverShowedPicture(s: HealthSnapshot) = s.renderedFrames == 0L && !frames.hasMoved

    private fun videoOnly(s: HealthSnapshot, now: Long, ctx: DetectContext, newAudioErrors: Boolean): Finding? {
        if (s.renderedFrames == null || !frames.hasMoved || !frames.movingRecently(now)) return null
        val pictureFor = now - frames.firstMoveAt
        return when (s.hasAudioTrack) {
            false -> Finding(Symptom.NO_AUDIO_TRACK, "keine Tonspur, Bild läuft").takeIf { pictureFor >= ctx.config.videoWithoutAudio }
            true -> silence(s, now, ctx, pictureFor, newAudioErrors)
            null -> null
        }
    }

    /** Tonspur vorhanden, aber kein Ton. Ohne Tonzähler (z. B. bei Ton-Durchreichen) nur echte Ausgabefehler werten. */
    private fun silence(s: HealthSnapshot, now: Long, ctx: DetectContext, pictureFor: Long, newAudioErrors: Boolean): Finding? {
        if (newAudioErrors) return Finding(Symptom.VIDEO_WITHOUT_AUDIO, "Ton-Ausgabefehler, Bild läuft")
        if (s.audioUnits == null) return null
        val silentFor = if (audio.hasMoved) audio.stillFor(now) else pictureFor
        return Finding(Symptom.VIDEO_WITHOUT_AUDIO, "kein Ton seit $silentFor ms, Bild läuft").takeIf { silentFor >= ctx.config.videoWithoutAudio }
    }

    override fun reset() {
        frames.reset()
        audio.reset()
        lastAudioErrors = -1
        audioErrorBurst = 0
        // Radio/stumm sind Eigenschaften des Senders: je Inhalt nur einmal melden (resetSession).
        fired.removeAll { !it.informational }
    }

    /** Neuer Inhalt: auch die Hinweise wieder erlauben. */
    fun resetSession() {
        reset()
        fired.clear()
    }
}
