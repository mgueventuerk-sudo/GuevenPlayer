package com.mg.player.player.proof

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mg.player.core.log.AppLog
import com.mg.player.data.sport.proof.FrameDiff
import com.mg.player.data.sport.proof.ProofLook
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.engine.Media3Engine
import com.mg.player.player.engine.PlaybackStatus
import com.mg.player.player.engine.PlayerEngineFactory
import com.mg.player.player.engine.StreamRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val imageReaderFailureLogged = AtomicBoolean(false)
    private val ocrFailureLogged = AtomicBoolean(false)

    /** null = Sender lief nicht an (Fehler, kein Bild in [START_TIMEOUT_MS]). */
    suspend fun look(request: StreamRequest): ProofLook? = withContext(Dispatchers.Main) {
        val reader = runCatching {
            // PRIVATE lässt den Decoder sein natives Surface-Format wählen. Decoder können YUV
            // ausgeben; ein RGBA-ImageReader verursacht dann beim acquireLatestImage einen Absturz.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, MAX_IMAGES, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, MAX_IMAGES)
            } else {
                ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, MAX_IMAGES)
            }
        }.getOrElse { error ->
            AppLog.log("Sport", "Bildbeweis: Bildausgabe nicht verfügbar (${error.javaClass.simpleName})")
            return@withContext null
        }
        val engine = engines.create(PlayerEngineType.MEDIA3) as? Media3Engine ?: run {
            reader.close()
            return@withContext null
        }
        val frames = ArrayList<Bitmap>()
        val latestFrame = LatestProofFrame { acquireLatestImage(reader) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reader.setOnImageAvailableListener({ _ ->
                    latestFrame.onFrame()
                }, mainHandler)
            }
            engine.setMuted(true)
            engine.prepare(request.copy(isLive = true, liveCushionMs = 0, autoPlay = true))
            engine.setOffscreenSurface(reader.surface)
            if (!awaitPlaying(engine)) return@withContext null
            repeat(FRAMES) {
                delay(GAP_MS)
                latestFrame.beginCopy()
                try {
                    captureFrame(reader, latestFrame.image)?.let(frames::add)
                } finally {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) latestFrame.finishCopy()
                }
            }
            if (frames.isEmpty()) return@withContext null
            val grays = withContext(Dispatchers.Default) {
                frames.map { frame -> frame.scale(DIFF_W, DIFF_H).let { small -> FrameDiff.gray(pixels(small)).also { small.recycle() } } }
            }
            // Mit Frist: Hängt die Texterkennung, bliebe sonst die Verbindung des Zugangs (und die Suchrunde) belegt.
            val texts = if (readText) withTimeoutOrNull(OCR_TIMEOUT_MS) { recognizeSafely(frames.last()) }.orEmpty() else emptyList()
            ProofLook(texts, FrameDiff.isStatic(grays))
        } finally {
            reader.setOnImageAvailableListener(null, null)
            latestFrame.close()
            acquireLatestImage(reader)?.close()
            engine.setOffscreenSurface(null)
            engine.release()
            reader.close()
            // Aufnahmen freigeben: 960×540 sind je 2 MB – sonst bleiben je Blick 6 MB für den Sammler liegen. Die letzte
            // ging an die Texterkennung: Nach einer Zeitüberschreitung kann ML Kit sie noch lesen, die bleibt dem Sammler.
            (if (readText) frames.dropLast(1) else frames).forEach { it.recycle() }
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

    private fun acquireLatestImage(reader: ImageReader): Image? = acquireImageSafely(
        acquire = { reader.acquireLatestImage() },
        onFailure = ::logImageReaderFailure,
    )

    private fun logImageReaderFailure(error: RuntimeException) {
        if (imageReaderFailureLogged.compareAndSet(false, true)) {
            AppLog.log("Sport", "Bildbeweis: Videobild konnte nicht ausgelesen werden (${error.javaClass.simpleName})")
        }
    }

    private suspend fun captureFrame(reader: ImageReader, image: Image?): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Das Decoder-Bild bleibt bis zum PixelCopy-Callback gültig.
        if (image != null) copySurface(reader) else null
    } else {
        withContext(Dispatchers.Default) { grab(reader) }
    }

    private fun grab(reader: ImageReader): Bitmap? {
        val image = acquireLatestImage(reader) ?: return null
        val width = image.width
        val height = image.height
        val pixels = image.use {
            val plane = it.planes.firstOrNull() ?: return@use null
            RgbaPlaneDecoder.pixels(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
        } ?: return null
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** PixelCopy benutzt keinen Java- oder nativen Zugriff auf ImageReader.Plane.buffer. */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun copySurface(reader: ImageReader): Bitmap? {
        val frame = createBitmap(WIDTH, HEIGHT)
        val copied = suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(reader.surface, null, frame, { result ->
                    if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                }, mainHandler)
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
        if (copied) return frame
        AppLog.log("Sport", "Bildbeweis: PixelCopy konnte Videobild nicht kopieren")
        frame.recycle()
        return null
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /**
     * Ein Fehler der Texterkennung darf den ganzen Blick nicht verwerfen: Die Standbild-Prüfung (Platzhalter) braucht
     * keinen Text, und ohne diese Kapselung scheiterte jede Runde wie auf Geräten ohne Texterkennung
     * ("Bildbeweis: NullPointerException", 9× in 10 min, Protokoll 12.11.). Einmal mit Stelle protokollieren.
     */
    private suspend fun recognizeSafely(bitmap: Bitmap): List<String> = try {
        recognize(bitmap)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (ocrFailureLogged.compareAndSet(false, true)) AppLog.error("Sport", "Bildbeweis: Texterkennung", e)
        emptyList()
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
        private const val MAX_IMAGES = 3
        private const val FRAMES = 3
        const val GAP_MS = 2_500L
        private const val START_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 200L
        private const val OCR_TIMEOUT_MS = 10_000L
        private const val DIFF_W = 32
        private const val DIFF_H = 18
    }
}
