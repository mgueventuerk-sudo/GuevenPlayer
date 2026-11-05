package com.mg.player.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.mg.player.core.di.AppContainer

/**
 * Plan „Spiel-Finder“ L: Ist ein Spiel bewiesen, kommt ein kurzer Hinweis „Spiel gefunden auf … (geprüft)“, und eine
 * Erinnerung an dasselbe Spiel zeigt danach auf den bewiesenen Sender.
 */
@Composable
fun ProofFoundHost(container: AppContainer) {
    val toast = LocalToast.current
    LaunchedEffect(container) {
        container.sport.proofs.found.collect { record ->
            val board = container.sport.repository.board.value ?: return@collect
            val event = (board.live + board.upcoming.values.flatten()).firstOrNull { it.event.id == record.eventId }?.event ?: return@collect
            container.reminders.retarget(event.title, event.startMs, record.key, record.channel)
            toast.show("Spiel gefunden: ${event.title} auf ${record.channel} (geprüft)")
        }
    }
}
