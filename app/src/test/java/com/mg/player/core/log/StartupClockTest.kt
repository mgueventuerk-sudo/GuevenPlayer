package com.mg.player.core.log

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Im Fire-TV-Protokoll stand „Start bis Startbildschirm: 86 606 484 ms“ (24 Stunden): Das VPN hält den Prozess
 * als Dienst wach, der Nutzer öffnete die App einen Tag später. Gemessen wurde das Prozessalter, nicht der Start.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StartupClockTest {

    @Test
    fun coldStartReportsProcessTime() {
        StartupClock.uiCreated()
        val line = StartupClock.startupLine()
        assertTrue(line, line.startsWith("Start bis Startbildschirm:"))
    }

    @Test
    fun warmStartReportsUiTimeAndProcessAge() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        StartupClock.uiCreated()
        val line = StartupClock.startupLine()
        assertTrue(line, line.startsWith("Oberfläche bis Startbildschirm:"))
        assertTrue(line, line.contains("Prozess lief schon 30 min"))
    }

    /** TTFD-Zeile („Inhalt sichtbar“) folgt derselben Kalt-/Warmstart-Unterscheidung. */
    @Test
    fun fullyDrawnLineUsesSameStartKind() {
        StartupClock.uiCreated()
        assertTrue(StartupClock.fullyDrawnLine().startsWith("Start bis Inhalt sichtbar:"))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        StartupClock.uiCreated()
        assertTrue(StartupClock.fullyDrawnLine().startsWith("Oberfläche bis Inhalt sichtbar:"))
    }
}
