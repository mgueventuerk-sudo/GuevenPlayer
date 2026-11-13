package com.mg.player.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import com.mg.player.domain.model.BufferProfile
import com.mg.player.domain.model.HwDecoding
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Hilfsfunktionen für [Media3Engine]: Decoder-Wahl, Fehlerarten, Byte-Zähler, Format-Erkennung. */
@OptIn(UnstableApi::class)
internal object Media3Support {
    /** Abspielbare Spuren einer Art als Auswahl ("Gruppe:Spur"). */
    fun tracksOf(p: Player, trackType: Int): List<TrackOption> {
        val result = ArrayList<TrackOption>()
        p.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != trackType) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                result += TrackOption("$groupIndex:$trackIndex", labelFor(format, result.size), group.isTrackSelected(trackIndex))
            }
        }
        return result
    }

    fun selectTrack(p: Player, trackType: Int, id: String) {
        val parts = id.split(':')
        val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return
        val trackIndex = parts.getOrNull(1)?.toIntOrNull() ?: return
        val group = p.currentTracks.groups.getOrNull(groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    /** Ladefehler (Verbindung kurz weg, Server antwortet zäh) so oft intern wiederholen, bevor ein Fehler gemeldet wird. */
    const val LOAD_RETRIES = 5

    fun sourceFactory(data: DataSource.Factory, extractors: ExtractorsFactory): MediaSource.Factory =
        DefaultMediaSourceFactory(data, extractors).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES))

    /**
     * Fehler, bei denen ein Neuversuch mit derselben Engine nichts bringt – LibVLC übernimmt.
     * Die MANIFEST_*-Varianten fehlten neben den CONTAINER_*-Varianten (z. B. kaputtes m3u8 bei "Haysiyet",
     * 13.11.): Ohne sie landete ERROR_CODE_PARSING_MANIFEST_MALFORMED als ErrorKind.OTHER statt FORMAT - der
     * Wächter versuchte vier sinnlose Neuverbindungen (1/2/4/8 s), statt sofort auf LibVLC zu wechseln.
     */
    val FORMAT_ERRORS = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    /** Hardware-Dekodierung "Aus" bevorzugt Software-Decoder, "An" bevorzugt Hardware-Decoder. */
    fun codecSelector(mode: HwDecoding): MediaCodecSelector = when (mode) {
        HwDecoding.AUTO -> MediaCodecSelector.DEFAULT

        HwDecoding.ON -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.hardwareAccelerated) 0 else 1 }
        }

        HwDecoding.OFF -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
        }
    }

    private val DECODER_ERRORS = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
    )

    private val AUDIO_OUTPUT_ERRORS = setOf(
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
    )

    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_STREAM_NOT_ALLOWED = 456
    private const val HTTP_SERVER_ERRORS = 500
    private const val PARSING_ERROR_GROUP = 3
    private const val ERROR_GROUP_SIZE = 1_000
    private const val MAX_RETRY_AFTER_S = 3_600L

    private fun httpError(error: PlaybackException): HttpDataSource.InvalidResponseCodeException? =
        generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

    /** Genauere Ursache für den Wiedergabe-Wächter. */
    fun cause(error: PlaybackException): ErrorCause {
        val http = httpError(error)?.responseCode
        return when {
            http == HTTP_TOO_MANY_REQUESTS -> ErrorCause.RATE_LIMITED

            http != null && http >= HTTP_SERVER_ERRORS -> ErrorCause.SERVER

            error.errorCode in DECODER_ERRORS -> ErrorCause.DECODER

            error.errorCode in AUDIO_OUTPUT_ERRORS -> ErrorCause.AUDIO_OUTPUT

            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> ErrorCause.TIMEOUT

            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> ErrorCause.CONNECTION

            error.errorCode / ERROR_GROUP_SIZE == PARSING_ERROR_GROUP -> ErrorCause.CONTAINER

            else -> ErrorCause.NONE
        }
    }

    /** `Retry-After` in Sekunden (nur die Zahlenform; ein Datum wird ignoriert). */
    fun retryAfterSec(error: PlaybackException): Long? =
        httpError(error)?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it in 0..MAX_RETRY_AFTER_S }

    /** Fehlerart und HTTP-Status aus einer PlaybackException (die Ursache steckt oft mehrere Ebenen tief). */
    fun classify(error: PlaybackException): Pair<ErrorKind, Int?> {
        val http = httpError(error)?.responseCode
        val kind = when {
            // 456 liefern Xtream-Panels bei gesperrtem Stream bzw. zu vielen Verbindungen. Als Netzfehler behandelt
            // (Protokoll 29.10./01.11.) führte das zu sechs Neuversuchen gegen einen Server, der bewusst „nein“ sagt.
            http == 401 || http == 403 || http == HTTP_STREAM_NOT_ALLOWED -> ErrorKind.AUTH

            http == 404 || http == 410 -> ErrorKind.NOT_FOUND

            error.errorCode in FORMAT_ERRORS -> ErrorKind.FORMAT

            // 2xxx = Ein-/Ausgabe (Verbindung, Timeout, HTTP 5xx), 1003 = allgemeiner Timeout.
            error.errorCode / 1000 == 2 || error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> ErrorKind.NETWORK

            else -> ErrorKind.OTHER
        }
        return kind to http
    }

    /**
     * HLS ohne ".m3u8"-Endung (z. B. "…/play?id=1&type=m3u8" oder "…/hls/…") wird sonst als TS-Datei
     * gelesen und scheitert. Klare Hinweise in der Adresse erzwingen deshalb HLS.
     */
    fun mimeTypeFor(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.substringBefore('?').endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            "m3u8" in lower || "/hls/" in lower || "output=hls" in lower -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
    }

    fun codecName(format: Format?): String? {
        val mime = format?.sampleMimeType ?: return null
        return when (mime) {
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/MP3"
            MimeTypes.AUDIO_OPUS -> "Opus"
            else -> mime.substringAfter('/').uppercase()
        }
    }

    /** Spuren dieses Typs vorhanden, aber keine davon abspielbar (z. B. DTS-Ton, HEVC ohne Decoder). */
    fun unplayable(tracks: Tracks, type: Int): Boolean {
        val groups = tracks.groups.filter { it.type == type }
        return groups.isNotEmpty() && groups.none { it.isSupported }
    }

    /** "Deutsch · 5.1" – Beschriftung einer Ton- oder Untertitelspur. */
    fun labelFor(format: Format, index: Int): String {
        val language = format.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.GERMAN) }
        val channels = if (format.channelCount >= 6) "5.1" else null
        return listOfNotNull(format.label, language, channels).distinct().joinToString(" · ").ifBlank { "Spur ${index + 1}" }
    }

    fun bitrateOf(format: Format?): Int? =
        listOfNotNull(format?.bitrate, format?.averageBitrate, format?.peakBitrate).firstOrNull { it != Format.NO_VALUE && it > 0 }

    fun known(value: Int?): Int? = value?.takeIf { it != Format.NO_VALUE && it > 0 }

    fun known(value: Float?): Float? = value?.takeIf { it != Format.NO_VALUE.toFloat() && it > 0f }

    /** Zählt alle übers Netz geladenen Bytes (läuft auf den Lade-Threads von ExoPlayer). */
    class ByteCounter : TransferListener {
        val bytes = AtomicLong(0)

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytes.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    const val TIME_UNSET = C.TIME_UNSET
}

/** Abstand zum Live-Rand je Puffer-Profil; „Stabil“ behält die Vorgabe des Streams. */
internal fun liveTargetOffsetMs(profile: BufferProfile): Long? = when (profile) {
    BufferProfile.FAST -> 6_000L
    BufferProfile.STANDARD -> 10_000L
    BufferProfile.STABLE -> null
}
