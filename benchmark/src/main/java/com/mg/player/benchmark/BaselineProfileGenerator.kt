package com.mg.player.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan B1 (3.24): Baseline-Profil für den App-Code. Bis 3.23 enthielt die APK nur die Profile der Bibliotheken; im
 * Labor wurde beim Start viel per JIT übersetzt, Voll-AOT war 22 % schneller bis „Inhalt sichtbar“.
 *
 * Erzeugen (Emulator/Gerät mit root, eingerichtete Test-Playlist):
 * `./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mg.player.benchmark.BaselineProfileGenerator`
 * Ergebnis `*-baseline-prof.txt` aus build/outputs/connected_android_test_additional_output nach
 * app/src/main/baseline-prof.txt kopieren.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startListZapAndSections() = rule.collect(packageName = APP_PACKAGE, maxIterations = 3, stableIterations = 2) {
        pressHome()
        startActivityAndWait()
        // Startpfad bis „Inhalt sichtbar“
        device.wait(Until.hasObject(By.textStartsWith("Alle ")), TIMEOUT_MS)
        // Gruppen, Senderliste, Scrollen
        device.pressDPadDown()
        device.waitForIdle()
        device.pressDPadRight()
        repeat(SCROLL_STEPS) { device.pressDPadDown() }
        device.waitForIdle()
        // Player und Zappen
        device.pressDPadCenter()
        device.wait(Until.hasObject(By.desc("Pause")), PLAYER_TIMEOUT_MS)
        repeat(ZAPS) {
            device.pressDPadDown()
            Thread.sleep(ZAP_PAUSE_MS)
        }
        device.pressBack()
        device.waitForIdle()
        // Weitere Bereiche über die Leiste (Sport, Filme, Serien, TV-Guide)
        device.pressDPadUp()
        device.pressDPadUp()
        repeat(SECTIONS) {
            device.pressDPadRight()
            Thread.sleep(SECTION_PAUSE_MS)
        }
    }

    private companion object {
        const val APP_PACKAGE = "com.mg.player"
        const val TIMEOUT_MS = 60_000L
        const val PLAYER_TIMEOUT_MS = 30_000L
        const val SCROLL_STEPS = 30
        const val ZAPS = 3
        const val ZAP_PAUSE_MS = 6_000L
        const val SECTIONS = 4
        const val SECTION_PAUSE_MS = 4_000L
    }
}
