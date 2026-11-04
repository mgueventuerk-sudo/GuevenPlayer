package com.mg.player.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.screenshots.TvHostPlain
import com.mg.player.screenshots.settle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Ein Screen verlässt den Bildschirm (z. B. Player geöffnet) und kehrt zurück: Fokus liegt wieder auf demselben Knopf. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class FocusMemoryTest {

    @get:Rule
    val compose = createComposeRule()

    private var onScreen by mutableStateOf(true)

    private fun show() {
        compose.setContent {
            TvHostPlain {
                // Wie im Navigator: der Zustand eines Screens lebt im SaveableStateHolder weiter, solange er im Back-Stack ist.
                val holder = rememberSaveableStateHolder()
                if (onScreen) {
                    holder.SaveableStateProvider("detail") {
                        val memory = rememberFocusMemory()
                        val play = remember { FocusRequester() }
                        LaunchedEffect(Unit) { memory.restore(play) }
                        Row {
                            ActionButton("Abspielen", onClick = {}, modifier = Modifier.focusRequester(play).remembered(memory, "play"))
                            ActionButton("Trailer", onClick = {}, modifier = Modifier.remembered(memory, "trailer"))
                            ActionButton("Favorit", onClick = {}, modifier = Modifier.remembered(memory, "favorite"))
                        }
                    }
                }
            }
        }
        compose.settle()
    }

    @Test
    fun firstVisitStartsOnFallback() {
        show()
        compose.onNode(isFocused()).assert(hasText("Abspielen"))
    }

    @Test
    fun returningRestoresLastFocusedButton() {
        show()
        compose.onNode(hasText("Favorit") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.settle(300)
        onScreen = false
        compose.settle(300)
        onScreen = true
        compose.settle()
        compose.onNode(isFocused()).assert(hasText("Favorit"))
    }
}
