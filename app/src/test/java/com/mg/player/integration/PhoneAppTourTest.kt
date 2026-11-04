package com.mg.player.integration

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.screenshots.PHONE_PORTRAIT
import com.mg.player.screenshots.Showcase
import com.mg.player.screenshots.ShowcaseServer
import com.mg.player.screenshots.settle
import com.mg.player.screenshots.waitForText
import com.mg.player.ui.MainActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Rundgang durch die echte App am Handy (MainActivity → AppRoot) mit eingerichtetem Zugang: jeder Bereich,
 * jede Einstellungs-Unterseite, die Dialoge, Details und der Player – nur mit Fingertipps und Zurück-Geste.
 * Läuft unter Android 14, 15 und 16. Ein Absturz irgendwo auf dem Weg lässt den Test scheitern.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34, 35, 36], qualifiers = PHONE_PORTRAIT)
class PhoneAppTourTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val server = ShowcaseServer()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        val pm = shadowOf(context.packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        pm.setSystemFeature(PackageManager.FEATURE_LEANBACK, false)
        pm.setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        // Freier Zugang: sonst fragt der Player (richtigerweise) nach, ob trotz belegtem Zugang abgespielt werden soll.
        server.activeConnections = 0
        Showcase.prepare(server, container)
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForText("ZDF")
        compose.settle()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        server.shutdown()
    }

    private fun tap(description: String) {
        compose.onNode(hasClickAction() and hasContentDescription(description)).performClick()
        compose.settle()
    }

    private fun tapText(text: String) {
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.settle()
    }

    private fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.settle()
    }

    /** Bereich der Einstellungen im Menü öffnen. */
    private fun settingsPage(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performClick()
        compose.settle()
    }

    private fun openSetting(label: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(label))
        compose.settle(300)
        tapText(label)
    }

    @Test
    fun everySectionAndTopIcon() {
        launch()
        listOf("Guide" to "Jetzt", "Filme" to "Weiterschauen", "Serien" to "Weiterschauen", "Suche" to "Wonach suchst du?", "Live TV" to "ZDF")
            .forEach { (tab, expected) ->
                tap(tab)
                compose.waitForText(expected)
            }
        tap("Aufnahmen")
        compose.waitForText("Noch keine Aufnahmen")
        tap("Einstellungen")
        compose.waitForText("Darstellung")
        settingsPage("Darstellung")
        compose.waitForText("Erscheinungsbild")
        // Erscheinungsbild umschalten: Statusleiste und Farben ändern sich ohne Neustart.
        tapText("Hell")
        tapText("Dunkel")
    }

    @Test
    fun everySettingsSubScreenOpensAndCloses() {
        launch()
        tap("Einstellungen")
        compose.waitForText("Darstellung")
        listOf(
            Triple("System", "Fehlerprotokoll anzeigen", "Neueste Einträge"),
            Triple("System", "Sicherung erstellen", "Kennwort"),
            Triple("System", "Sicherung wiederherstellen", "Sicherung"),
            Triple("Inhalte", "Kategorien verwalten", "Standard-Reihenfolge"),
            Triple("Inhalte", "Favoriten sortieren", "Neue Favoriten kommen oben dazu"),
            Triple("Sport", "Sender korrigieren", "Sport: Sender korrigieren"),
            Triple("Zugänge", "Zugang hinzufügen", "Weiteren Zugang hinzufügen"),
        ).forEach { (page, row, expected) ->
            settingsPage(page)
            openSetting(row)
            compose.waitForText(expected)
            back()
            // Zurück in den Einstellungen – im selben Bereich; noch einmal Zurück führt ins Menü.
            compose.waitForText(row)
            back()
            compose.waitForText("Darstellung")
        }
        // Eine Ebene tiefer: Zugang → Länder & Sprachen → zurück auf die Seite des Zugangs → Bereich → Menü.
        settingsPage("Zugänge")
        openSetting("Mein IPTV")
        openSetting("Länder & Sprachen")
        compose.waitForText("Welche Länder")
        back()
        compose.waitForText("Zugang löschen")
        back()
        compose.waitForText("Zugang hinzufügen")
        back()
        compose.waitForText("Darstellung")
    }

    @Test
    fun accountSwitcherDialog() {
        launch()
        compose.onNode(hasText("M") and hasClickAction()).performClick()
        compose.settle()
        compose.waitForText("Zugang wechseln")
        back()
    }

    @Test
    fun movieDetailAndPlayerWithGestures() {
        launch()
        tap("Filme")
        tap("Nordlicht")
        compose.waitForText("Abspielen")
        tapText("Abspielen")
        compose.settle(2_000)
        // Player: quer, Vollbild. Antippen, Doppeltippen und Wischen dürfen nichts kaputt machen.
        scenario.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, it.requestedOrientation) }
        compose.onRoot().performTouchInput { click(center) }
        compose.settle(600)
        compose.onRoot().performTouchInput { doubleClick(Offset(width * 0.8f, height * 0.5f)) }
        compose.settle(600)
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f) }
        compose.settle(600)
        back()
        compose.waitForText("Abspielen")
        scenario.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, it.requestedOrientation) }
    }

    @Test
    fun liveChannelStartsPlayerAndReturns() {
        launch()
        tapText("ZDF")
        compose.settle(2_000)
        scenario.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, it.requestedOrientation) }
        back()
        compose.waitForText("Live TV")
    }

    @Test
    fun seriesDetailOpens() {
        launch()
        tap("Serien")
        tap("Stadt der Lichter")
        compose.waitForText("Staffel 1")
        tap("Zurück")
        compose.waitForText("Weiterschauen")
    }

    @Test
    fun crashFromLastRunIsOfferedForSharing() {
        File(context.filesDir, "logs").mkdirs()
        File(context.filesDir, "logs/last_crash.txt").writeText("12.10. 10:00 · IllegalStateException: Testfehler\n\tat x.y.z")
        launch()
        compose.waitForText("unerwartet beendet")
        compose.waitForText("IllegalStateException: Testfehler")
        tapText("Bericht teilen")
        var started: Intent? = null
        scenario.onActivity { started = shadowOf(it).nextStartedActivity }
        assertEquals(Intent.ACTION_CHOOSER, started?.action)
        val shared = started?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(shared.contains("IllegalStateException: Testfehler"))
        // Nur einmal: beim nächsten Start kein Hinweis mehr.
        assertTrue(!File(context.filesDir, "logs/last_crash.txt").exists())
    }
}
