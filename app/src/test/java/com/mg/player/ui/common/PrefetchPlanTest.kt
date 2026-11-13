package com.mg.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPlanTest {

    private val urls = List(100) { "https://x/$it.jpg" }
    private fun plan(last: Int, count: Int, total: Int = urls.size, done: Set<String> = emptySet(), at: (Int) -> String? = { urls.getOrNull(it) }) =
        PrefetchPlan.upcoming(last, total, count, at) { it in done }

    @Test
    fun takesTheEntriesRightAfterTheLastVisibleOne() {
        assertEquals(urls.subList(11, 16), plan(last = 10, count = 5))
    }

    @Test
    fun neverLooksPastTheLoadedItems() {
        assertEquals(urls.subList(8, 10), plan(last = 7, count = 30, total = 10))
        assertTrue(plan(last = 9, count = 30, total = 10).isEmpty())
    }

    @Test
    fun skipsEmptyDuplicateAndAlreadyDoneAddresses() {
        val at: (Int) -> String? = { index -> mapOf(1 to "a", 2 to "", 3 to null, 4 to "a", 5 to "b", 6 to "c")[index] }
        assertEquals(listOf("a", "c"), plan(last = 0, count = 6, total = 7, at = at, done = setOf("b")))
    }

    @Test
    fun doesNothingWithoutVisibleItemsOrBudget() {
        assertTrue(plan(last = -1, count = 10).isEmpty())
        assertTrue(plan(last = 5, count = 0).isEmpty())
    }
}
