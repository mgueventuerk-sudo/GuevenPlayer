package com.mg.player.data.recording

import com.mg.player.core.log.AppLog
import com.mg.player.core.network.IPTV_USER_AGENT
import com.mg.player.core.util.StreamTarget
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Steuerung einer laufenden Aufnahme von außen (Stoppen bricht auch die offene Verbindung ab). */
class RecordingSession(
    val recordingId: String,
    val channelId: String,
    val allowInsecureHttp: Boolean = false,
) {
    @Volatile var stopped: Boolean = false
        private set

    @Volatile var stopReason: String? = null
        private set

    @Volatile internal var call: Call? = null

    /** HTTP-Header des Senders (User-Agent, Referer aus der M3U-Liste). */
    @Volatile internal var headers: Map<String, String> = emptyMap()

    /** Letztes geschriebenes HLS-Segment – auch über Neuverbindungen hinweg keine Doppelten. */
    @Volatile internal var lastSequence: Long = -1L

    fun stop(reason: String? = null) {
        stopReason = reason
        stopped = true
        call?.cancel()
    }
}

/**
 * Schreibt einen Live-Stream in eine Datei. MPEG-TS-Streams (Xtream-Standard) werden direkt kopiert,
 * HLS-Streams (.m3u8) Segment für Segment angehängt. Bei Abbrüchen wird automatisch neu verbunden.
 */
class StreamRecorder(baseClient: OkHttpClient) {

    private val client = baseClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val allowed = request.tag(RecordingSession::class.java)?.allowInsecureHttp == true
            if (!request.url.isHttps && !allowed) throw IOException("HTTP für diesen IPTV-Zugang nicht freigegeben")
            chain.proceed(request)
        }
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Liefert die Gesamtzahl geschriebener Bytes. [onBytes] meldet den Zwischenstand. */
    suspend fun record(url: String, file: File, untilMs: Long, session: RecordingSession, hasSpace: () -> Boolean, onBytes: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val target = StreamTarget.parse(url)
            session.headers = target.headers
            val streamUrl = target.url
            if (streamUrl.startsWith("http://", ignoreCase = true) && !session.allowInsecureHttp) {
                throw IOException("HTTP für diesen IPTV-Zugang nicht freigegeben")
            }
            var total = 0L
            var failures = 0
            FileOutputStream(file, true).buffered(BUFFER).use { out ->
                val counter = { n: Int ->
                    total += n
                    onBytes(total)
                }
                while (!session.stopped && System.currentTimeMillis() < untilMs) {
                    coroutineContext.ensureActive()
                    if (!hasSpace()) {
                        session.stop("Speicher fast voll – Aufnahme beendet")
                        break
                    }
                    val before = total
                    try {
                        if (isHls(streamUrl)) {
                            recordHls(streamUrl, out, untilMs, session, hasSpace, counter)
                        } else {
                            recordRaw(streamUrl, out, untilMs, session, hasSpace, counter)
                        }
                    } catch (e: IOException) {
                        if (session.stopped) break
                        AppLog.log("Aufnahme", "Verbindung unterbrochen: ${e.message}")
                    }
                    out.flush()
                    if (session.stopped || System.currentTimeMillis() >= untilMs) break
                    // Kamen Daten an, war die Verbindung grundsätzlich in Ordnung -> Fehlerzähler zurücksetzen.
                    failures = if (total > before) 0 else failures + 1
                    if (failures > MAX_FAILURES) throw IOException("Sender nicht erreichbar")
                    delay(RETRY_DELAY_MS * failures.coerceAtLeast(1))
                }
            }
            total
        }

    private fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private suspend fun recordRaw(url: String, out: OutputStream, untilMs: Long, session: RecordingSession, hasSpace: () -> Boolean, count: (Int) -> Unit) {
        open(url, session).use { response ->
            val type = response.header("Content-Type").orEmpty().lowercase()
            if ("mpegurl" in type) {
                // Der Server liefert trotz .ts-Endung eine HLS-Playlist.
                response.close()
                recordHls(response.request.url.toString(), out, untilMs, session, hasSpace, count)
                return
            }
            val input = response.body.byteStream()
            val buffer = ByteArray(BUFFER)
            var sinceCheck = 0L
            while (!session.stopped && System.currentTimeMillis() < untilMs) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                count(read)
                sinceCheck += read
                if (sinceCheck > SPACE_CHECK_BYTES) {
                    sinceCheck = 0
                    if (!hasSpace()) {
                        session.stop("Speicher fast voll – Aufnahme beendet")
                        break
                    }
                }
            }
        }
    }

    private suspend fun recordHls(
        playlistUrl: String,
        out: OutputStream,
        untilMs: Long,
        session: RecordingSession,
        hasSpace: () -> Boolean,
        count: (Int) -> Unit,
    ) {
        var mediaUrl = playlistUrl
        val first = fetchText(playlistUrl, session)
        if ("#EXT-X-STREAM-INF" in first) mediaUrl = bestVariant(first, playlistUrl) ?: playlistUrl
        // Zuletzt geschriebenes Segment (Sequenznummer) – nicht die Adresse: Token-Adressen ändern sich bei jedem Abruf.
        var lastSequence = session.lastSequence
        var initWritten = false
        val keys = HashMap<String, ByteArray>()
        while (!session.stopped && System.currentTimeMillis() < untilMs) {
            coroutineContext.ensureActive()
            val playlist = HlsPlaylist.parse(fetchText(mediaUrl, session))
            // Sender neu gestartet (Sequenz beginnt von vorn): sonst würde ab jetzt alles übersprungen.
            if (playlist.segments.isNotEmpty() && playlist.segments.last().sequence + SEQUENCE_RESET < lastSequence) lastSequence = -1L
            if (!initWritten) {
                playlist.mapUri?.let { download(resolve(mediaUrl, it), out, session, count) }
                initWritten = true
            }
            for (segment in playlist.segments) {
                if (segment.sequence <= lastSequence) continue
                if (session.stopped || System.currentTimeMillis() >= untilMs) return
                if (!hasSpace()) {
                    session.stop("Speicher fast voll – Aufnahme beendet")
                    return
                }
                val key = segment.key
                val decrypt: ((InputStream) -> InputStream)? = when {
                    key == null || key.isNone -> null

                    key.isAes128 && key.uri != null -> {
                        val keyUrl = resolve(mediaUrl, key.uri)
                        val bytes = keys.getOrPut(keyUrl) { fetchBytes(keyUrl, session) }
                        val iv = HlsPlaylist.ivFor(segment)
                        ({ input -> HlsPlaylist.decrypting(input, bytes, iv) })
                    }

                    else -> {
                        // SAMPLE-AES bzw. DRM: lässt sich nicht aufnehmen – lieber klar beenden als eine kaputte Datei.
                        session.stop("Sender ist verschlüsselt (${key.method}) – Aufnahme nicht möglich")
                        return
                    }
                }
                download(resolve(mediaUrl, segment.uri), out, session, count, decrypt)
                lastSequence = segment.sequence
                session.lastSequence = lastSequence
            }
            if (playlist.endList) return
            delay((playlist.targetDurationSec * 500).toLong().coerceIn(1_000, 5_000))
        }
    }

    private fun bestVariant(master: String, baseUrl: String): String? {
        val lines = master.lines().map { it.trim() }
        var best: String? = null
        var bestBandwidth = -1L
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth
                best = resolve(baseUrl, uri)
            }
        }
        return best
    }

    private fun resolve(base: String, ref: String): String = base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    private fun fetchText(url: String, session: RecordingSession): String = open(url, session).use { response ->
        val source = response.body.source()
        // Endlose oder riesige "Playlist" (falscher Link auf den Stream selbst) darf den Speicher nicht füllen.
        if (source.request(MAX_PLAYLIST_BYTES + 1)) throw IOException("HLS-Playlist zu groß")
        source.buffer.readUtf8()
    }

    private fun fetchBytes(url: String, session: RecordingSession): ByteArray = open(url, session).use { it.body.bytes() }
        .also { if (it.size != 16) throw IOException("Ungültiger HLS-Schlüssel (${it.size} Bytes)") }

    private fun download(url: String, out: OutputStream, session: RecordingSession, count: (Int) -> Unit, decrypt: ((InputStream) -> InputStream)? = null) {
        open(url, session).use { response ->
            val raw = response.body.byteStream()
            val input = decrypt?.invoke(raw) ?: raw
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                count(read)
            }
        }
    }

    private fun open(url: String, session: RecordingSession): Response {
        val builder = Request.Builder().url(url).header("User-Agent", IPTV_USER_AGENT)
        session.headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.tag(RecordingSession::class.java, session).build()
        val call = client.newCall(request)
        session.call = call
        if (session.stopped) throw IOException("Gestoppt")
        val response = call.execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Server antwortet mit HTTP $code")
        }
        return response
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val MAX_FAILURES = 6
        const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024
        const val RETRY_DELAY_MS = 3_000L
        const val SPACE_CHECK_BYTES = 32L * 1024 * 1024
        const val SEQUENCE_RESET = 1_000L
    }
}
