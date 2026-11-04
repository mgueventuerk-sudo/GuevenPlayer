package com.mg.player.player.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.view.Display
import com.mg.player.core.log.AppLog

/**
 * Was das Gerät an Video wirklich darstellen kann. Viele 1080p-Sticks haben weder einen 4K-Decoder noch einen
 * 4K-Bildschirm – startet trotzdem zuerst die 4K-Variante eines Senders, gibt es Ruckeln, einen verzögerten
 * Start über den Quellenwechsel oder sogar nur Ton ohne Bild.
 */
object DeviceVideo {

    @Volatile
    private var cached: Boolean? = null

    /** true, wenn Bildschirm (ein Anzeigemodus mit ≥ 2160 Zeilen) und ein H.264/HEVC-Decoder 4K schaffen. */
    fun supports4K(context: Context): Boolean = cached ?: compute(context.applicationContext).also { cached = it }

    private fun compute(context: Context): Boolean = runCatching {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val maxLines = display?.supportedModes?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
        if (maxLines < UHD_LINES) return@runCatching false
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .any { info ->
                info.supportedTypes.any { type ->
                    (type == "video/hevc" || type == "video/avc") &&
                        info.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(3840, 2160) == true
                }
            }
    }.onFailure { AppLog.log("Gerät", "4K-Prüfung fehlgeschlagen: ${it.javaClass.simpleName}") }
        // Im Zweifel nichts herabstufen: Das alte Verhalten (beste Qualität zuerst) bleibt.
        .getOrDefault(true)

    private const val UHD_LINES = 2000
}

/**
 * Reihenfolge der Stream-Varianten eines Senders: zuerst die in dieser Sitzung bewährte, dann alles, was das
 * Gerät darstellen kann (beste Qualität zuerst), zuletzt Varianten über der Gerätefähigkeit (4K/8K auf 1080p).
 */
object VariantOrder {
    private val beyondFullHd = setOf("4K", "8K")

    fun <T> order(items: List<T>, id: (T) -> String, quality: (T) -> String?, rememberedId: String?, supports4K: Boolean): List<T> =
        items.withIndex().sortedWith(
            compareBy<IndexedValue<T>> { if (id(it.value) == rememberedId) 0 else 1 }
                .thenBy { if (!supports4K && quality(it.value) in beyondFullHd) 1 else 0 }
                .thenBy { it.index },
        ).map { it.value }
}
