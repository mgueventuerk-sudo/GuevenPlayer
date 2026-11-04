package com.mg.player.core.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Zehn Gruppen nacheinander einblenden soll das EPG einmal laden – nicht zehnmal. */
@OptIn(ExperimentalCoroutinesApi::class)
class DelayedActionTest {

    private val scope = TestScope()
    private val calls = mutableListOf<Long>()
    private val action = DelayedAction<Long>(scope, 30_000L) { calls += it }

    @Test
    fun burstOfRequestsRunsOnce() {
        repeat(10) {
            action.request(1)
            scope.advanceTimeBy(5_000)
        }
        assertEquals(emptyList<Long>(), calls)
        scope.advanceUntilIdle()
        assertEquals(listOf(1L), calls)
    }

    @Test
    fun keysAreIndependent() {
        action.request(1)
        action.request(2)
        scope.advanceUntilIdle()
        assertEquals(setOf(1L, 2L), calls.toSet())
    }

    @Test
    fun requestAfterRunStartsAgain() {
        action.request(1)
        scope.advanceUntilIdle()
        action.request(1)
        scope.advanceUntilIdle()
        assertEquals(listOf(1L, 1L), calls)
    }
}
