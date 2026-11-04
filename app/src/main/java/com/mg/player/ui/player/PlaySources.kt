package com.mg.player.ui.player

import androidx.compose.runtime.Immutable
import com.mg.player.domain.model.ConnectionStatus

/** Eine Quelle eines Films bzw. einer Folge aus „Alle Zugänge“ (ein Original in einem echten Zugang). */
@Immutable
data class VodSource(
    val itemId: String,
    val playlistId: Long,
    val streamUrl: String,
    val accountName: String,
    val quality: String?,
    val qualityRank: Int,
    val language: String?,
    val durationSec: Int?,
    /** Titel genau wie vom Anbieter geliefert. */
    val originalName: String = "",
) {
    /** Kurz für Overlay und Hinweise: „Zugang · FHD · DE“. */
    val label: String get() = listOfNotNull(accountName.takeIf { it.isNotBlank() }, quality, language).joinToString(" · ")

    /** Quellen-Liste: „Avatar (2009) [4K] · Ultra HD 4K (2160p) · DE · Zugang“. */
    val fullLabel: String get() = listOfNotNull(
        originalName.trim().ifEmpty { null },
        quality?.let { SourceLabels.quality(listOf(it)) },
        language,
        accountName.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * Rangfolge der Quellen eines Inhalts über alle Zugänge (Plan „Alle Zugänge“, Entscheidungen 03.11.2026):
 * 1. auf dem Gerät abspielbar (kein 4K auf 1080p-Geräten), 2. Zugang nicht voll belegt, 3. eigene Sprache,
 * 4. die zuletzt bewährte Quelle, 5. Qualität, 6. Zuverlässigkeit (kürzlich ausgefallene Zugänge nach hinten),
 * 7. bei Gleichstand der freieste Zugang, 8. dann die eigene Rangfolge der Zugänge.
 */
object SourceRanker {

    data class Candidate<T>(
        val item: T,
        val id: String,
        val playlistId: Long,
        val quality: String?,
        val qualityRank: Int,
        val language: String? = null,
    )

    private val beyondFullHd = setOf("4K", "8K")

    fun <T> rank(
        candidates: List<Candidate<T>>,
        rememberedId: String?,
        supports4K: Boolean,
        statuses: Map<Long, ConnectionStatus>,
        preferredLanguage: String? = null,
        unreliable: (Long) -> Boolean = { false },
        accountRank: (Long) -> Int = { 0 },
    ): List<T> = candidates.withIndex().sortedWith(
        compareBy<IndexedValue<Candidate<T>>> { if (!supports4K && it.value.quality in beyondFullHd) 1 else 0 }
            .thenBy { if (statuses[it.value.playlistId]?.full == true) 1 else 0 }
            .thenBy { languagePenalty(it.value.language, preferredLanguage) }
            .thenBy { if (it.value.id == rememberedId) 0 else 1 }
            .thenBy { it.value.qualityRank }
            .thenBy { if (unreliable(it.value.playlistId)) 1 else 0 }
            .thenByDescending { freeSlots(statuses[it.value.playlistId]) }
            .thenBy { accountRank(it.value.playlistId) }
            .thenBy { it.index },
    ).map { it.value.item }

    /** Freie Verbindungen; unbegrenzt bzw. unbekannt zählt wie „viel frei“, aber hinter echten Angaben gleicher Güte. */
    private fun freeSlots(status: ConnectionStatus?): Int = when {
        status == null -> UNKNOWN_FREE
        status.max <= 0 -> UNLIMITED_FREE
        else -> (status.max - status.active).coerceAtLeast(0)
    }

    private fun languagePenalty(language: String?, preferred: String?): Int = when {
        preferred == null || language == null -> 1
        language.equals(preferred, ignoreCase = true) -> 0
        else -> 2
    }

    private const val UNLIMITED_FREE = 1_000
    private const val UNKNOWN_FREE = 1
}
