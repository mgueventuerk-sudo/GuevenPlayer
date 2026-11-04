package com.mg.player.data.recording

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aufnahme verschlüsselter HLS-Streams (AES-128) und ohne doppelte Segmente bei wechselnden Token-Adressen. */
class HlsRecordingTest {
    private val server = MockWebServer()
    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    private val segments = (1..3).map { n -> ByteArray(188 * 20) { (n * 31 + it).toByte() } }
    private var fetches = 0

    @After
    fun tearDown() = server.shutdown()

    private fun encrypt(data: ByteArray, sequence: Long): ByteArray {
        val iv = ByteBuffer.allocate(16).putLong(0).putLong(sequence).array()
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)
    }

    @Test
    fun parsesKeysSequencesAndIv() {
        val p = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000000000000000000000000000000FF
            #EXTINF:4,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            b.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        assertEquals(listOf(100L, 101L), p.segments.map { it.sequence })
        assertEquals("key.bin", p.segments[0].key?.uri)
        assertEquals(0xFF.toByte(), p.segments[0].key?.iv?.last())
        assertNull(p.segments[1].key)
        assertTrue(p.endList)
        assertEquals(4.0, p.targetDurationSec, 0.0)
    }

    @Test
    fun encryptedStreamIsRecordedDecryptedWithoutDuplicates() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/live.m3u8" -> {
                        fetches++
                        // Erste Abrufe: Segmente 1–2, danach 2–3 (mit neuen Token-Adressen) und Ende.
                        val seq = if (fetches <= 2) listOf(1, 2) else listOf(2, 3)
                        val body = buildString {
                            append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:${seq.first()}\n")
                            append("#EXT-X-KEY:METHOD=AES-128,URI=\"/key\"\n")
                            seq.forEach { append("#EXTINF:2,\n/seg$it.ts?token=$fetches\n") }
                            if (fetches > 2) append("#EXT-X-ENDLIST\n")
                        }
                        MockResponse().setBody(body)
                    }

                    path == "/key" -> MockResponse().setBody(Buffer().write(key))

                    path.startsWith("/seg") -> {
                        val n = path.removePrefix("/seg").removeSuffix(".ts").toInt()
                        MockResponse().setBody(Buffer().write(encrypt(segments[n - 1], n.toLong())))
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val file = File.createTempFile("rec", ".ts")
        val recorder = StreamRecorder(OkHttpClient())
        val total = recorder.record(
            server.url("/live.m3u8").toString(),
            file,
            System.currentTimeMillis() + 6_000,
            RecordingSession("r", "c", allowInsecureHttp = true),
            { true },
        ) {}
        val expected = segments.fold(ByteArray(0)) { acc, b -> acc + b }
        assertEquals(expected.size.toLong(), total)
        assertArrayEquals(expected, file.readBytes())
        file.delete()
    }

    /** Falscher Link oder böser Server: eine 5-MB-"Playlist" darf nicht komplett in den Speicher (früher body.string()). */
    @Test
    fun oversizedPlaylistIsRejected() = runBlocking<Unit> {
        val padding = "#X-PAD:" + "a".repeat(1_000) + "\n"
        val body = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\n/seg1.ts\n")
            repeat(5 * 1024) { append(padding) }
            append("#EXT-X-ENDLIST\n")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/big.m3u8" -> MockResponse().setBody(body)
                "/seg1.ts" -> MockResponse().setBody(Buffer().write(segments[0]))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val file = File.createTempFile("rec", ".ts")
        val until = System.currentTimeMillis() + 2_000
        val total = StreamRecorder(OkHttpClient()).record(
            server.url("/big.m3u8").toString(),
            file,
            until,
            RecordingSession("r", "c", allowInsecureHttp = true),
            { true },
        ) {}
        assertEquals("Kein Segment aus einer übergroßen Playlist", 0L, total)
        file.delete()
    }

    @Test
    fun deniesHttpRecordingWithoutPlaylistOptIn() = runBlocking<Unit> {
        server.start()
        val file = File.createTempFile("rec", ".ts")
        try {
            StreamRecorder(OkHttpClient()).record(
                server.url("/live.ts").toString(),
                file,
                System.currentTimeMillis() + 5_000,
                RecordingSession("r", "c"),
                { true },
            ) {}
            fail("HTTP-Aufnahme hätte ohne Zugangsschalter abgebrochen werden müssen")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("HTTP für diesen IPTV-Zugang nicht freigegeben"))
        } finally {
            file.delete()
        }
    }
}
