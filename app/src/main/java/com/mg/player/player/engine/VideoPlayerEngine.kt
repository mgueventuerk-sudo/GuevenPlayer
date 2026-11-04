package com.mg.player.player.engine

import android.widget.FrameLayout
import androidx.compose.runtime.Immutable
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.ChapterInfo
import com.mg.player.domain.model.HwDecoding
import com.mg.player.domain.model.PlayerEngineType
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackStatus { IDLE, BUFFERING, READY, ENDED, ERROR, HANDED_OFF }

/** Art eines Wiedergabefehlers – bestimmt, ob ein Neuversuch sinnvoll ist und welche Meldung erscheint. */
enum class ErrorKind {
    NONE,

    /** Verbindungsabbruch, Timeout, Server 5xx: Neuversuch mit Wartezeit. */
    NETWORK,

    /** HTTP 401/403: Abo abgelaufen, gesperrt oder zu viele gleichzeitige Verbindungen. */
    AUTH,

    /** HTTP 404/410: Sender oder Film gibt es beim Anbieter nicht (mehr). */
    NOT_FOUND,

    /** Format nicht abspielbar (Container, Codec). */
    FORMAT,
    OTHER,
}

/** Genauere Ursache eines Fehlers – steuert die Gegenmaßnahme des Wiedergabe-Wächters. */
enum class ErrorCause {
    NONE,

    /** HTTP 429: Anbieter drosselt, Wartezeit aus `Retry-After`. */
    RATE_LIMITED,

    /** HTTP 5xx. */
    SERVER,

    /** Zeitüberschreitung beim Verbinden oder Lesen. */
    TIMEOUT,

    /** Verbindung abgebrochen oder nicht aufgebaut. */
    CONNECTION,

    /** Decoder ließ sich nicht starten oder ist beim Dekodieren gescheitert. */
    DECODER,

    /** Ton-Ausgabe (AudioTrack) ließ sich nicht öffnen oder beschreiben. */
    AUDIO_OUTPUT,

    /** Container nicht lesbar. */
    CONTAINER,
}

@Immutable
data class EngineState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
    val videoFrameRate: Float? = null,
    val tracksVersion: Int = 0,
    /** Container, Video- oder Tonformat kann diese Engine nicht abspielen (z. B. DTS bei ExoPlayer). */
    val formatUnsupported: Boolean = false,
    val errorKind: ErrorKind = ErrorKind.NONE,
    val httpCode: Int? = null,
    val errorCause: ErrorCause = ErrorCause.NONE,
    /** Wartezeit laut Server (`Retry-After`), falls angegeben. */
    val retryAfterSec: Long? = null,
)

/**
 * Momentaufnahme für die Stream-Info im Player-Menü. [bytesLoaded] zählt seit dem Start des Streams –
 * aus der Differenz pro Sekunde ergibt sich die tatsächliche Downloadrate.
 */
@Immutable
data class StreamStats(
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val videoCodec: String? = null,
    /** Vom Stream angegebene Video-Bitrate in bit/s (bei Live-TS meist unbekannt). */
    val videoBitrate: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val bytesLoaded: Long = 0,
    val bufferedMs: Long? = null,
    val droppedFrames: Int? = null,
)

@Immutable
data class TrackOption(val id: String, val label: String, val selected: Boolean)

data class StreamRequest(
    val url: String,
    val title: String,
    val startPositionMs: Long,
    val isLive: Boolean,
    val bufferProfile: BufferProfile,
    val hwDecoding: HwDecoding,
    /** Hardware-Tunneling (nur ExoPlayer): Video läuft direkt zum Display-Pfad, entlastet die CPU. */
    val tunneling: Boolean = false,
    /** Dolby/DTS durchreichen statt im Gerät zu dekodieren (nur ExoPlayer). */
    val audioPassthrough: Boolean = false,
    /** Explizite Freigabe des IPTV-Zugangs für HTTP; gilt für Playlist und HLS-Unteranfragen. */
    val allowInsecureHttp: Boolean = false,
)

/**
 * Strategy-Interface für alle Player-Engines (Media3, LibVLC, System-Player, extern).
 * Alle Methoden werden auf dem Main-Thread aufgerufen. [StreamRequest.url] darf HTTP-Header im
 * Format `url|User-Agent=…&Referer=…` enthalten – jede Engine trennt sie über StreamTarget ab.
 */
interface VideoPlayerEngine {
    val type: PlayerEngineType
    val state: StateFlow<EngineState>
    val positionMs: Long
    val durationMs: Long

    /** Bisher gezeigte Videobilder (nur wo die Engine sie meldet), null = unbekannt. Für die Hänger-Erkennung. */
    val renderedFrames: Long? get() = null

    /** Gesundheitswerte für den Wiedergabe-Wächter (null = Engine liefert keine). Aufruf auf dem Main-Thread, im Player-Takt. */
    fun health(): HealthSnapshot? = null

    /** Live: zum Live-Rand springen. false = kann diese Engine nicht. */
    fun seekToLiveEdge(): Boolean = false

    /** Video- bzw. Tonspur abwählen und wieder wählen (startet den Decoder neu). false = kann diese Engine nicht. */
    fun refreshTrack(video: Boolean): Boolean = false

    fun attachSurface(container: FrameLayout)
    fun detachSurface(container: FrameLayout)

    fun prepare(request: StreamRequest)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    fun audioTracks(): List<TrackOption>
    fun subtitleTracks(): List<TrackOption>
    fun selectAudioTrack(id: String)

    /** null schaltet Untertitel aus. */
    fun selectSubtitleTrack(id: String?)

    /** Eingebettete Kapitel (z. B. MKV-Chapters). Leer, wenn die Engine keine liefert. */
    fun chapters(): List<ChapterInfo>

    /** Technische Daten für die Stream-Info (null = Engine liefert keine). */
    fun stats(): StreamStats? = null

    fun release()

}

/**
 * Puffer-Werte je Profil (Millisekunden). Kurzer Startpuffer für schnelles Zappen,
 * aber ein größerer Puffer nach einem Aussetzer, damit es bei schwankender Leitung nicht stottert.
 */
data class BufferConfig(val minMs: Int, val maxMs: Int, val playbackMs: Int, val rebufferMs: Int, val vlcCachingMs: Int)

fun BufferProfile.toConfig(): BufferConfig = when (this) {
    BufferProfile.FAST -> BufferConfig(minMs = 5_000, maxMs = 30_000, playbackMs = 1_000, rebufferMs = 3_000, vlcCachingMs = 800)
    BufferProfile.STANDARD -> BufferConfig(minMs = 10_000, maxMs = 50_000, playbackMs = 1_500, rebufferMs = 3_500, vlcCachingMs = 1_500)
    BufferProfile.STABLE -> BufferConfig(minMs = 20_000, maxMs = 90_000, playbackMs = 3_000, rebufferMs = 6_000, vlcCachingMs = 5_000)
}

/**
 * Obergrenze für den ExoPlayer-Puffer in Bytes. Der Puffer liegt im Java-Heap; ohne Grenze nimmt ExoPlayer
 * für Video rund 128 MB – bei 4K-Streams (20–25 Mbit/s) und 50 s Puffer wird das auch erreicht. Auf Sticks
 * mit 1 GB RAM (Heap ~256 MB) reicht das zusammen mit Bild-Cache und App für einen Speicherüberlauf.
 *
 * Regel: ein Fünftel des Heaps, zwischen 24 und 80 MB; Geräte mit wenig RAM fest 24 MB. Dort hat die Grenze
 * auch Vorrang vor der Mindest-Pufferzeit – lieber ein kürzerer Puffer als ein Absturz.
 */
data class BufferLimit(val targetBytes: Int, val prioritizeTime: Boolean) {
    companion object {
        private const val MB = 1024 * 1024

        fun forDevice(heapMb: Int, lowRamDevice: Boolean): BufferLimit {
            val lowMemory = lowRamDevice || heapMb < 256
            val mb = if (lowRamDevice) 24 else (heapMb / 5).coerceIn(24, 80)
            return BufferLimit(targetBytes = mb * MB, prioritizeTime = !lowMemory)
        }
    }
}
