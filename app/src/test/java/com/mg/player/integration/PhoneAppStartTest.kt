package com.mg.player.integration

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.screenshots.PHONE_PORTRAIT
import com.mg.player.screenshots.settle
import com.mg.player.screenshots.waitForText
import com.mg.player.ui.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Die echte App (MainActivity → AppRoot) auf einem Handy starten – wie nach der Installation: Touchscreen ja,
 * Leanback nein, noch kein Zugang eingerichtet. Bedient wird ausschließlich mit Fingertipps.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34, 35, 36], qualifiers = PHONE_PORTRAIT)
class PhoneAppStartTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        val pm = shadowOf(ApplicationProvider.getApplicationContext<Context>().packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        pm.setSystemFeature(PackageManager.FEATURE_LEANBACK, false)
        pm.setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForText("Willkommen")
        compose.settle()
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun firstStartShowsPhoneSetup() {
        compose.waitForText("Tippen zum Eingeben")
    }

    @Test
    fun tappingAFieldOpensItForTyping() {
        // Mitten aufs Feld tippen – dort liegt das eigentliche Textfeld.
        compose.onNodeWithContentDescription("Server-URL").performClick()
        compose.settle(500)
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("http://example.org:8080")
        compose.settle(300)
        compose.waitForText("http://example.org:8080")
    }

    @Test
    fun tappingThePlaceholderOpensTheFieldToo() {
        compose.onAllNodesWithText("Tippen zum Eingeben").onFirst().performClick()
        compose.settle(500)
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("Mein Anbieter")
        compose.waitForText("Mein Anbieter")
    }

    @Test
    fun typeSwitchAndRotationDoNotCrash() {
        compose.onAllNodesWithText("M3U-Link").onFirst().performClick()
        compose.settle(500)
        compose.waitForText("M3U")
        scenario?.recreate()
        compose.settle()
        compose.waitForText("Willkommen")
    }
}
