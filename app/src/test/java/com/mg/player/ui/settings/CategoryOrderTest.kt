package com.mg.player.ui.settings

import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kategorien verschieben: nur innerhalb der Favoriten bzw. der übrigen Gruppen, Ränder bleiben Ränder. */
class CategoryOrderTest {

    private fun cat(id: String, pinned: Boolean = false) = Category(id, id, ContentType.LIVE, pinned = pinned)

    private val list = listOf(cat("F", pinned = true), cat("A"), cat("B"), cat("C"))

    @Test
    fun movesOneStepWithinGroup() {
        assertEquals(listOf("F", "B", "A", "C"), movedOrder(list, "A", 1))
        assertEquals(listOf("F", "A", "C", "B"), movedOrder(list, "C", -1))
    }

    @Test
    fun staysAtEdgesAndOutOfFavorites() {
        assertNull(movedOrder(list, "C", 1))
        assertNull("Nicht über die Favoriten-Gruppe hinweg", movedOrder(list, "A", -1))
        assertNull(movedOrder(list, "F", -1))
        assertNull(movedOrder(list, "X", 1))
    }

    @Test
    fun localOrderUsesFreshFlagsAndKeepsFavoritesOnTop() {
        // Während des Verschiebens wird „C“ zum Favoriten: Es rutscht nach oben, der Rest bleibt in lokaler Reihenfolge.
        val fresh = listOf(cat("F", pinned = true), cat("A"), cat("B"), cat("C", pinned = true))
        val shown = applyOrder(fresh, listOf("F", "B", "A", "C"))
        assertEquals(listOf("F", "C", "B", "A"), shown.map { it.id })
        assertEquals(true, shown[1].pinned)
    }

    @Test
    fun ignoresLocalOrderWhenGroupsChanged() {
        assertEquals(list, applyOrder(list, listOf("A", "B")))
        assertEquals(list, applyOrder(list, null))
    }
}
