package com.mg.player.ui.common

import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.util.concurrent.atomic.AtomicInteger

/**
 * Misst, woher die **angezeigten** Bilder kommen (Arbeitsspeicher, Festplatte, Netz) – das ist der Nachweis, ob Vorladen
 * etwas bringt: Mehr Treffer im Arbeitsspeicher und weniger aus dem Netz bei gleicher Nutzung. Meldet außerdem
 * [ImagePrefetcher], wenn ein vorgeladenes Bild gezeigt wurde (dann wird es nicht freigegeben). Kostet je Bild zwei Zähler;
 * eigene Vorlade-Anfragen werden nicht mitgezählt.
 */
internal object ImageUsage : Interceptor {
    private val fromMemory = AtomicInteger()
    private val fromDisk = AtomicInteger()
    private val fromNetwork = AtomicInteger()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        val url = chain.request.data as? String
        if (result is SuccessResult && !(url != null && ImagePrefetcher.isPrefetching(url))) record(result)
        return result
    }

    private fun record(result: SuccessResult) {
        when (result.dataSource) {
            DataSource.NETWORK -> fromNetwork.incrementAndGet()

            DataSource.DISK -> fromDisk.incrementAndGet()

            // Arbeitsspeicher (MEMORY_CACHE, MEMORY): War es ein von uns vorgeladenes Bild, war das Vorladen nicht umsonst.
            else -> {
                fromMemory.incrementAndGet()
                ImagePrefetcher.onShownFromMemory(result.memoryCacheKey)
            }
        }
    }

    /** „angezeigt: Speicher a, Platte b, Netz c“ seit dem letzten Aufruf (setzt die Zähler zurück). */
    fun takeSummary(): String = "angezeigt seit zuletzt: Speicher ${fromMemory.getAndSet(0)}, Platte ${fromDisk.getAndSet(0)}, Netz ${fromNetwork.getAndSet(0)}"
}
