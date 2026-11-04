package com.mg.player.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.screenshots.TvHostPlain
import com.mg.player.screenshots.onRootKey
import com.mg.player.screenshots.remote
import com.mg.player.screenshots.settle
import com.mg.player.ui.theme.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Obere Leiste: Verweilen öffnet nur beim Durchblättern mit links/rechts, nie wenn der Fokus von außen kommt. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TopBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = listOf(
        TopBarItem("a", "Live TV", AppIcons.LiveTv),
        TopBarItem("b", "TV-Guide", AppIcons.Guide),
        TopBarItem("c", "Filme", AppIcons.Movie),
    )

    private var selected by mutableStateOf("a")
    private val opened = mutableListOf<String>()

    private fun show(contentRequester: FocusRequester? = null) {
        compose.setContent {
            TvHostPlain {
                Column {
                    TopBar(items, selected, onSelect = { selected = it.id }, onOpen = {
                        selected = it.id
                        opened += it.id
                    })
                    val requester = contentRequester ?: remember { FocusRequester() }
                    ActionButton("Inhalt", onClick = {}, modifier = Modifier.focusRequester(requester).width(200.dp))
                }
            }
        }
        compose.settle()
    }

    @Test
    fun browsingWithRightOpensAfterDwell() {
        val content = FocusRequester()
        show(content)
        compose.runOnIdle { content.requestFocus() }
        compose.remote(Key.DirectionUp)
        compose.onNode(isFocused() and hasContentDescription("Live TV")).assertExists()
        compose.remote(Key.DirectionRight)
        compose.onNode(isFocused() and hasContentDescription("TV-Guide")).assertExists()
        compose.settle(600)
        assertEquals("b", selected)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun quickBrowsingDoesNotOpenEveryTab() {
        val content = FocusRequester()
        show(content)
        compose.runOnIdle { content.requestFocus() }
        compose.remote(Key.DirectionUp)
        // Zweimal rechts in schneller Folge (unter der Verweilzeit): erst der Endpunkt wird geöffnet.
        compose.onRootKey(Key.DirectionRight)
        compose.onRootKey(Key.DirectionRight)
        compose.onNode(isFocused() and hasContentDescription("Filme")).assertExists()
        compose.settle(600)
        assertEquals("c", selected)
    }

    @Test
    fun focusComingFromOutsideDoesNotSwitch() {
        show()
        // Fokus landet direkt (nicht per links/rechts) auf einem nicht gewählten Tab.
        compose.onNode(hasContentDescription("Filme") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.settle(600)
        assertEquals("a", selected)
    }
}
