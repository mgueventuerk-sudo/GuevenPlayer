package com.mg.player.ui.common

import android.content.Context
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
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowRgb565
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.device.PreloadBudget
import com.mg.player.core.device.PreloadPolicy
import com.mg.player.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Welche Bilder die nächsten Einträge haben: Poster (undurchsichtig) oder Senderlogos (oft transparent). */
enum class PrefetchKind(val width: Int, val height: Int, val rgb565: Boolean) {
    POSTER(180, 270, rgb565 = true),
    LOGO(160, 120, rgb565 = false),
}

/**
 * Lädt die Bilder der nächsten Einträge vor, bevor man dort ankommt:
 * - **Immer** (solange [PreloadPolicy] es erlaubt) in den Datei-Cache: kostet keinen Arbeitsspeicher und spart das Warten
 *   auf den Server.
 * - **Bei genug freiem Speicher** ([PreloadBudget.warmMemory]) zusätzlich fertig dekodiert in den Arbeitsspeicher, in genau der
 *   Größe, in der die Anzeige sie braucht ([ImageRequests]) – dann steht das Bild beim Ankommen ohne Dekodieren da.
 *
 * **Freigabe** (siehe [PrefetchLedger]): Was wir in den Speicher gelegt haben und nie gezeigt wurde, geht wieder raus, wenn es
 * (1) außerhalb des Fensters um den sichtbaren Bereich liegt, (2) länger als [UNUSED_TTL_MS] ungenutzt liegt (z. B. Liste
 * verlassen), oder (3) der Speicher knapp wird. Was die Anzeige gezeigt hat, bleibt dem normalen Cache überlassen.
 *
 * Dekodiert wird nur, wenn die Liste ruht (siehe [PrefetchAhead]), höchstens zwei Bilder gleichzeitig.
 */
internal object ImagePrefetcher {
    private val gate = Semaphore(PARALLEL)
    private val diskDone = history()
    private val memoryDone = history()

    private fun history() = object : LinkedHashMap<String, Unit>(HISTORY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > HISTORY
    }
    private val ledger = PrefetchLedger<MemoryCache.Key>()
    private val inFlight = HashSet<String>()
    private val sweeper = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sweepJob: Job? = null
    private var lastLogMs = 0L
    private val warmed = AtomicInteger()
    private val released = AtomicInteger()
    private val used = AtomicInteger()

    @Synchronized
    private fun diskKnown(url: String) = diskDone.containsKey(url)

    @Synchronized
    private fun markDisk(url: String) {
        diskDone[url] = Unit
    }

    @Synchronized
    private fun memoryKnown(url: String) = memoryDone.containsKey(url)

    @Synchronized
    private fun markMemory(url: String) {
        memoryDone[url] = Unit
    }

    /** Freigegebene Bilder dürfen später wieder vorgeladen werden. */
    @Synchronized
    private fun forgetMemory(urls: List<String>) {
        urls.forEach(memoryDone::remove)
    }

    @Synchronized
    private fun begin(url: String) = inFlight.add(url)

    @Synchronized
    private fun end(url: String) {
        inFlight.remove(url)
    }

    /** Die Anzeige hat ein Bild aus dem Arbeitsspeicher gezeigt: War es eins von uns, war das Vorladen nicht umsonst. */
    fun onShownFromMemory(key: MemoryCache.Key?) {
        if (key != null && ledger.markUsed(key)) used.incrementAndGet()
    }

    /** Läuft gerade unser eigenes Vorladen für diese Adresse? Dann zählt die Anfrage nicht als „angezeigt“. */
    fun isPrefetching(url: String): Boolean = synchronized(this) { url in inFlight }

    /** Speicherwarnung: Das ganze Bild-Cache wurde geleert, unser Buch ist hinfällig. */
    fun forgetAll() {
        forgetMemory(ledger.all().map { it.url })
        synchronized(this) { memoryDone.clear() }
    }

    suspend fun prefetch(context: Context, kind: PrefetchKind, view: Visible, itemCount: Int, urlAt: (Int) -> String?, posterSize: IntSize?) {
        val loader = SingletonImageLoader.get(context)
        // Misst auch den freien Platz im Dateisystem: nicht im Hauptthread.
        val budget = withContext(Dispatchers.IO) { PreloadPolicy.current(context) }
        if (!budget.active) {
            release(loader.memoryCache, ledger.all(), "Speicher knapp oder Vorladen aus")
            return
        }
        val reach = if (budget.warmMemory) maxOf(budget.imagesAhead, 0) else 0
        val wanted = PrefetchPlan.window(view.first, view.last, itemCount, budget.keepBehind, reach, urlAt)
        release(loader.memoryCache, ledger.outsideWindow(wanted), "außerhalb des Fensters")
        release(loader.memoryCache, ledger.expired(UNUSED_TTL_MS), "ungenutzt abgelaufen")
        // Die nächsten Einträge (imagesAhead) kommen fertig in den Arbeitsspeicher, alles weiter voraus nur in den Datei-Cache.
        val upcoming = PrefetchPlan.ahead(view.last, itemCount, maxOf(budget.diskAhead, reach), urlAt)
        val todo = upcoming.withIndex().filter { (i, url) -> if (budget.warmMemory && i < reach) !memoryKnown(url) else !diskKnown(url) }
        if (todo.isEmpty()) return
        log(context, budget, todo.size)
        for ((i, url) in todo) {
            val memory = budget.warmMemory && i < reach && roomInMemory(loader)
            gate.withPermit { load(context, loader, kind, url, memory = memory, posterSize = posterSize) }
        }
    }

    private suspend fun load(context: Context, loader: ImageLoader, kind: PrefetchKind, url: String, memory: Boolean, posterSize: IntSize?) {
        val ahead = when (kind) {
            PrefetchKind.POSTER -> posterSize?.let { ImageRequests.posterAhead(context, url, it) }
            PrefetchKind.LOGO -> ImageRequests.channelLogoAhead(context, url)
        }
        // Ohne gemessene Kachelgröße (noch kein Poster gezeigt) bleibt es beim Datei-Cache.
        val toMemory = memory && ahead != null
        val request = (if (toMemory) ahead else diskOnly(context, kind, url)) ?: return
        if (!begin(url)) return
        try {
            val result = loader.execute(request)
            markDisk(url)
            if (toMemory && result is SuccessResult) {
                result.memoryCacheKey?.let {
                    ledger.add(it, url)
                    markMemory(url)
                    warmed.incrementAndGet()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nicht ladbar: später beim Anzeigen erneut versuchen, hier nicht merken und nicht melden.
        } finally {
            end(url)
        }
    }

    private fun diskOnly(context: Context, kind: PrefetchKind, url: String) = ImageRequest.Builder(context)
        .data(url)
        .size(kind.width, kind.height)
        .allowRgb565(kind.rgb565)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()

    /** Der Bild-Cache ist zu mehr als 85 % voll: nichts Neues hineinlegen (es verdrängte Bilder, die man schon gesehen hat). */
    private fun roomInMemory(loader: ImageLoader): Boolean {
        val cache = loader.memoryCache ?: return false
        return cache.size < cache.maxSize * FILL_LIMIT
    }

    private fun release(cache: MemoryCache?, gone: List<PrefetchLedger.Released<MemoryCache.Key>>, why: String) {
        if (gone.isEmpty()) return
        gone.forEach { cache?.remove(it.key) }
        forgetMemory(gone.map { it.url })
        released.addAndGet(gone.size)
        AppLog.debug("Vorladen", "${gone.size} ungenutzte Bilder aus dem Speicher freigegeben ($why)")
    }

    /** Nach dem Verlassen der Liste: Was dann noch ungenutzt im Speicher liegt, wird nach [UNUSED_TTL_MS] freigegeben. */
    fun scheduleSweep(context: Context) {
        sweepJob?.cancel()
        sweepJob = sweeper.launch {
            delay(UNUSED_TTL_MS + SWEEP_SLACK_MS)
            release(SingletonImageLoader.get(context).memoryCache, ledger.expired(UNUSED_TTL_MS), "Liste verlassen")
        }
    }

    /** Neue Liste auf dem Bildschirm: den geplanten Aufräumlauf der alten verwerfen. */
    fun cancelSweep() {
        sweepJob?.cancel()
    }

    /** Höchstens alle 20 s eine Zeile: Stufe, freier Speicher, Heap und was das Vorladen gebracht hat. */
    @Synchronized
    private fun log(context: Context, budget: PreloadBudget, count: Int) {
        val now = System.currentTimeMillis()
        if (now - lastLogMs < LOG_EVERY_MS) return
        lastLogMs = now
        val memory = PreloadPolicy.lastReading
        val device = DeviceTier.of(context).label
        AppLog.log(
            "Vorladen",
            "$count Bilder voraus (Stufe ${budget.level}, Gerät $device, frei ${memory?.availMb} MB, Heap frei ${memory?.heapFreeMb} MB, " +
                "Speicherplatz frei ${memory?.storageFreeMb} MB, Datei-Cache ${budget.diskAhead} voraus, " +
                "Arbeitsspeicher ${if (budget.warmMemory) budget.imagesAhead else 0} voraus); " +
                "im Speicher vorgeladen ${warmed.get()}, davon gezeigt ${used.get()}, freigegeben ${released.get()}, noch ungenutzt ${ledger.size}; " +
                ImageUsage.takeSummary(),
        )
    }

    private const val PARALLEL = 2
    private const val HISTORY = 600
    private const val LOAD_FACTOR = 0.75f
    private const val LOG_EVERY_MS = 20_000L
    private const val FILL_LIMIT = 0.85

    /** So lange darf ein vorgeladenes, nie gezeigtes Bild im Arbeitsspeicher liegen. */
    private const val UNUSED_TTL_MS = 120_000L
    private const val SWEEP_SLACK_MS = 1_000L
}

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
            ImagePrefetcher.prefetch(context, kind, view, currentCount(), currentUrlAt, currentSize())
        }
    }
}
