package com.mg.player.ui.player

import com.mg.player.core.util.MediaTitle
import com.mg.player.domain.model.Channel

/**
 * Beschriftung der Quellen unter „Mehr“ → Quelle: Originalname des Anbieters, Qualität mit Erklärung, Zugang.
 * Beispiel: „Quelle 1 · DE - PROSIEBEN UHD · Ultra HD 4K (2160p) · Zugang A · beste“.
 * So ist jede Zuordnung nachprüfbar – eine falsche Quelle fällt sofort am Originalnamen auf.
 */
internal object SourceLabels {

    private val definitions = linkedMapOf(
        "8K" to "8K (4320p)",
        "4K" to "Ultra HD 4K (2160p)",
        "FHD" to "Full HD (1080p)",
        "HD" to "HD (720p)",
        "SD" to "SD (576p)",
        "HDR" to "HDR",
        "HEVC" to "HEVC/H.265",
        "RAW" to "RAW (Originalsignal)",
        "50FPS" to "50 Bilder/s",
        MediaTitle.BACKUP to "Ersatz-Stream",
    )

    /** „Ultra HD 4K (2160p) · HEVC/H.265“; ohne Angabe im Namen „Qualität nicht angegeben“. */
    fun quality(badges: List<String>): String {
        val known = definitions.filterKeys { it in badges }.values
        return if (known.isEmpty()) "Qualität nicht angegeben" else known.joinToString(" · ")
    }

    fun live(index: Int, source: Channel, account: String?, best: Boolean): String = listOfNotNull(
        "Quelle ${index + 1}",
        source.originalName.trim().ifEmpty { source.name },
        quality(source.qualityBadges),
        account?.ifBlank { null },
        if (best) "beste" else null,
    ).joinToString(" · ")
}
