package com.mg.player.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.mg.player.core.image.ImagePrefetcher
import com.mg.player.core.image.PrefetchItems
import com.mg.player.core.image.PrefetchKind
import com.mg.player.core.image.Visible
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Erst nach so langer Ruhe der Liste wird vorgeladen – beim Durchblättern mit der Fernbedienung stört es sonst das Scrollen. */
private const val IDLE_MS = 400L

/** Raster (Filme, Serien): Bilder der nächsten Kacheln vorladen, sobald das Raster ruht. */
@Composable
fun PrefetchAhead(state: LazyGridState, kind: PrefetchKind, itemCount: () -> Int, urlAt: (Int) -> String?, imageSize: () -> IntSize? = { null }) {
    PrefetchAhead(
        visible = { state.layoutInfo.visibleItemsInfo.let { Visible(it.firstOrNull()?.index ?: -1, it.lastOrNull()?.index ?: -1) } },
        scrolling = { state.isScrollInProgress },
        kind = kind,
        itemCount = itemCount,
        urlAt = urlAt,
        imageSize = imageSize,
    )
}

/** Liste (Senderliste): Logos der nächsten Zeilen vorladen, sobald die Liste ruht. */
@Composable
fun PrefetchAhead(state: LazyListState, kind: PrefetchKind, itemCount: () -> Int, urlAt: (Int) -> String?) {
    PrefetchAhead(
        visible = { state.layoutInfo.visibleItemsInfo.let { Visible(it.firstOrNull()?.index ?: -1, it.lastOrNull()?.index ?: -1) } },
        scrolling = { state.isScrollInProgress },
        kind = kind,
        itemCount = itemCount,
        urlAt = urlAt,
    )
}

@Composable
private fun PrefetchAhead(
    visible: () -> Visible,
    scrolling: () -> Boolean,
    kind: PrefetchKind,
    itemCount: () -> Int,
    urlAt: (Int) -> String?,
    imageSize: () -> IntSize? = { null },
) {
    val context = LocalContext.current
    val currentCount by rememberUpdatedState(itemCount)
    val currentUrlAt by rememberUpdatedState(urlAt)
    val currentSize by rememberUpdatedState(imageSize)
    DisposableEffect(kind) {
        ImagePrefetcher.cancelSweep()
        // Liste verlassen: Was vorgeladen und nie gezeigt wurde, wird nach einer Weile freigegeben (kommt man schnell zurück, ist es noch da).
        // Anwendungs-Kontext: Der Aufräumlauf wartet zwei Minuten und soll die Activity nicht so lange festhalten.
        onDispose { ImagePrefetcher.scheduleSweep(context.applicationContext) }
    }
    LaunchedEffect(kind) {
        snapshotFlow { visible() to scrolling() }.distinctUntilChanged().collectLatest { (view, moving) ->
            if (moving || view.last < 0) return@collectLatest
            delay(IDLE_MS)
            ImagePrefetcher.prefetch(context, kind, view, PrefetchItems(currentCount(), currentUrlAt), currentSize())
        }
    }
}
