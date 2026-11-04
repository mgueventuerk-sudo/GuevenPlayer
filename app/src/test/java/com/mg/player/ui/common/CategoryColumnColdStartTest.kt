package com.mg.player.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.screenshots.TvHost
import com.mg.player.screenshots.settle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Am Fire TV gemeldet: Nach dem Neustart stand der Fokus „irgendwo“ in der Gruppenliste statt auf „Alle Sender“,
 * nach einem Tab-Wechsel dagegen richtig. Ursache: Android stellt die gespeicherte Scrollposition der Liste wieder
 * her, während die Gruppen noch laden – „Alle Sender“ liegt dann außerhalb des Bildes und der Fokus fällt auf eine
 * gerade sichtbare Gruppe.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CategoryColumnColdStartTest {

    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    private val groups = listOf(Category(Category.ALL, "Alle Sender", ContentType.LIVE)) +
        (1..80).map { Category("g$it", "Gruppe $it", ContentType.LIVE) }

    @Test
    fun restoredScrollPositionDoesNotStealTheStartFocus() {
        val restore = StateRestorationTester(compose)
        var loaded by mutableStateOf(true)
        lateinit var all: FocusRequester
        restore.setContent {
            TvHost(container) {
                all = remember { FocusRequester() }
                CompositionLocalProvider(LocalAllCategoryFocus provides all) {
                    CategoryColumn(
                        categories = if (loaded) groups else emptyList(),
                        selectedId = Category.ALL,
                        onFocused = {},
                        onClick = {},
                    )
                }
            }
        }
        compose.settle(300)
        // Weit nach unten gescrollt, dann „Neustart“: Zustand wird gespeichert und wiederhergestellt,
        // die Gruppen laden dabei erst etwas später (wie am Gerät mit großer Datenbank).
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Gruppe 70"))
        compose.settle(300)
        loaded = false
        restore.emulateSavedInstanceStateRestore()
        compose.settle(200)
        loaded = true
        compose.settle(400)

        compose.runOnIdle { all.requestFocus() }
        compose.settle(300)
        compose.onNode(isFocused() and hasText("Alle Sender")).assertExists()
        compose.onNodeWithText("Alle Sender").assertExists()
    }
}
