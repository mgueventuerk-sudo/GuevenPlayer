package com.mg.player.domain.usecase

import com.mg.player.domain.model.Episode
import com.mg.player.domain.repository.SeriesRepository

/**
 * Ermittelt die nächste Folge – inklusive Wechsel von der letzten Folge einer Staffel
 * zu Staffel + 1, Folge 1.
 */
class ResolveNextEpisodeUseCase(private val series: SeriesRepository) {

    suspend operator fun invoke(current: Episode): Episode? {
        val all = series.episodes(current.seriesId)
        return next(all, current)
    }

    companion object {
        fun next(all: List<Episode>, current: Episode): Episode? {
            val sorted = all.sortedWith(compareBy({ it.season }, { it.episode }))
            val index = sorted.indexOfFirst { it.id == current.id }
            if (index >= 0) return sorted.getOrNull(index + 1)
            return sorted.firstOrNull {
                it.season > current.season || (it.season == current.season && it.episode > current.episode)
            }
        }
    }
}
