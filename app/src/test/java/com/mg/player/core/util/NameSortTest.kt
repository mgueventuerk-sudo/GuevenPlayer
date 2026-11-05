package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NameSortTest {

    private fun sorted(vararg names: String) = names.sortedWith(NameSort.comparator)

    @Test
    fun providerPrefixesAndQualityDoNotCount() {
        assertEquals(listOf("DE: Das Erste HD", "|DE| ProSieben", "DE | ZDF"), sorted("DE | ZDF", "|DE| ProSieben", "DE: Das Erste HD"))
    }

    @Test
    fun numbersCompareAsNumbers() {
        assertEquals(listOf("Sky Sport 2", "Sky Sport 10"), sorted("Sky Sport 10", "Sky Sport 2"))
    }

    @Test
    fun caseAndUmlautsAreIgnored() {
        assertEquals(listOf("arte", "Bayern 3", "Ölfilm"), sorted("Ölfilm", "Bayern 3", "arte"))
    }

    @Test
    fun namesThatLookLikePrefixesStay() {
        assertEquals("ard alpha", NameSort.key("ARD-alpha"))
        assertEquals("zdf neo", NameSort.key("ZDF neo"))
    }
}
