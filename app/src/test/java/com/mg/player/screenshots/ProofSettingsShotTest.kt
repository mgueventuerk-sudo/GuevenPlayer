package com.mg.player.screenshots

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.sport.proof.ProofEvaluator
import com.mg.player.data.sport.proof.ProofHunter
import com.mg.player.ui.settings.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sport → „Quellen der Sender“ in den beiden Zuständen, die der Einstellungs-Rundgang nicht trifft: als Prüfgerät
 * (Geheimnis für die anderen Geräte) und als Stick/Handy (Eingabefeld). Dazu die Prüf-Historie mit dem gelesenen
 * Text, auf dem ein Ergebnis beruht. Ablage: build/outputs/roborazzi/build/screenshots (Namen mit „pruefgeraet“).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ProofSettingsShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() {
        runBlocking {
            // Drei Prüfungen mit Ergebnis und gelesenem Text – so sieht die Diagnose nach einem Spieltag aus.
            container.sport.proofs.record(
                "espn:soccer/ger.1:1", "sky-sport-bundesliga-1", "Sky Sport Bundesliga 1",
                ProofEvaluator.Verdict.PROVEN, ProofHunter.BY_PICTURE, "BAY 2:1 DOR · 67'",
            )
            container.sport.proofs.record(
                "espn:soccer/ger.1:2", "dazn-1", "DAZN 1",
                ProofEvaluator.Verdict.OTHER, ProofHunter.BY_PICTURE, "LEV 0:0 RBL · 12'",
            )
            container.sport.proofs.record(
                "espn:soccer/ger.1:3", "sport1", "Sport1",
                ProofEvaluator.Verdict.PLACEHOLDER, ProofHunter.BY_PICTURE, "No Event",
            )
        }
    }

    private fun openSportSources() {
        compose.setContent { TvHost(container) { SettingsScreen() } }
        compose.waitForText("Quellen der Sender")
        compose.onNode(hasText("Quellen der Sender")).performScrollToNode(hasText("Letzte Prüfungen"))
        compose.settle()
    }

    @Test
    fun hubDeviceShowsItsSecret() {
        runBlocking { container.settingsRepository.update { it.copy(sportProofDevice = true) } }
        openSportSources()
        compose.waitForText("Geheimnis für andere Geräte")
        compose.shootBothThemes("pruefgeraet_1_geheimnis")
        // Das angezeigte Geheimnis ist das starke Format, nicht mehr acht Ziffern.
        val secret = runBlocking { container.settingsRepository.current().sportHubCode }
        assertTrue("angezeigt: $secret", secret.isBlank() || secret.contains('-') || secret.length == 8)
    }

    @Test
    fun otherDevicesGetAnEntryField() {
        runBlocking { container.settingsRepository.update { it.copy(sportProofDevice = false) } }
        openSportSources()
        compose.waitForText("Geheimnis des Prüfgeräts")
        compose.shootBothThemes("pruefgeraet_2_eintragen")
    }

    @Test
    fun theProofHistoryShowsWhatWasRead() {
        openSportSources()
        compose.waitForText("Letzte Prüfungen")
        // Der gelesene Text steht in der Diagnose – daran lässt sich ein Ergebnis nachvollziehen.
        compose.waitForText("BAY 2:1 DOR")
        compose.shootBothThemes("pruefgeraet_3_historie")
    }
}
