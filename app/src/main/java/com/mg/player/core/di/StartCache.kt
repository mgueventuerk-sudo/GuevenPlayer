package com.mg.player.core.di

import com.mg.player.domain.model.Category
import java.util.concurrent.atomic.AtomicReference

/**
 * Vorab geladene Daten für das erste Bild (Plan B2, 3.25): Die Live-Gruppen des aktiven Zugangs werden schon in
 * resolveStart (Hintergrund, parallel zum Splash) gelesen. Das ViewModel startet damit statt mit einer leeren Liste –
 * „Alle Sender“ steht sofort da, statt erst nach DataStore- und Room-Abfrage im ersten Bild.
 * Wird genau einmal übernommen (danach liefert Room die Liste wie gewohnt und hält sie aktuell).
 */
class StartCache {
    private val liveCategories = AtomicReference<Pair<Long, List<Category>>?>(null)

    fun putLiveCategories(playlistId: Long, categories: List<Category>) = liveCategories.set(playlistId to categories)

    /** Einmalig: vorab geladene Gruppen, sonst leere Liste. */
    fun takeLiveCategories(): List<Category> = liveCategories.getAndSet(null)?.second.orEmpty()
}
