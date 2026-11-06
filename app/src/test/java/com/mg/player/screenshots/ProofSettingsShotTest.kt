package com.mg.player.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.sport.proof.ProofEvaluator
import com.mg.player.data.sport.proof.ProofHunter
import com.mg.player.ui.settings.SettingsPage
import com.mg.player.ui.settings.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sport → „Quellen der Sender“ in den Zuständen, die der Einstellungs-Rundgang nicht trifft: als Prüfgerät (Geheimnis
 * für die anderen Geräte) und als Stick/Handy (Eingabefeld), dazu die Prüf-Historie mit dem gelesenen Text, auf dem ein
 * Ergebnis beruht. Bedient wird wie am Fernseher: „Runter“ bis zum Bereich Sport, „Rechts“ in den Inhalt, dann weiter
 * „Runter“, bis die Zeile sichtbar ist. Ablage: build/outputs/roborazzi/build/screenshots (Namen mit „pruefgeraet“).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ProofSettingsShotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = ShowcaseServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Before
    fun setUp() {
        Showcase.prepare(server, container)
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

    @After
    fun tearDown() = server.shutdown()

    private fun visible(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Bereich Sport öffnen und im Inhalt nach unten, bis [text] auf dem Bildschirm steht. */
    private fun scrollTo(text: String) {
        compose.setContent { TvHost(container) { SettingsScreen() } }
        compose.waitForText(SettingsPage.ACCOUNTS.lead)
        compose.onAllNodesWithText(SettingsPage.ACCOUNTS.label)[0].requestFocus()
        compose.settle(500)
        // Linke Spalte: „Runter“ bis zum Bereich Sport.
        repeat(SettingsPage.entries.indexOf(SettingsPage.SPORT)) { compose.remote(Key.DirectionDown) }
        compose.waitForText(SettingsPage.SPORT.lead)
        // „Rechts“ in die Inhaltsspalte, dann weiter nach unten, bis die gesuchte Zeile zusammengesetzt ist.
        compose.remote(Key.DirectionRight)
        var steps = 0
        while (!visible(text) && steps < MAX_STEPS) {
            compose.remote(Key.DirectionDown)
            steps++
        }
        assertTrue("„$text“ nach $steps Schritten nicht erreicht", visible(text))
        compose.settle()
    }

    @Test
    fun hubDeviceShowsItsSecret() {
        runBlocking { container.settingsRepository.update { it.copy(sportProofDevice = true) } }
        scrollTo("Geheimnis für andere Geräte")
        compose.shootBothThemes("pruefgeraet_1_geheimnis")
        // Angezeigt wird das starke Format in Vierergruppen – oder ein alter 8-stelliger Code, der weiterläuft.
        val secret = runBlocking { container.settingsRepository.current().sportHubCode }
        assertTrue("angezeigt: $secret", secret.isBlank() || secret.contains('-') || secret.length == LEGACY_CODE)
    }

    @Test
    fun otherDevicesGetAnEntryField() {
        runBlocking { container.settingsRepository.update { it.copy(sportProofDevice = false) } }
        scrollTo("Geheimnis des Prüfgeräts")
        compose.shootBothThemes("pruefgeraet_2_eintragen")
    }

    @Test
    fun theProofHistoryShowsWhatWasRead() {
        // Der gelesene Text steht in der Diagnose – daran lässt sich ein Ergebnis nachvollziehen.
        scrollTo("BAY 2:1 DOR")
        compose.shootBothThemes("pruefgeraet_3_historie")
    }

    private companion object {
        const val MAX_STEPS = 40
        const val LEGACY_CODE = 8
    }
}
