package com.mg.player.integration

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.screenshots.Showcase
import com.mg.player.screenshots.ShowcaseServer
import com.mg.player.screenshots.TvHost
import com.mg.player.screenshots.remote
import com.mg.player.screenshots.settle
import com.mg.player.screenshots.waitForText
import com.mg.player.ui.home.HomeScreen
import com.mg.player.ui.settings.SettingsScreen
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Zufälliger Steuerkreuz-Lauf über die Startseite: Nach jedem Tastendruck muss irgendein Element den Fokus haben.
 * Ein Druck, nach dem nichts mehr fokussiert ist, ist eine Sackgasse - die Fernbedienung wäre „tot“.
 * Fester Startwert, damit ein Fehlschlag reproduzierbar ist.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class FocusNeverLostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() {
        Showcase.prepare(server, container)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun randomDpadWalkNeverLeavesTheHomeScreenWithoutFocus() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("Quellen")
        walk()
    }

    @Test
    fun randomDpadWalkNeverLeavesSettingsWithoutFocus() {
        compose.setContent { TvHost(container) { SettingsScreen() } }
        compose.waitForText("Wiedergabe")
        walk()
    }

    private fun walk() {
        compose.settle()
        val keys = listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
        for (seed in SEEDS) {
            val random = Random(seed)
            val trail = ArrayList<String>()
            repeat(STEPS) {
                val key = keys[random.nextInt(keys.size)]
                trail += key.toString()
                compose.remote(key)
                val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().size
                assertTrue("Fokus verloren (Startwert $seed) nach ${trail.size} Tasten: ${trail.takeLast(12)}", focused > 0)
            }
        }
    }

    private companion object {
        const val STEPS = 80
        val SEEDS = listOf(7, 11, 23, 42, 99)
    }
}
