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

    /** Ab 3.50: Hängt eine Variante, rutscht sie nach unten – auch vor die bewährte und vor jede Qualität. */
    @Test
    fun healthyVariantsComeFirstQualityIsSecondary() {
        val health = mapOf("a" to 2, "b" to 1)
        val ordered = VariantOrder.order(variants, { it.id }, { it.quality }, "a", supports4K = true) { health[it] ?: 0 }.map { it.id }
        assertEquals(listOf("c", "d", "b", "a"), ordered)
    }

    /** Eine Hänger-Variante macht 4K auf einem 1080p-Gerät nicht zur ersten Wahl. */
    @Test
    fun healthNeverPutsUnsupported4KFirst() {
        val ordered = VariantOrder.order(variants, { it.id }, { it.quality }, null, supports4K = false) { if (it == "b") 1 else 0 }.map { it.id }
        assertEquals(listOf("c", "d", "b", "a"), ordered)
    }
}
