package com.mg.player.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.mg.player.domain.model.AccentColor
import com.mg.player.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hell/Dunkel/Automatisch und Lesbarkeit (Kontrast nach WCAG) aller Paletten. */
class ThemeTest {

    @Test
    fun automaticFollowsTimeOfDay() {
        assertTrue("6 Uhr ist noch dunkel", isDarkFor(ThemeMode.AUTO, 6))
        assertFalse("7 Uhr ist hell", isDarkFor(ThemeMode.AUTO, 7))
        assertFalse("18 Uhr ist hell", isDarkFor(ThemeMode.AUTO, 18))
        assertTrue("19 Uhr ist dunkel", isDarkFor(ThemeMode.AUTO, 19))
        assertTrue("Mitternacht ist dunkel", isDarkFor(ThemeMode.AUTO, 0))
    }

    @Test
    fun fixedModesIgnoreTime() {
        for (hour in 0..23) {
            assertTrue(isDarkFor(ThemeMode.DARK, hour))
            assertFalse(isDarkFor(ThemeMode.LIGHT, hour))
        }
    }

    @Test
    fun applySwitchesPaletteAndAccent() {
        AppColors.apply(dark = false, accent = AccentColor.ROSE)
        assertFalse(AppColors.isDark)
        assertEquals(accentGradient(AccentColor.ROSE).first(), AppColors.Accent)
        AppColors.apply(dark = true, accent = AccentColor.BLUE)
        assertTrue(AppColors.isDark)
        assertEquals(accentGradient(AccentColor.BLUE).first(), AppColors.Accent)
    }

    @Test
    fun everyAccentHasItsOwnColors() {
        val colors = AccentColor.entries.map { accentGradient(it).first() }
        assertEquals(colors.size, colors.toSet().size)
        AccentColor.entries.forEach { assertNotEquals(accentGradient(it)[0], accentGradient(it)[1]) }
    }

    /** Text muss auf dem Hintergrund, auf Glas und auf der Fokus-Fläche gut lesbar sein – in jeder Kombination. */
    @Test
    fun textContrastIsReadableEverywhere() {
        for (dark in listOf(true, false)) {
            for (accent in AccentColor.entries) {
                val p = paletteFor(dark, accent)
                val name = "${if (dark) "dunkel" else "hell"}/$accent"
                val glass = p.glass.compositeOver(p.background)
                assertContrast("$name Text/Hintergrund", p.textPrimary, p.background, 7.0)
                assertContrast("$name Text/Glas", p.textPrimary, glass, 7.0)
                assertContrast("$name Nebentext/Hintergrund", p.textSecondary, p.background, 4.5)
                assertContrast("$name Nebentext/Glas", p.textSecondary, glass, 4.5)
                assertContrast("$name Hinweistext/Hintergrund", p.textMuted, p.background, 3.0)
                assertContrast("$name Fokus", p.onFocus, p.focusFill, 4.5)
                assertContrast("$name Akzenttext", p.accentText, p.background, 4.5)
                assertContrast("$name Primärknopf", p.onAccent, p.accent, 3.0)
                assertContrast("$name Akzent-Partner", contentColorFor(p.accentAlt), p.accentAlt, 3.0)
            }
        }
    }

    @Test
    fun panelsAreNearlyOpaqueForDialogs() {
        AppColors.apply(dark = true, accent = AccentColor.BLUE)
        assertTrue(AppColors.Panel.alpha > 0.9f)
        AppColors.apply(dark = false, accent = AccentColor.BLUE)
        assertTrue(AppColors.Panel.alpha > 0.9f)
        AppColors.apply(dark = true, accent = AccentColor.BLUE)
    }

    private fun assertContrast(label: String, fg: Color, bg: Color, min: Double) {
        val l1 = fg.compositeOver(bg).luminance() + 0.05
        val l2 = bg.luminance() + 0.05
        val ratio = maxOf(l1, l2) / minOf(l1, l2)
        assertTrue("$label: Kontrast %.2f < %.1f".format(ratio, min), ratio >= min)
    }
}
