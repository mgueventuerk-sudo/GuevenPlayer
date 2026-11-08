package com.mg.player.data.search

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Claim work in input order, with each worker publishing as soon as its result is available. */
internal fun <T, R> parallelSearchResults(
    items: List<T>,
    parallelism: Int,
    search: suspend (T) -> R,
): Flow<R> = channelFlow {
    val next = AtomicInteger()
    repeat(minOf(parallelism, items.size)) {
        launch {
            while (true) {
                val index = next.getAndIncrement()
                if (index >= items.size) break
                send(search(items[index]))
            }
        }
    }
}
