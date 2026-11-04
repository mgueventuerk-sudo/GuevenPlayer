package com.mg.player.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Liest das aktuelle Videobild einer Engine-Fläche (Media3, LibVLC und System-Player zeichnen in eine SurfaceView,
 * manche Geräte in eine TextureView). Das Bild wird von der GPU direkt in die Größe von [Bitmap] skaliert – für
 * Ambient-Licht und Mosaik reicht ein kleines Bild, das kostet kaum Zeit.
 *
 * Geschützte Inhalte (DRM, Secure-Surface) und Tunneling liefern kein Bild: Dann kommt false zurück, nie ein Absturz.
 */
internal object VideoFrames {

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Erste sichtbare Video-Fläche unterhalb von [root] (Tiefensuche), sonst null. */
    fun findVideoView(root: View): View? {
        if (!root.isVisible || root.width == 0 || root.height == 0) return null
        if (root is SurfaceView || root is TextureView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findVideoView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Kopiert das aktuelle Bild von [view] skaliert nach [into]. Aufruf auf dem Main-Thread. */
    suspend fun copy(view: View, into: Bitmap): Boolean = when (view) {
        is TextureView -> view.isAvailable && runCatching { view.getBitmap(into) }.isSuccess
        is SurfaceView -> copySurface(view, into)
        else -> false
    }

    private suspend fun copySurface(view: SurfaceView, into: Bitmap): Boolean {
        val surface = view.holder.surface
        if (surface == null || !surface.isValid) return false
        return suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(view, into, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, main)
            }.onFailure {
                // IllegalArgumentException: Surface wurde zwischen Prüfung und Anfrage ungültig (Senderwechsel, Abbau).
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
