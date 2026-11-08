package com.mg.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prüft PixelCopy unabhängig vom Decoder mit einem bis zum Callback gültigen roten Bild. */
@RunWith(AndroidJUnit4::class)
class ProofPixelCopyControlTest {
    @Test
    fun copiesRedFrameWhileImageRemainsAcquired() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val handler = Handler(Looper.getMainLooper())
        val reader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        } else {
            ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2)
        }
        val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val held = AtomicReference<Image?>()
        val available = CountDownLatch(1)
        val copied = CountDownLatch(1)
        var status = -1
        try {
            reader.setOnImageAvailableListener({ source ->
                held.getAndSet(source.acquireLatestImage())?.close()
                available.countDown()
            }, handler)
            val canvas = reader.surface.lockCanvas(null)
            canvas.drawColor(Color.RED)
            reader.surface.unlockCanvasAndPost(canvas)
            assertTrue("ImageReader erhielt kein Kontrollbild", available.await(10, TimeUnit.SECONDS))
            assertTrue("Kontrollbild fehlt", held.get() != null)
            PixelCopy.request(reader.surface, null, frame, { result ->
                status = result
                copied.countDown()
            }, handler)
            assertTrue("PixelCopy antwortete nicht", copied.await(10, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, status)
            assertEquals(Color.RED, frame.getPixel(32, 32))
        } finally {
            reader.setOnImageAvailableListener(null, null)
            held.getAndSet(null)?.close()
            reader.close()
            // Bei einem ausstehenden nativen Kopiervorgang darf die Bitmap nicht recycelt werden.
            if (copied.count == 0L) frame.recycle()
        }
    }
}
