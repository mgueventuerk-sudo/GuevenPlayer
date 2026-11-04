package com.mg.player.ui.player

import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mg.player.player.engine.VideoPlayerEngine

/**
 * Bindet die Video-Ausgabe einer Engine in Compose ein. Wechselt die Engine,
 * wird die View neu erzeugt und die alte sauber abgekoppelt.
 */
@Composable
fun EngineSurface(engine: VideoPlayerEngine, modifier: Modifier = Modifier) {
    key(engine) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    engine.attachSurface(this)
                }
            },
            onRelease = { container -> engine.detachSurface(container) },
            modifier = modifier,
        )
    }
}
