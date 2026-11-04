package com.mg.player.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Wie [runCatching], aber ein Abbruch (CancellationException) wird weitergereicht. Das normale runCatching
 * fängt ihn mit: Die abgebrochene Coroutine liefe dann weiter (z. B. Schleifen über Sender nach dem Verlassen
 * des Bildschirms) und schriebe womöglich noch Ergebnisse. Fängt nur Exceptions – Errors (OutOfMemoryError)
 * sollen nicht still verschluckt werden.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
