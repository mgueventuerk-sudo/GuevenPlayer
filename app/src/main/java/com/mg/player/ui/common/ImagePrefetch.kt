package com.mg.player.ui.common

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.device.PreloadBudget
import com.mg.player.core.device.PreloadPolicy
import com.mg.player.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Welche Bilder die nächsten Einträge haben: Poster (undurchsichtig) oder Senderlogos (oft transparent). */
enum class PrefetchKind(val width: Int, val height: Int, val rgb565: Boolean) {
    POSTER(180, 270, rgb565 = true),
    LOGO(160, 120, rgb565 = false),
}

/**
 * Lädt die Bilder der nächsten Einträge in den Datei-Cache von Coil, bevor man dort ankommt. Absichtlich nur auf die
 * Festplatte: Die Größe, in der ein Bild später gezeigt wird, steht beim Vorladen nicht fest – ein Treffer im
 * Arbeitsspeicher-Cache wäre Zufall, und ein voll aufgelöstes Bild kostet auf einem Stick viel Speicher. Von der Festplatte
 * statt aus dem Netz zu laden, spart trotzdem das Warten auf den Server. Dekodiert wird nur klein, höchstens zwei Bilder
 * gleichzeitig, und nur, wenn die Liste ruht (siehe [PrefetchAhead]).
 */
internal object ImagePrefetcher {
    private val gate = Semaphore(PARALLEL)
    private val done = object : LinkedHashMap<String, Unit>(HISTORY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > HISTORY
    }
    private var lastLogMs = 0L

    @Synchronized
    private fun isDone(url: String) = done.containsKey(url)

    @Synchronized
    private fun markDone(url: String) {
        done[url] = Unit
    }

    suspend fun prefetch(context: Context, kind: PrefetchKind, lastVisible: Int, itemCount: Int, urlAt: (Int) -> String?) {
        val budget = PreloadPolicy.current(context)
        if (!budget.active) return
        val urls = PrefetchPlan.upcoming(lastVisible, itemCount, budget.imagesAhead, urlAt, ::isDone)
        if (urls.isEmpty()) return
        log(context, budget, urls.size)
        val loader = SingletonImageLoader.get(context)
        for (url in urls) {
            // Jede Adresse einzeln: Wird die Liste wieder bewegt, bricht der Rest sofort ab (collectLatest).
            gate.withPermit {
                try {
                    loader.execute(request(context, kind, url))
                    markDone(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Nicht ladbar: später beim Anzeigen erneut versuchen, hier nicht merken und nicht melden.
                }
            }
        }
    }

    private fun request(context: Context, kind: PrefetchKind, url: String) = ImageRequest.Builder(context)
        .data(url)
        .size(kind.width, kind.height)
        .allowRgb565(kind.rgb565)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()

    /** Höchstens alle 20 s eine Zeile: Stufe, freier Speicher und Heap – damit sich die Grenzwerte am Gerät nachstellen lassen. */
    @Synchronized
    private fun log(context: Context, budget: PreloadBudget, count: Int) {
        val now = System.currentTimeMillis()
        if (now - lastLogMs < LOG_EVERY_MS) return
        lastLogMs = now
        val memory = PreloadPolicy.lastReading
        val device = DeviceTier.of(context).label
        AppLog.log("Vorladen", "$count Bilder voraus (Stufe ${budget.level}, Gerät $device, frei ${memory?.availMb} MB, Heap frei ${memory?.heapFreeMb} MB)")
    }

    private const val PARALLEL = 2
    private const val HISTORY = 600
    private const val LOAD_FACTOR = 0.75f
    private const val LOG_EVERY_MS = 20_000L
}

/** Erst nach so langer Ruhe der Liste wird vorgeladen – beim Durchblättern mit der Fernbedienung stört es sonst das Scrollen. */
private const val IDLE_MS = 400L

/** Raster (Filme, Serien): Bilder der nächsten Kacheln vorladen, sobald das Raster ruht. */
@Composable
fun PrefetchAhead(state: LazyGridState, kind: PrefetchKind, itemCount: () -> Int, urlAt: (Int) -> String?) {
    PrefetchAhead(
        lastVisible = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
        scrolling = { state.isScrollInProgress },
        kind = kind,
        itemCount = itemCount,
        urlAt = urlAt,
    )
}

/** Liste (Senderliste): Logos der nächsten Zeilen vorladen, sobald die Liste ruht. */
@Composable
fun PrefetchAhead(state: LazyListState, kind: PrefetchKind, itemCount: () -> Int, urlAt: (Int) -> String?) {
    PrefetchAhead(
        lastVisible = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
        scrolling = { state.isScrollInProgress },
        kind = kind,
        itemCount = itemCount,
        urlAt = urlAt,
    )
}

@Composable
private fun PrefetchAhead(lastVisible: () -> Int, scrolling: () -> Boolean, kind: PrefetchKind, itemCount: () -> Int, urlAt: (Int) -> String?) {
    val context = LocalContext.current
    val currentCount by rememberUpdatedState(itemCount)
    val currentUrlAt by rememberUpdatedState(urlAt)
    LaunchedEffect(kind) {
        snapshotFlow { lastVisible() to scrolling() }.distinctUntilChanged().collectLatest { (last, moving) ->
            if (moving || last < 0) return@collectLatest
            delay(IDLE_MS)
            ImagePrefetcher.prefetch(context, kind, last, currentCount(), currentUrlAt)
        }
    }
}
