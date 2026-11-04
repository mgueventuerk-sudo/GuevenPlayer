package com.mg.player.ui.live

/** Tatsächliche Decodermaße für den Sender, dessen Vorschau gerade läuft. */
internal data class PreviewResolution(val channelId: String, val width: Int, val height: Int) {
    val label: String get() = "$width×$height"
}

internal fun measuredPreviewResolution(channelId: String, width: Int?, height: Int?): PreviewResolution? {
    val actualWidth = width?.takeIf { it > 0 } ?: return null
    val actualHeight = height?.takeIf { it > 0 } ?: return null
    return PreviewResolution(channelId, actualWidth, actualHeight)
}
