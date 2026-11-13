package com.mg.player.player.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fehlerarten und Ursachen aus Media3-Fehlern – Grundlage für die Maßnahmen des Wiedergabe-Wächters. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@OptIn(UnstableApi::class)
class Media3ErrorClassificationTest {

    private fun http(code: Int, headers: Map<String, List<String>> = emptyMap()): PlaybackException {
        val spec = DataSpec(Uri.parse("http://anbieter.example/live/1.ts"))
        val cause = HttpDataSource.InvalidResponseCodeException(code, "x", null, headers, spec, ByteArray(0))
        return PlaybackException("HTTP $code", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    private fun code(errorCode: Int) = PlaybackException("x", null, errorCode)

    @Test
    fun httpStatusMapsToKindAndCause() {
        assertEquals(ErrorKind.AUTH to 403, Media3Support.classify(http(403)))
        assertEquals(ErrorKind.NOT_FOUND to 404, Media3Support.classify(http(404)))
        assertEquals(ErrorKind.NETWORK, Media3Support.classify(http(503)).first)
        assertEquals(ErrorCause.SERVER, Media3Support.cause(http(503)))
        assertEquals(ErrorCause.RATE_LIMITED, Media3Support.cause(http(429)))
    }

    @Test
    fun retryAfterIsReadInSecondsAndBounded() {
        assertEquals(17L, Media3Support.retryAfterSec(http(429, mapOf("Retry-After" to listOf("17")))))
        assertEquals(5L, Media3Support.retryAfterSec(http(429, mapOf("retry-after" to listOf(" 5 ")))))
        assertNull(Media3Support.retryAfterSec(http(429, mapOf("Retry-After" to listOf("Wed, 21 Oct 2026 07:28:00 GMT")))))
        assertNull(Media3Support.retryAfterSec(http(429, mapOf("Retry-After" to listOf("999999")))))
        assertNull(Media3Support.retryAfterSec(http(429)))
    }

    @Test
    fun decoderAndAudioOutputErrorsAreFormatWithSpecificCause() {
        val decoder = code(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertEquals(ErrorKind.FORMAT, Media3Support.classify(decoder).first)
        assertEquals(ErrorCause.DECODER, Media3Support.cause(decoder))
        val audio = code(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
        assertEquals(ErrorKind.FORMAT, Media3Support.classify(audio).first)
        assertEquals(ErrorCause.AUDIO_OUTPUT, Media3Support.cause(audio))
    }

    /** Regression: "Haysiyet", 13.11. - ein kaputtes Manifest landete als OTHER statt FORMAT und löste nie
     *  den LibVLC-Fallback aus, sondern vier sinnlose Neuverbindungen. */
    @Test
    fun malformedOrUnsupportedManifestsAreFormatNotOther() {
        assertEquals(ErrorKind.FORMAT, Media3Support.classify(code(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)).first)
        assertEquals(ErrorKind.FORMAT, Media3Support.classify(code(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)).first)
    }

    @Test
    fun timeoutsAndConnectionFailuresAreNetwork() {
        val timeout = code(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(ErrorKind.NETWORK, Media3Support.classify(timeout).first)
        assertEquals(ErrorCause.TIMEOUT, Media3Support.cause(timeout))
        assertEquals(ErrorCause.CONNECTION, Media3Support.cause(code(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertEquals(ErrorCause.CONTAINER, Media3Support.cause(code(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)))
    }
}
