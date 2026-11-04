package com.mg.player.screenshots

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.ui.home.HomeScreen
import com.mg.player.ui.theme.AppColors
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Schwache Sticks (Fire TV Stick Lite, „Effekte reduzieren“ automatisch an): ohne Schatten, Glanz und Hintergrundbild.
 * Der Fokus muss trotzdem aus der Entfernung erkennbar sein (heller Rand statt Schatten).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class StickShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() {
        Showcase.prepare(server, container)
        AppColors.reducedEffects = true
    }

    @After
    fun tearDown() {
        AppColors.reducedEffects = false
        server.shutdown()
    }

    @Test
    fun moviesWithPosterFocus() {
        compose.setContent { TvHost(container) { HomeScreen() } }
        compose.waitForText("ZDF")
        compose.settle()
        compose.clickable("Filme").pressOk()
        compose.settle()
        compose.waitForText("Nordlicht")
        compose.remote(Key.DirectionRight, times = 2)
        compose.shootBothThemes("40_stick_filme_poster_fokus")
    }
}
