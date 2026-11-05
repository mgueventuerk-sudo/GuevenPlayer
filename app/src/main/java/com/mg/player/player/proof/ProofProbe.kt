package com.mg.player.player.proof

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mg.player.data.sport.proof.FrameDiff
import com.mg.player.data.sport.proof.ProofLook
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.Media3Engine
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.PlayerEngineFactory
import com.mg.player.player.engine.StreamRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Sieht sich einen Sender kurz an, ohne ihn zu zeigen (Plan „Spiel-Finder“ C): Media3 spielt stumm in einen ImageReader,
 * drei Aufnahmen im Abstand von [GAP_MS] ergeben „Standbild ja/nein“, die Texterkennung (ML Kit, auf dem Gerät) liest
 * die schärfste Aufnahme. Kein Bild wird gespeichert oder verschickt. Eine Verbindung des Zugangs ist nur während des
 * Blicks belegt (etwa [GAP_MS] × 3 + Start) und wird danach sofort freigegeben.
 */
class ProofProbe(private val engines: PlayerEngineFactory, private val readText: Boolean = true) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** null = Sender lief nicht an (Fehler, kein Bild in [START_TIMEOUT_MS]). */
    suspend fun look(request: StreamRequest): ProofLook? = withContext(Dispatchers.Main) {
        val engine = engines.create(PlayerEngineType.MEDIA3) as? Media3Engine ?: return@withContext null
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, MAX_IMAGES)
        try {
            engine.setMuted(true)
            engine.prepare(request.copy(isLive = true, liveCushionMs = 0, autoPlay = true))
            engine.setOffscreenSurface(reader.surface)
            if (!awaitPlaying(engine)) return@withContext null
            val frames = ArrayList<Bitmap>()
            repeat(FRAMES) {
                delay(GAP_MS)
                grab(reader)?.let(frames::add)
            }
            if (frames.isEmpty()) return@withContext null
            val grays = frames.map { FrameDiff.gray(pixels(it.scale(DIFF_W, DIFF_H))) }
            val texts = if (readText) recognize(frames.last()) else emptyList()
            ProofLook(texts, FrameDiff.isStatic(grays))
        } finally {
            engine.setOffscreenSurface(null)
            engine.release()
            reader.close()
        }
    }

    private suspend fun awaitPlaying(engine: Media3Engine): Boolean = withTimeoutOrNull(START_TIMEOUT_MS) {
        var result: Boolean? = null
        while (result == null) {
            val state = engine.state.value
            result = when {
                state.status == PlaybackStatus.ERROR -> false
                state.status == PlaybackStatus.READY && state.isPlaying -> true
                else -> null
            }
            if (result == null) delay(POLL_MS)
        }
        result
    } == true

    /** Neueste Aufnahme als Bitmap (Zeilenabstand des Puffers berücksichtigt). */
    private fun grab(reader: ImageReader): Bitmap? {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return null
        return image.use {
            val plane = it.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * it.width
            val padded = createBitmap(it.width + rowPadding / plane.pixelStride, it.height)
            padded.copyPixelsFromBuffer(plane.buffer)
            Bitmap.createBitmap(padded, 0, 0, it.width, it.height)
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private suspend fun recognize(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result.textBlocks.flatMap { b -> b.lines.map { it.text } }) }
            .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
    }

    companion object {
        /** Groß genug für die Spielstand-Einblendung, klein genug für Fire-TV-Sticks. */
        private const val WIDTH = 960
        private const val HEIGHT = 540
        private const val MAX_IMAGES = 2
        private const val FRAMES = 3
        const val GAP_MS = 2_500L
        private const val START_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 200L
        private const val DIFF_W = 32
        private const val DIFF_H = 18
    }
}
