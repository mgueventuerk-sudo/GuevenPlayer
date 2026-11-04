package com.mg.player.domain.sport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * EIN Takt für alle Countdown-Karten. Läuft nur mit Abonnenten (WhileSubscribed(0)): Ist die Sport-Seite nicht
 * sichtbar oder die App im Hintergrund, steht er still. Er tickt auf volle Sekunden, damit alle Karten gleichzeitig umspringen.
 */
class EventCountdownTicker(scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    val now: StateFlow<Long> = flow {
        while (true) {
            val t = clock()
            emit(t)
            delay(1_000 - t % 1_000)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(0), clock())
}
