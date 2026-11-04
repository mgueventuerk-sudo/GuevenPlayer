package com.mg.player.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Alle Icons lassen sich zeichnen – ruhend, aktiv (mit Animation) und statisch. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppIconsTest {

    @get:Rule
    val compose = createComposeRule()

    private val icons: List<AppIcon> = AppIcons::class.java.declaredFields
        .filter { AppIcon::class.java.isAssignableFrom(it.type) }
        .map {
            it.isAccessible = true
            it.get(AppIcons) as AppIcon
        }

    @Test
    fun familyIsComplete() {
        assertTrue("erwartet mindestens 55 Icons, gefunden ${icons.size}", icons.size >= 55)
        assertEquals("Icon-Namen müssen eindeutig sein", icons.size, icons.map { it.name }.toSet().size)
        icons.forEach { icon -> assertTrue("${icon.name} hat keine Teile", icon.parts.isNotEmpty()) }
    }

    @Test
    fun everyIconRendersInAllStates() {
        compose.setContent {
            PlayerTheme {
                Row {
                    icons.forEach { icon ->
                        AnimatedIcon(icon, active = false, contentDescription = "ruhend-${icon.name}")
                        AnimatedIcon(icon, active = true, contentDescription = "aktiv-${icon.name}")
                        StaticIcon(icon, contentDescription = "statisch-${icon.name}")
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        icons.forEach { icon ->
            listOf("ruhend", "aktiv", "statisch").forEach { state ->
                assertEquals(1, compose.onAllNodesWithContentDescription("$state-${icon.name}").fetchSemanticsNodes().size)
            }
        }
    }
}
