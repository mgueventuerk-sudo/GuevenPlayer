package com.mg.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPlanTest {

    private val urls = List(100) { "https://x/$it.jpg" }
    private fun plan(last: Int, count: Int, total: Int = urls.size, done: Set<String> = emptySet(), at: (Int) -> String? = { urls.getOrNull(it) }) =
        PrefetchPlan.upcoming(last, total, count, at) { it in done }

    @Test
    fun aheadKeepsTheOrderAndIgnoresWhatIsAlreadyDone() {
        val all = PrefetchPlan.ahead(10, urls.size, 5) { urls.getOrNull(it) }
        assertEquals(urls.subList(11, 16), all)
        // „bereits erledigt“ filtert nur upcoming, nicht ahead – das Aufteilen in Speicher/Platte macht der Aufrufer.
        assertEquals(urls.subList(12, 16), plan(last = 10, count = 5, done = setOf(urls[11])))
        assertTrue(PrefetchPlan.ahead(-1, urls.size, 5) { urls.getOrNull(it) }.isEmpty())
    }

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

    private fun window(first: Int, last: Int, behind: Int, ahead: Int, total: Int = urls.size) =
        PrefetchPlan.window(first, last, total, behind, ahead) { urls.getOrNull(it) }

    @Test
    fun windowCoversTheVisibleItemsPlusKeepBehindAndAhead() {
        assertEquals(urls.subList(7, 24).toSet(), window(first = 10, last = 15, behind = 3, ahead = 8))
    }

    @Test
    fun windowIsCutAtTheEndsOfTheList() {
        assertEquals(urls.subList(0, 7).toSet(), window(first = 0, last = 2, behind = 5, ahead = 4))
        assertEquals(urls.subList(95, 100).toSet(), window(first = 97, last = 99, behind = 2, ahead = 9))
        assertTrue("Liste kürzer als das Fenster", window(first = 0, last = 1, behind = 3, ahead = 50, total = 5).size <= 5)
    }

    @Test
    fun windowIsEmptyWithoutVisibleItems() {
        assertTrue(window(first = -1, last = -1, behind = 3, ahead = 8).isEmpty())
        assertTrue(window(first = 5, last = 2, behind = 3, ahead = 8).isEmpty())
    }
}
