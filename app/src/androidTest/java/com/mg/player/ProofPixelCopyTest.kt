package com.mg.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Testet die tatsächliche Kopie von der ImageReader-Surface ohne Zugriff auf Image.Plane.buffer. */
@RunWith(AndroidJUnit4::class)
class ProofPixelCopyTest {
    @Test
    fun copiesQueuedFrameAfterImageReaderReleasesIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
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

    private companion object {
        const val SIZE = 64
        const val WAIT_SECONDS = 10L
    }
}
