package com.mg.player.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemTest {

    @Test
    fun spacingIsOnFourDpGrid() {
        Spacing.scale.forEach { step -> assertEquals("$step liegt nicht im 4-dp-Raster", 0f, step.value % 4f, 0f) }
    }

    @Test
    fun spacingScaleIsStrictlyAscending() {
        Spacing.scale.zipWithNext().forEach { (a, b) -> assertTrue("$a muss kleiner als $b sein", a < b) }
    }

    @Test
    fun namedGapsComeFromTheScale() {
        assertTrue(Spacing.CardGap in Spacing.scale)
        assertTrue(Spacing.RowGap in Spacing.scale)
    }

    @Test
    fun cardsUseStandardAspectRatios() {
        assertEquals(16f / 9f, CardSize.LANDSCAPE_RATIO, 0.0001f)
        assertEquals(2f / 3f, CardSize.POSTER_RATIO, 0.0001f)
    }
}
