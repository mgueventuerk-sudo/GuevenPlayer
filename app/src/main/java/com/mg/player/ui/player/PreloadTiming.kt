package com.mg.player.ui.player

internal object PreloadTiming {
    /** Liegt [sinceMs] länger als [limitMs] zurück? null = nie geschehen: gilt als abgelaufen (ohne Überlauf). */
    fun isOlderThan(sinceMs: Long?, nowMs: Long, limitMs: Long): Boolean = sinceMs == null || nowMs - sinceMs > limitMs
}
