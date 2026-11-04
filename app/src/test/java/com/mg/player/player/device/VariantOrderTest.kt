package com.mg.player.player.device

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fall aus der Prüfung: Ein Full-HD-Fernseher startete immer zuerst die 4K-Variante und ruckelte. */
class VariantOrderTest {

    private data class V(val id: String, val quality: String?)

    private val variants = listOf(V("a", "4K"), V("b", "FHD"), V("c", "HD"), V("d", "SD"))

    private fun order(remembered: String?, supports4K: Boolean) = VariantOrder.order(variants, { it.id }, { it.quality }, remembered, supports4K).map { it.id }

    @Test
    fun keepsProviderOrderOn4KDevices() {
        assertEquals(listOf("a", "b", "c", "d"), order(null, supports4K = true))
    }

    @Test
    fun puts4KLastOnFullHdDevices() {
        assertEquals(listOf("b", "c", "d", "a"), order(null, supports4K = false))
    }

    @Test
    fun rememberedVariantAlwaysFirst() {
        assertEquals(listOf("c", "a", "b", "d"), order("c", supports4K = true))
        // Auch eine gemerkte 4K-Variante: Sie hat auf diesem Gerät nachweislich funktioniert.
        assertEquals(listOf("a", "b", "c", "d"), order("a", supports4K = false))
    }

    @Test
    fun unknownRememberedIdIsIgnored() {
        assertEquals(listOf("b", "c", "d", "a"), order("weg", supports4K = false))
    }
}
