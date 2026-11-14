package com.mg.player.player.supervisor

import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus

/** Muss dieser Sender ein Bild haben? Bestimmt, ob „keine Videospur“ ein Radiosender oder eine Störung ist. */
enum class PictureExpectation {
    /** Radio: laut Name oder weil schon mehrere Versuche kein Bild brachten. */
    RADIO,

    /** Hatte früher nachweislich ein Bild. */
    TV,

    /** Nichts bekannt. */
    UNKNOWN,
}

/** Was ein Detektor über den laufenden Start wissen muss. */
data class DetectContext(
    val session: SessionInfo,
    val preparedAtMs: Long,
    val config: SupervisorConfig,
    val picture: PictureExpectation = PictureExpectation.UNKNOWN,
) {
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
        // Live mit Ersatzquelle (ab 3.51): ohne Nachschub nach 2 s, sonst spätestens nach 2,5 s – auch ohne Datenmenge
        // (LibVLC/System-Player) und bei stehender Position. Ohne Ersatz bleiben die langen Grenzen (dort hilft nur Neu-Verbinden).
        val quick = ctx.quickSwitch
        val latest = if (quick) cfg.stallWithAlternativeMax else if (ctx.session.isLive) cfg.stallLive else cfg.stallVod
        val starving = if (quick && s.bytesLoaded == null) latest else ctx.limit(cfg.stallWithAlternative, cfg.stallLive, cfg.stallVod)
        return when {
            // „hart“: Auch wenn Daten nachkommen, ist Dauerpuffern irgendwann ein Hänger.
            s.status == PlaybackStatus.BUFFERING -> buffering(s, now, starving, latest)

            s.playing -> positionCheck(s.positionMs, now, if (quick) latest else starving)

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
        if (last < 0 || value < last) {
            // Decoderzähler können nach einem Renderer-Neustart zurückspringen. Das ist ein neuer Messabschnitt,
            // kein Beleg dafür, dass ein Standbild bereits läuft.
            stillSince = now
        } else if (moved) {
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
    private var playingSince = -1L

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        if (!s.playing || s.hasVideoTrack == false || s.renderedFrames == null) {
            reset()
            return null
        }
        if (playingSince < 0) playingSince = now
        if (frames.update(s.renderedFrames, now)) fired = false
        audio.update(s.audioUnits, now)
        // Auch „READY, aber nie ein erstes Bild gerendert“ erkennen. Media3 wird erst nach einem READY-Status
        // geprüft; ein Sender ohne Videospur wird durch hasVideoTrack=false ausgeschlossen.
        val still = if (frames.hasMoved) frames.stillFor(now) else now - playingSince
        if (fired || still < ctx.config.frozenPicture) return null
        fired = true
        val sound = when {
            s.audioUnits == null -> "Ton unbekannt"
            audio.movingRecently(now) -> "Ton läuft"
            else -> "Ton steht auch"
        }
        val picture = if (frames.hasMoved) "kein neues Bild" else "kein erstes Bild"
        return Finding(Symptom.FROZEN_PICTURE, "$picture seit $still ms, $sound")
    }

    override fun reset() {
        frames.reset()
        audio.reset()
        fired = false
        playingSince = -1L
    }
}

/** Datenrate aus den geladenen Bytes der letzten Sekunden (null = Engine meldet keine Bytes oder zu wenig Messzeit). */
internal class DataRateMeter {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun update(bytes: Long?, now: Long) {
        if (bytes == null) return
        // Zähler springt zurück (neuer Start): neu messen.
        if (samples.isNotEmpty() && bytes < samples.last().second) samples.clear()
        samples.addLast(now to bytes)
        while (samples.size > 1 && now - samples.first().first > WINDOW_MS) samples.removeFirst()
    }

    /** Byte pro Sekunde über das Messfenster, erst ab [MIN_SPAN_MS] Messzeit. */
    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val span = t1 - t0
        return if (span < MIN_SPAN_MS) null else (b1 - b0) * MS_PER_S / span
    }

    fun reset() = samples.clear()

    private companion object {
        const val WINDOW_MS = 4_000L
        const val MIN_SPAN_MS = 2_000L
        const val MS_PER_S = 1_000L
    }
}

/**
 * S5/S6: Nur Ton oder nur Bild. Unterscheidet echte Störungen von Sendern, die gar keine Video- bzw. Tonspur haben
 * (Radio, stumme Kanäle) – dort gibt es nur einen Hinweis, keine Maßnahme.
 *
 * „Keine Videospur“ allein heißt aber nicht „Radio“: Ein Fernsehsender, dessen Bild ausfällt, sieht genauso aus – bis
 * 3.58 gab es dafür nur den Hinweis „Nur Ton“ und keine Maßnahme. Jetzt entscheidet [PictureExpectation] und die
 * Datenrate: Kommt mehr an, als ein Radiosender braucht, oder hatte der Sender schon einmal ein Bild, ist es eine Störung.
 */
class AvPresenceDetector : Detector {
    private val frames = ProgressTracker()
    private val audio = ProgressTracker()
    private val position = ProgressTracker()
    private val rate = DataRateMeter()
    private var lastAudioErrors = -1
    private var audioErrorBurst = 0
    private val fired = HashSet<Symptom>()

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        if (!s.playing) {
            frames.reset()
            audio.reset()
            position.reset()
            rate.reset()
            return null
        }
        frames.update(s.renderedFrames, now)
        audio.update(s.audioUnits, now)
        position.update(s.positionMs, now)
        rate.update(s.bytesLoaded, now)
        // Fire-TV-Protokoll vom 31.10.: Ein einzelner Ausgabefehler (Tonformat-Wechsel, anderer Ton-Fokus) löste
        // dreimal „Bild ohne Ton“ aus – jedes Mal „behoben nach 0,0 s“, der Ton war nie weg. Darum zählt jetzt nur
        // eine Häufung ([SupervisorConfig.audioErrorBurst] in Folge) oder ein wirklich stehender Tonzähler.
        if (lastAudioErrors >= 0 && s.audioErrors > lastAudioErrors) audioErrorBurst++ else if (s.audioErrors == lastAudioErrors) audioErrorBurst = 0
        lastAudioErrors = s.audioErrors
        val newAudioErrors = audioErrorBurst >= ctx.config.audioErrorBurst
        val finding = audioOnly(s, now, ctx) ?: videoOnly(s, now, ctx, newAudioErrors)
        return finding?.takeIf { fired.add(it.symptom) }
    }

    /**
     * Wie lange der Ton schon läuft: laut Tonzähler, oder – wo es keinen gibt (Ton-Durchreichen) – laut Position. null =
     * noch kein Beleg, dass Ton läuft.
     */
    private fun soundFor(s: HealthSnapshot, now: Long): Long? = when {
        s.audioUnits != null -> audio.takeIf { it.hasMoved }?.let { now - it.firstMoveAt }
        else -> position.takeIf { it.hasMoved && it.movingRecently(now) }?.let { now - it.firstMoveAt }
    }

    private fun audioOnly(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        val soundFor = soundFor(s, now) ?: return null
        return when (s.hasVideoTrack) {
            false -> withoutVideoTrack(ctx, soundFor)
            true -> withoutPicture(s, ctx, soundFor)
            null -> null
        }
    }

    /** Videospur da, aber kein Bild – auch ohne Bilderzähler (Tunneling), wenn die Engine „erstes Bild“ meldet. */
    private fun withoutPicture(s: HealthSnapshot, ctx: DetectContext, soundFor: Long): Finding? {
        val cfg = ctx.config
        val limit = if (ctx.quickSwitch) cfg.audioWithoutVideoWithAlternative else cfg.audioWithoutVideo
        val noPicture = if (s.renderedFrames != null) neverShowedPicture(s) else s.pictureShown == false
        if (!noPicture || soundFor < limit) return null
        val counter = if (s.renderedFrames == null) ", ohne Bilderzähler" else ""
        return Finding(Symptom.AUDIO_WITHOUT_VIDEO, "Ton seit $soundFor ms, noch kein Bild (Videofehler: ${s.videoErrors}$counter)")
    }

    /**
     * Keine Videospur: Radio (nur Hinweis) oder ein Sender, dem das Bild fehlt (Maßnahme). Entschieden wird so:
     * - Name/Gedächtnis sagt Radio, oder kein Live-Sender → Hinweis.
     * - Hatte schon ein Bild, oder die Datenrate ist höher als bei Radio → Störung (sicher).
     * - Sonst, mit Ersatzquelle → Störung als Vermutung (wenige Versuche, dann als Radio merken); ohne → Hinweis.
     */
    private fun withoutVideoTrack(ctx: DetectContext, soundFor: Long): Finding? {
        val cfg = ctx.config
        val flowingKb = rate.bytesPerSecond()?.takeIf { it >= cfg.videoDataRate }?.let { it / BYTES_PER_KB }
        val dataFlows = flowingKb != null
        val suspicious = ctx.picture == PictureExpectation.TV || dataFlows || ctx.session.canSwitchVariant
        val failure = ctx.session.isLive && ctx.picture != PictureExpectation.RADIO && suspicious
        if (!failure) return radioHint(soundFor, cfg)
        val limit = if (ctx.quickSwitch) cfg.noVideoTrackWithAlternative else cfg.noVideoTrack
        if (soundFor < limit) return null
        val sure = ctx.picture == PictureExpectation.TV || dataFlows
        val why = when {
            ctx.picture == PictureExpectation.TV -> "hatte früher ein Bild"
            dataFlows -> "$flowingKb KB/s kommen an – mehr als bei Radio"
            else -> "Radio nicht ausgeschlossen"
        }
        return Finding(Symptom.NO_VIDEO_TRACK, "Ton seit $soundFor ms, keine Videospur ($why)", confident = sure)
    }

    private fun radioHint(soundFor: Long, cfg: SupervisorConfig): Finding? =
        Finding(Symptom.RADIO_ONLY, "keine Videospur, Ton läuft").takeIf { soundFor >= cfg.radioHint }

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
        position.reset()
        rate.reset()
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
