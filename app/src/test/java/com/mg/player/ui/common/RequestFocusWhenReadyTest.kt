package com.mg.player.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.screenshots.TvHostPlain
import com.mg.player.screenshots.settle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `requestFocus()` meldet „noch nicht fokussierbar“ als false, nicht als Ausnahme (z. B. Dialogfenster im ersten
 * Moment). Der Helfer muss dann weiter versuchen – bis 3.57 brach er nach dem ersten false ab und der Fokus lag im Nichts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RequestFocusWhenReadyTest {

    @get:Rule
    val compose = createComposeRule()

    private var focusable by mutableStateOf(false)
    private var result: Boolean? = null

    /** [attempts] je Fall gesetzt, damit das Zeitfenster nicht am Limit der Wartezeit im Test liegt. */
    private fun show(attempts: Int) {
        compose.setContent {
            TvHostPlain {
                val target = remember { FocusRequester() }
                LaunchedEffect(Unit) { result = target.requestFocusWhenReady(attempts = attempts) }
                Row {
                    ActionButton("Anderer", onClick = {})
                    ActionButton(
                        "Ziel",
                        onClick = {},
                        modifier = Modifier.focusRequester(target).focusProperties { canFocus = focusable },
                    )
                }
            }
        }
    }

    @Test
    fun keepsTryingUntilTheElementCanTakeFocus() {
        // 40 × 50 ms = 2 s Fenster: Das Ziel wird erst nach 300 ms fokussierbar (wie ein aufgehendes Dialogfenster).
        show(attempts = 40)
        compose.settle(300)
        compose.onNode(hasText("Ziel") and hasClickAction()).assertIsNotFocused()
        focusable = true
        compose.settle()
        compose.onNode(isFocused()).assert(hasText("Ziel"))
        assertEquals(true, result)
    }

    @Test
    fun givesUpHonestlyWhenTheElementNeverBecomesFocusable() {
        // 5 × 50 ms = 250 ms: Das Aufgeben liegt sicher innerhalb der Wartezeit unten.
        show(attempts = 5)
        compose.settle()
        compose.onNode(hasText("Ziel") and hasClickAction()).assertIsNotFocused()
        assertEquals(false, result)
    }

    @Test
    fun tryRequestFocusReportsRefusalInsteadOfThrowing() {
        var refused: Boolean? = null
        var accepted: Boolean? = null
        compose.setContent {
            TvHostPlain {
                val blocked = remember { FocusRequester() }
                val open = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    refused = blocked.tryRequestFocus()
                    accepted = open.tryRequestFocus()
                }
                Row {
                    ActionButton("Gesperrt", onClick = {}, modifier = Modifier.focusRequester(blocked).focusProperties { canFocus = false })
                    ActionButton("Offen", onClick = {}, modifier = Modifier.focusRequester(open))
                }
            }
        }
        compose.settle()
        assertFalse(refused == true)
        assertEquals(true, accepted)
        compose.onNode(isFocused()).assert(hasText("Offen"))
    }
}
