package com.mg.player.data.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class ParallelSearchResultsTest {
    @Test
    fun fastResultDoesNotWaitForPreferredPlaylist() = runBlocking {
        val preferredStarted = CompletableDeferred<Unit>()
        val preferredResult = CompletableDeferred<String>()

        val first = withTimeout(5_000) {
            parallelSearchResults(listOf("preferred", "fast"), parallelism = 2) { name ->
                if (name == "preferred") {
                    preferredStarted.complete(Unit)
                    preferredResult.await()
                } else {
                    preferredStarted.await()
                    name
                }
            }.first()
        }

        assertEquals("fast", first)
    }
}
