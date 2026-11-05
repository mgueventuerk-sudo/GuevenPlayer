package com.mg.player.data.combined

import com.mg.player.domain.model.GroupSort

/** Ob Sender, Filme und Serien eines Zugangs alphabetisch erscheinen ([GroupSort], ab 3.44). */
object SortPolicy {
    fun alphabetical(mode: GroupSort, playlistId: Long): Boolean = when (mode) {
        // In „Alle Zugänge“ mischen sich mehrere Anbieter – deren Reihenfolgen ergeben zusammen keine Ordnung.
        GroupSort.AUTO -> Combined.isCombined(playlistId)
        GroupSort.PROVIDER -> false
        GroupSort.ALPHABETICAL -> true
    }
}
