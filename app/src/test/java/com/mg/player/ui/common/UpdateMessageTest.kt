package com.mg.player.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Update-Hinweis in Alltagssprache: was neu ist, was behoben wurde, und was beim Installieren passiert. */
class UpdateMessageTest {

    @Test
    fun showsSectionsFromReleaseNotesWithoutExtraHeading() {
        val text = updateMessage("3.61", "Neu:\n• Einstellung „Sprache“\nBehoben:\n• Serien wechseln nicht mehr die Sprache", touch = false)
        assertTrue(text.startsWith("Version 3.61 ist bereit."))
        assertTrue("Behoben:\n• Serien wechseln nicht mehr die Sprache" in text)
        assertFalse("Das ist neu:" in text)
        assertTrue("Wähle „Installieren“" in text)
    }

    @Test
    fun plainBulletsGetAHeadingAndTouchSaysTap() {
        val text = updateMessage("3.61", "• Schnellerer Start", touch = true)
        assertTrue("Das ist neu:\n• Schnellerer Start" in text)
        assertTrue("Tippe auf „Installieren“" in text)
    }

    @Test
    fun saysSoWhenThereIsNoDescription() {
        assertTrue("keine Beschreibung der Änderungen" in updateMessage("3.61", null, touch = false))
    }
}
