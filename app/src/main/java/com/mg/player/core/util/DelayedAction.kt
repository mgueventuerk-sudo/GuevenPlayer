package com.mg.player.core.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Führt eine Aktion je Schlüssel erst aus, wenn [delayMs] lang keine weitere Anfrage kam
 * (z. B. EPG nachladen, nachdem der Nutzer mehrere Gruppen wieder eingeblendet hat).
 */
class DelayedAction<K : Any>(private val scope: CoroutineScope, private val delayMs: Long, private val action: (K) -> Unit) {
    private val jobs = ConcurrentHashMap<K, Job>()

    fun request(key: K) {
        jobs[key]?.cancel()
        jobs[key] = scope.launch {
            delay(delayMs)
            // Nur den eigenen Eintrag entfernen – nie den einer neueren Anfrage.
            jobs.remove(key, coroutineContext.job)
            action(key)
        }
    }
}
