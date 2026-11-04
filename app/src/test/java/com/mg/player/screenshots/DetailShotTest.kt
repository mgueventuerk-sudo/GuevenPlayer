package com.mg.player.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.ui.detail.MovieDetailScreen
import com.mg.player.ui.detail.SeriesDetailScreen
import com.mg.player.ui.setup.CountryFilterScreen
import com.mg.player.ui.setup.SetupScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Detailseiten sowie Einrichtung und Länderauswahl – dunkel und hell. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DetailShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private lateinit var data: ShowcaseData

    @Before
    fun setUp() {
        data = Showcase.prepare(server, container)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun movieDetail() {
        compose.setContent { TvHost(container) { MovieDetailScreen(data.movieId) } }
        compose.waitForText("Abspielen")
        compose.waitForText("Forscherin")
        compose.settle()
        compose.shootBothThemes("08_film_details")
    }

    @Test
    fun seriesDetail() {
        compose.setContent { TvHost(container) { SeriesDetailScreen(data.seriesId) } }
        compose.waitForText("Staffel 2")
        compose.settle()
        compose.shootBothThemes("09_serien_details")
    }

    @Test
    fun setup() {
        compose.setContent { TvHost(container) { SetupScreen(onFinished = {}, firstSetup = true) } }
        compose.waitForText("Manuell eingeben")
        compose.settle()
        compose.shootBothThemes("10_einrichtung")
    }

    @Test
    fun countryFilter() {
        compose.setContent { TvHost(container) { CountryFilterScreen(firstRun = true, onDone = {}) } }
        compose.waitForText("Welche Länder")
        compose.settle()
        compose.shootBothThemes("11_laenderauswahl")
    }
}
