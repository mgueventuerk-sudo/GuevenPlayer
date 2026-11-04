package com.mg.player.core.device

import android.app.ActivityManager
import android.content.Context

/**
 * Leistungsklasse des Geräts – zentral für alles, was Speicher kostet (Bilder, Sport-Index, LibVLC-Optionen).
 * Vorher richtete sich nur der Videopuffer nach dem Gerät; ein 1-GB-Stick bekam sonst dieselben Caches wie ein
 * 4-GB-Fernseher.
 */
enum class DeviceTier(
    val label: String,
    /** Obergrenze für Bilder im Arbeitsspeicher. */
    val imageCacheBytes: Long,
) {
    LOW("Schwach", 24L * MB),
    NORMAL("Mittel", 48L * MB),
    HIGH("Stark", 64L * MB),
    ;

    companion object {
        /**
         * [lowRam]: Android meldet ein Gerät mit wenig Speicher; [largeHeapMb]: Heap mit largeHeap; [totalRamMb]: RAM gesamt.
         * Schwach: Low-RAM-Gerät, unter 1,5 GB RAM oder unter 256 MB Heap. Stark: ab 3,5 GB RAM und 512 MB Heap.
         */
        fun of(lowRam: Boolean, largeHeapMb: Int, totalRamMb: Long): DeviceTier = when {
            lowRam || totalRamMb in 1 until 1_536 || largeHeapMb < 256 -> LOW
            totalRamMb >= 3_584 && largeHeapMb >= 512 -> HIGH
            else -> NORMAL
        }

        @Volatile private var cached: DeviceTier? = null

        fun of(context: Context): DeviceTier = cached ?: run {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            of(am?.isLowRamDevice == true, am?.largeMemoryClass ?: 192, info.totalMem / MB).also { cached = it }
        }
    }
}

private const val MB = 1024L * 1024L
