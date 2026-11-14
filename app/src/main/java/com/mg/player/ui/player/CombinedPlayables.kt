package com.mg.player.ui.player

import com.mg.player.core.di.AppContainer
import com.mg.player.core.util.MediaTitle
import com.mg.player.domain.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Quellenwahl für „Alle Zugänge“ (Live, Filme, Folgen): Rangfolge nach [SourceRanker] mit dem aktuellen Verbindungsstand
 * der Zugänge, dem Schutzschalter des Wiedergabe-Wächters und der eigenen Rangfolge der Zugänge.
 */
internal object CombinedPlayables {

    /** Qualitätsrang aus der Anbieter-Angabe (kleiner = besser), wie beim Import. */
    fun qualityRank(quality: String?): Int = when (quality) {
        "8K", "4K" -> 0
        "FHD" -> 30
        "HD" -> 40
        "SD" -> 60
        else -> 50
    }

    suspend fun rankLive(container: AppContainer, all: List<Channel>, remembered: String?, supports4K: Boolean): List<Channel> {
        val config = container.combined.settings.current()
        val now = System.currentTimeMillis()
        val breaker = container.playback.providerBreaker
        return SourceRanker.rank(
            all.map { SourceRanker.Candidate(it, it.id, it.playlistId, it.quality, qualityRank(it.quality)) },
            rememberedId = remembered,
            supports4K = supports4K,
            statuses = container.connectionMonitor.status.value,
            unreliable = { breaker.isOpen(it.toString(), now) },
            accountRank = config::rankOf,
            health = container.playback.sourceHealth::rank,
        )
    }

    /** Quellen eines Films ([episode] = false) bzw. einer Folge, beste zuerst; gesperrte Gruppen nie. */
    suspend fun vodSources(
        container: AppContainer,
        combinedId: String,
        episode: Boolean,
        remembered: String?,
        supports4K: Boolean,
        preferredPlaylist: Long? = null,
    ): List<VodSource> =
        withContext(Dispatchers.IO) {
            val db = container.database
            val names = db.playlistDao().all().associate { it.id to it.name }
            val restricted = db.categoryPrefDao().all().filter { it.hidden || it.locked }.map { it.categoryId }.toHashSet()
            val candidates = db.combinedDao().sources(combinedId).mapNotNull { link ->
                val (name, url) = if (episode) {
                    val e = db.seriesDao().episode(link.itemId) ?: return@mapNotNull null
                    val series = db.seriesDao().get(e.seriesId)
                    if (series != null && series.categoryId in restricted) return@mapNotNull null
                    e.title to e.streamUrl
                } else {
                    val m = db.vodDao().get(link.itemId) ?: return@mapNotNull null
                    if (m.categoryId in restricted) return@mapNotNull null
                    m.name to m.streamUrl
                }
                val quality = MediaTitle.parse(name).resolution
                val account = names[link.playlistId].orEmpty()
                val source = VodSource(link.itemId, link.playlistId, url, account, quality, qualityRank(quality), link.language, link.durationSec, name)
                SourceRanker.Candidate(source, source.itemId, source.playlistId, quality, source.qualityRank, source.language)
            }
            val now = System.currentTimeMillis()
            val breaker = container.playback.providerBreaker
            val settings = container.settingsRepository.current()
            val languages = settings.audioLanguages
            SourceRanker.rank(
                if (settings.onlyPreferredLanguages) SourceRanker.onlyLanguages(candidates, languages) else candidates,
                rememberedId = remembered,
                supports4K = supports4K,
                statuses = SourceRanker.withoutOwnUse(container.connectionMonitor.status.value, container.connectionMonitor::recentlyUsedHere),
                preferredLanguages = languages,
                unreliable = { breaker.isOpen(it.toString(), now) },
                accountRank = container.combined.settings.current()::rankOf,
                health = container.playback.sourceHealth::rank,
                preferredPlaylist = preferredPlaylist,
            )
        }

    /**
     * Position in einer anderen Quelle: gleich, wenn die Laufzeiten höchstens 3 % auseinanderliegen; sonst
     * anteilig und 10 Sekunden früher, damit nichts fehlt.
     */
    fun mapPosition(positionMs: Long, fromDurationSec: Int?, toDurationSec: Int?): Long {
        val from = fromDurationSec?.takeIf { it > 0 } ?: return positionMs
        val to = toDurationSec?.takeIf { it > 0 } ?: return positionMs
        val ratio = to.toDouble() / from
        if (ratio in SAME_MIN..SAME_MAX) return positionMs
        return ((positionMs * ratio).toLong() - EARLIER_MS).coerceAtLeast(0)
    }

    private const val EARLIER_MS = 10_000L
    private const val SAME_MIN = 0.97
    private const val SAME_MAX = 1.03
}
