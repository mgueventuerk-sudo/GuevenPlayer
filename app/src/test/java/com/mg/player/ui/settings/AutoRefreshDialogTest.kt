package com.mg.player.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tage-Eingabe der automatischen Aktualisierung (Fernseher, OK-Taste = Klick-Aktion): Ziffern, ✓ speichert; leer = aus. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class AutoRefreshDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun typedDaysAreSavedAndLabelled() {
        var saved: Int? = null
        compose.setContent { AutoRefreshDialog("Test", 0, { saved = it }, {}) }
        compose.onNodeWithText("Aus").assertExists()
        compose.onNodeWithText("1").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("4").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("alle 14 Tage").assertExists()
        compose.onNodeWithContentDescription("Speichern").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(14, saved)
    }

    @Test
    fun deletingEverythingTurnsItOffAndLimitIsKept() {
        var saved: Int? = null
        compose.setContent { AutoRefreshDialog("Test", 3, { saved = it }, {}) }
        compose.onNodeWithText("alle 3 Tage").assertExists()
        compose.onNodeWithContentDescription("Löschen").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Speichern").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, saved)
        assertEquals("jeden Tag", autoRefreshLabel(1))
        assertEquals("Aus", autoRefreshLabel(400))
    }
}
