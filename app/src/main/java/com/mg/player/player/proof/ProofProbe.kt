package com.mg.player.player.proof

import android.graphics.Bitmap
import android.graphics.ColorSpace
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
import java.util.concurrent.atomic.AtomicReference
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

    /** null = Sender lief nicht an (Fehler, kein Bild in [START_TIMEOUT_MS]). */
    suspend fun look(request: StreamRequest): ProofLook? = withContext(Dispatchers.Main) {
        val reader = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) newHardwareReader()
            else ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, MAX_IMAGES)
        }.getOrElse { error ->
            AppLog.log("Sport", "Bildbeweis: Bildausgabe nicht verfügbar (${error.javaClass.simpleName})")
            return@withContext null
        }
        val engine = engines.create(PlayerEngineType.MEDIA3) as? Media3Engine ?: run {
            reader.close()
            return@withContext null
        }
        val latestImage = AtomicReference<Image?>()
        val frames = ArrayList<Bitmap>()
        try {
            listenForFrames(reader, latestImage)
            engine.setMuted(true)
            engine.prepare(request.copy(isLive = true, liveCushionMs = 0, autoPlay = true))
            engine.setOffscreenSurface(reader.surface)
            if (!awaitPlaying(engine)) return@withContext null
            repeat(FRAMES) {
                delay(GAP_MS)
                captureFrame(reader, latestImage)?.let(frames::add)
            }
            if (frames.isEmpty()) return@withContext null
            val grays = withContext(Dispatchers.Default) {
                frames.map { frame -> frame.scale(DIFF_W, DIFF_H).let { small -> FrameDiff.gray(pixels(small)).also { small.recycle() } } }
            }
            // Mit Frist: Hängt die Texterkennung, bliebe sonst die Verbindung des Zugangs (und die Suchrunde) belegt.
            val texts = if (readText) withTimeoutOrNull(OCR_TIMEOUT_MS) { recognize(frames.last()) }.orEmpty() else emptyList()
            ProofLook(texts, FrameDiff.isStatic(grays))
        } finally {
            engine.setOffscreenSurface(null)
            engine.release()
            reader.setOnImageAvailableListener(null, null)
            latestImage.getAndSet(null)?.close()
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

    /** Neueste Aufnahme als Bitmap (Zeilenabstand des Puffers berücksichtigt). */
    private fun grab(reader: ImageReader): Bitmap? {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixels = image.use {
            val plane = it.planes.firstOrNull() ?: return@use null
            RgbaPlaneDecoder.pixels(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
        } ?: return null
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun listenForFrames(reader: ImageReader, latestImage: AtomicReference<Image?>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Immer das jüngste Bild behalten, damit der Decoder nicht zwischen zwei Proben stehen bleibt.
            reader.setOnImageAvailableListener({ source ->
                val next = try {
                    source.acquireLatestImage()
                } catch (_: IllegalStateException) {
                    return@setOnImageAvailableListener
                } ?: return@setOnImageAvailableListener
                latestImage.getAndSet(next)?.close()
            }, mainHandler)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Auf Android 8–9 werden die Bilder verworfen; PixelCopy liest die Surface.
            reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.close() }, mainHandler)
        }
    }

    private suspend fun captureFrame(reader: ImageReader, latestImage: AtomicReference<Image?>): Bitmap? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> withContext(Dispatchers.Default) { grabHardware(latestImage.getAndSet(null)) }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> copySurface(reader)
        else -> withContext(Dispatchers.Default) { grab(reader) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun newHardwareReader(): ImageReader = ImageReader.newInstance(
        WIDTH, HEIGHT, PixelFormat.RGBA_8888, HARDWARE_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
    )

    /** Übernimmt ein Hardware-Bild als Software-Bitmap, ohne DirectByteBuffer oder copyPixelsFromBuffer. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun grabHardware(image: Image?): Bitmap? = image?.use { frame ->
        frame.hardwareBuffer?.use { buffer ->
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)) ?: return@use null
            try {
                wrapped.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                wrapped.recycle()
            }
        }
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
        private const val HARDWARE_IMAGES = 3
        private const val FRAMES = 3
        const val GAP_MS = 2_500L
        private const val START_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 200L
        private const val OCR_TIMEOUT_MS = 10_000L
        private const val DIFF_W = 32
        private const val DIFF_H = 18
    }
}
