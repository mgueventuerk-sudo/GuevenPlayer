package com.mg.player.ui.player

import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import com.mg.player.player.engine.VideoPlayerEngine
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.VideoAmbient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Bindet die Video-Ausgabe einer Engine in Compose ein. Wechselt die Engine,
 * wird die View neu erzeugt und die alte sauber abgekoppelt.
 *
 * [ambient] = Ambient-Licht: Das Bild wird regelmäßig stark verkleinert gelesen und färbt den App-Hintergrund.
 * [onContainer] meldet die Fläche (z. B. für Standbilder im Mosaik): (Fläche, true) beim Anlegen, (Fläche, false)
 * beim Abbau – mit der Fläche, damit ein später Abbau der alten nicht die neue überschreibt.
 */
@Composable
fun EngineSurface(
    engine: VideoPlayerEngine,
    modifier: Modifier = Modifier,
    ambient: Boolean = false,
    onContainer: ((FrameLayout, Boolean) -> Unit)? = null,
) {
    key(engine) {
        val holder = remember { ContainerHolder() }
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    engine.attachSurface(this)
                    holder.view = this
                    onContainer?.invoke(this, true)
                }
            },
            onRelease = { container ->
                holder.view = null
                onContainer?.invoke(container, false)
                engine.detachSurface(container)
            },
            modifier = modifier,
        )
        if (ambient && !AppColors.reducedEffects) AmbientSampler(holder)
    }
}

private class ContainerHolder {
    var view: FrameLayout? = null
}

/** Abstand zwischen zwei Messungen: ruhig genug für TV-Sticks, schnell genug für Szenenwechsel. */
private const val AMBIENT_SAMPLE_MS = 1_200L
private const val AMBIENT_WIDTH = 32
private const val AMBIENT_HEIGHT = 18

@Composable
private fun AmbientSampler(holder: ContainerHolder) {
    DisposableEffect(holder) { onDispose { AppColors.videoAmbient = null } }
    LaunchedEffect(holder) {
        // Kein recycle(): Eine noch laufende PixelCopy-Anfrage dürfte sonst in ein freigegebenes Bitmap schreiben.
        val bitmap = createBitmap(AMBIENT_WIDTH, AMBIENT_HEIGHT)
        val pixels = IntArray(AMBIENT_WIDTH * AMBIENT_HEIGHT)
        while (isActive) {
            delay(AMBIENT_SAMPLE_MS)
            val target = holder.view?.let(VideoFrames::findVideoView) ?: continue
            if (!VideoFrames.copy(target, bitmap)) continue
            bitmap.getPixels(pixels, 0, AMBIENT_WIDTH, 0, 0, AMBIENT_WIDTH, AMBIENT_HEIGHT)
            val dark = AppColors.isDark
            AppColors.videoAmbient = AmbientColors.extract(pixels, AMBIENT_WIDTH, AMBIENT_HEIGHT)?.let { (left, right) ->
                VideoAmbient(
                    left = androidx.compose.ui.graphics.Color(AmbientColors.tame(left, dark)),
                    right = androidx.compose.ui.graphics.Color(AmbientColors.tame(right, dark)),
                )
            }
        }
    }
}
