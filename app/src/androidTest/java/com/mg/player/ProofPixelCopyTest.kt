package com.mg.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Testet die tatsächliche Kopie von der ImageReader-Surface ohne Zugriff auf Image.Plane.buffer. */
@RunWith(AndroidJUnit4::class)
class ProofPixelCopyTest {
    @Test
    fun decodesVideoIntoPrivateReaderAndCopiesFrameOnAndroidOAndP() {
        assumeTrue(Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P)
        val handler = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(SIZE, SIZE, ImageFormat.PRIVATE, 2)
        val frame = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val available = CountDownLatch(1)
        val copied = CountDownLatch(1)
        val frameAcquired = AtomicBoolean(false)
        val acquireFailure = AtomicReference<RuntimeException?>()
        var copyResult = -1
        try {
            reader.setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.use { frameAcquired.set(true) }
                } catch (error: RuntimeException) {
                    acquireFailure.set(error)
                } finally {
                    available.countDown()
                }
            }, handler)
            decodeVideoToReader(reader, available)
            acquireFailure.get()?.let { fail("PRIVATE-ImageReader akzeptierte das Decoderformat nicht: ${it.javaClass.simpleName}") }
            assertTrue("ImageReader erhielt kein Bild", frameAcquired.get())

            PixelCopy.request(reader.surface, null, frame, { status ->
                copyResult = status
                copied.countDown()
            }, handler)
            assertTrue("PixelCopy antwortete nicht", copied.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, copyResult)
            val pixel = frame.getPixel(SIZE / 2, SIZE / 2)
            assertTrue("Videobild wurde nicht als rotes Bild kopiert: $pixel", Color.red(pixel) > Color.green(pixel) + 80)
        } finally {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            frame.recycle()
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @Test
    fun decodesVideoIntoHardwareBufferAndCopiesPixelsOnAndroidQPlus() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val reader = ImageReader.newInstance(
            SIZE,
            SIZE,
            PixelFormat.RGBA_8888,
            3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
        )
        val available = CountDownLatch(1)
        val acquiredFrame = AtomicReference<Bitmap?>()
        val acquireFailure = AtomicReference<RuntimeException?>()
        try {
            reader.setOnImageAvailableListener({ source ->
                try {
                    val image = source.acquireLatestImage()
                    if (image == null) {
                        acquireFailure.set(IllegalStateException("ImageReader lieferte kein Bild"))
                    } else {
                        image.use { frame ->
                            val buffer = frame.hardwareBuffer
                            if (buffer == null) {
                                acquireFailure.set(IllegalStateException("Decoder-Image hat keinen HardwareBuffer"))
                            } else {
                                buffer.use {
                                    val wrapped = Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB))
                                    if (wrapped == null) {
                                        acquireFailure.set(IllegalStateException("HardwareBuffer konnte nicht gelesen werden"))
                                    } else {
                                        try {
                                            acquiredFrame.set(wrapped.copy(Bitmap.Config.ARGB_8888, false))
                                        } finally {
                                            wrapped.recycle()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (error: RuntimeException) {
                    acquireFailure.set(error)
                } finally {
                    available.countDown()
                }
            }, Handler(Looper.getMainLooper()))

            decodeVideoToReader(reader, available)
            acquireFailure.get()?.let { fail("HardwareBuffer-Aufnahme des Decoderframes schlug fehl: ${it.javaClass.simpleName}") }
            val frame = requireNotNull(acquiredFrame.get()) { "Decoderframe wurde nicht kopiert" }
            val pixel = frame.getPixel(SIZE / 2, SIZE / 2)
            assertTrue("HardwareBuffer lieferte kein rotes Decoderbild: $pixel", Color.red(pixel) > Color.green(pixel) + 80)
        } finally {
            acquiredFrame.getAndSet(null)?.recycle()
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        }
    }

    @Test
    fun copiesQueuedFrameAfterImageReaderReleasesIt() {
        assumeTrue(Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P)
        val handler = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(SIZE, SIZE, PixelFormat.RGBA_8888, 2)
        val frame = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val available = CountDownLatch(1)
        val copied = CountDownLatch(1)
        var result = -1
        try {
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.close()
                available.countDown()
            }, handler)
            val canvas = reader.surface.lockCanvas(null)
            canvas.drawColor(Color.RED)
            reader.surface.unlockCanvasAndPost(canvas)
            assertTrue("ImageReader erhielt kein Bild", available.await(WAIT_SECONDS, TimeUnit.SECONDS))
            PixelCopy.request(reader.surface, null, frame, { status ->
                result = status
                copied.countDown()
            }, handler)
            assertTrue("PixelCopy antwortete nicht", copied.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            assertEquals(Color.RED, frame.getPixel(SIZE / 2, SIZE / 2))
        } finally {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            frame.recycle()
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @Test
    fun copiesHardwareFrameWithoutDirectBufferAccess() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val reader = ImageReader.newInstance(
            SIZE, SIZE, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN,
        )
        val available = CountDownLatch(1)
        try {
            reader.setOnImageAvailableListener({ available.countDown() }, Handler(Looper.getMainLooper()))
            val canvas = reader.surface.lockCanvas(null)
            canvas.drawColor(Color.RED)
            reader.surface.unlockCanvasAndPost(canvas)
            assertTrue("ImageReader erhielt kein Hardware-Bild", available.await(WAIT_SECONDS, TimeUnit.SECONDS))
            reader.setOnImageAvailableListener(null, null)
            requireNotNull(reader.acquireLatestImage()).use { image ->
                requireNotNull(image.hardwareBuffer).use { buffer ->
                    val wrapped = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))) {
                        "HardwareBuffer konnte nicht als Bitmap gelesen werden"
                    }
                    val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    try {
                        assertEquals(Color.RED, software.getPixel(SIZE / 2, SIZE / 2))
                    } finally {
                        software.recycle()
                        wrapped.recycle()
                    }
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun decodeVideoToReader(reader: ImageReader, frameAvailable: CountDownLatch) {
        val payload = Base64.decode(H264_SAMPLE_BASE64, Base64.DEFAULT)
        val decoder = MediaCodec.createDecoderByType(MIME_TYPE)
        var started = false
        try {
            decoder.configure(MediaFormat.createVideoFormat(MIME_TYPE, SIZE, SIZE), reader.surface, null, 0)
            decoder.start()
            started = true
            val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            assertTrue("Decoder nahm keinen H.264-Eingabepuffer an", inputIndex >= 0)
            requireNotNull(decoder.getInputBuffer(inputIndex)).put(payload)
            decoder.queueInputBuffer(inputIndex, 0, payload.size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (frameAvailable.count > 0 && System.nanoTime() < deadline) {
                val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, info.size > 0)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val remainingNanos = (deadline - System.nanoTime()).coerceAtLeast(0L)
            assertTrue("Video-Decoder lieferte kein Bild an ImageReader", frameAvailable.await(remainingNanos, TimeUnit.NANOSECONDS))
        } finally {
            try {
                if (started) decoder.stop()
            } finally {
                decoder.release()
            }
        }
    }

    private companion object {
        // 64x64 roter H.264-Testframe, inline statt eines zusätzlichen Asset-Files.
        private const val H264_SAMPLE_BASE64 =
            "AAAAAWdCwB7aEJsBEAAAAwAQAAADAIjxYuoAAAABaM4PyAAAAQYF//9P3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCByMzEwOCAzMWUxOWY5IC0g" +
                "SC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyMyAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6" +
                "IGNhYmFjPTAgcmVmPTEgZGVibG9jaz0wOjA6MCBhbmFseXNlPTA6MCBtZT1kaWEgc3VibWU9MCBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0w" +
                "IG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MCA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29m" +
                "ZnNldD0wIHRocmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVybGFjZWQ9MCBibHVyYXlf" +
                "Y29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0yNTAga2V5aW50X21pbj00IHNjZW5lY3V0PTAgaW50cmFf" +
                "cmVmcmVzaD0wIHJjPWNyZiBtYnRyZWU9MCBjcmY9MjMuMCBxY29tcD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0w" +
                "AIAAAAFliIQ6EYoAAhjxwABA9jgACHlJycnXXXXXXXXXXXXgAAAAAUGaIBSgjAAAAAFBmkAVoIwAAAABQZpgFaCMAAAAAUGagBWgjAAAAAFBmqAVoIwAAAAB" +
                "QZrAFaCMAAAAAUGa4BagjA=="
        const val MIME_TYPE = "video/avc"
        const val CODEC_TIMEOUT_US = 10_000L
        const val SIZE = 64
        const val WAIT_SECONDS = 10L
    }
}
