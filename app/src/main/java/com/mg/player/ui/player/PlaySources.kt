package com.mg.player.ui.player

import androidx.compose.runtime.Immutable
import com.mg.player.domain.model.AudioLanguages
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
 * 3a. die wenigsten Hänger und Fehler (Gedächtnis des Wächters, ab 3.50), 4. die zuletzt bewährte Quelle,
 * 4a. derselbe Zugang wie die vorige Folge der Serie ([preferredPlaylist], ab 3.60 – sonst wechselte die Fassung
 * und damit oft die Sprache von Folge zu Folge), 5. Qualität, 6. Zuverlässigkeit (kürzlich ausgefallene Zugänge nach hinten),
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
        /** Bevorzugte Sprachen, erste = Standard (ISO 639-1); leer = Sprache spielt keine Rolle. */
        preferredLanguages: List<String> = emptyList(),
        unreliable: (Long) -> Boolean = { false },
        accountRank: (Long) -> Int = { 0 },
        health: (String) -> Int = { 0 },
        preferredPlaylist: Long? = null,
    ): List<T> {
        // Einmal je Quelle abfragen: Der Rang verfällt mit der Uhr – mitten im Sortieren darf er sich nicht ändern.
        val healthOf = candidates.associate { it.id to health(it.id) }
        return candidates.withIndex().sortedWith(
            compareBy<IndexedValue<Candidate<T>>> { if (!supports4K && it.value.quality in beyondFullHd) 1 else 0 }
                .thenBy { if (statuses[it.value.playlistId]?.full == true) 1 else 0 }
                .thenBy { if (preferredLanguages.isEmpty()) 0 else AudioLanguages.rank(it.value.language, preferredLanguages) }
                .thenBy { healthOf[it.value.id] ?: 0 }
                .thenBy { if (it.value.id == rememberedId) 0 else 1 }
                .thenBy { if (preferredPlaylist != null && it.value.playlistId == preferredPlaylist) 0 else 1 }
                .thenBy { it.value.qualityRank }
                .thenBy { if (unreliable(it.value.playlistId)) 1 else 0 }
                .thenByDescending { freeSlots(statuses[it.value.playlistId]) }
                .thenBy { accountRank(it.value.playlistId) }
                .thenBy { it.index },
        ).map { it.value.item }
    }

    /**
     * Verbindungsstand ohne die eigene, gerade beendete Wiedergabe: Anbieter zählen sie noch ein, zwei Minuten mit. Ohne
     * das galt der Zugang der vorigen Folge beim Wechsel zur nächsten als „voll“ – und die Folge kam aus einem anderen
     * Zugang, oft in einer anderen Sprache. [usedHere]: Hat dieses Gerät den Zugang gerade selbst genutzt?
     */
    fun withoutOwnUse(statuses: Map<Long, ConnectionStatus>, usedHere: (Long) -> Boolean): Map<Long, ConnectionStatus> =
        statuses.mapValues { (playlistId, status) ->
            if (status.active > 0 && usedHere(playlistId)) status.copy(active = status.active - 1) else status
        }

    /** Freie Verbindungen; unbegrenzt bzw. unbekannt zählt wie „viel frei“, aber hinter echten Angaben gleicher Güte. */
    private fun freeSlots(status: ConnectionStatus?): Int = when {
        status == null -> UNKNOWN_FREE
        status.max <= 0 -> UNLIMITED_FREE
        else -> (status.max - status.active).coerceAtLeast(0)
    }

    /**
     * „Nur diese Sprachen“: Quellen in einer erkannt anderen Sprache weglassen – aber nur, wenn danach noch eine übrig
     * bleibt (sonst lieber die fremde Fassung als gar nichts). Unbekannte Sprache bleibt immer.
     */
    fun <T> onlyLanguages(candidates: List<Candidate<T>>, languages: List<String>): List<Candidate<T>> =
        candidates.filterNot { AudioLanguages.isForeign(it.language, languages) }.ifEmpty { candidates }

    private const val UNLIMITED_FREE = 1_000
    private const val UNKNOWN_FREE = 1
}
