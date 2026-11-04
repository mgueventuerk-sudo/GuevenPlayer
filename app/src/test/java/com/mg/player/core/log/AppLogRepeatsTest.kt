package com.mg.player.core.log

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Im Fire-TV-Protokoll waren 1 410 von 2 415 Zeilen dieselbe VPN-Meldung und 404 derselbe Sport-Satz. Das
 * Protokoll ist begrenzt (256 KB) – Abstürze und StrictMode-Meldungen fielen dadurch heraus.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLogRepeatsTest {

    @Before
    fun setUp() {
        AppLog.init(ApplicationProvider.getApplicationContext<PlayerApp>())
        AppLog.clear()
        AppLog.awaitWritten()
    }

    private fun lines(filter: String) = AppLog.read().lines().filter { it.contains(filter) }

    @Test
    fun identicalMessagesAreCollapsed() {
        repeat(200) { AppLog.log("VPN", "Getrennt: Verbindung zum VPN-Server abgerissen") }
        AppLog.awaitWritten()
        assertEquals(1, lines("Getrennt: Verbindung").size)
    }

    @Test
    fun differentMessagesAreAllKept() {
        repeat(20) { n -> AppLog.log("Sport", "$n Events aus 10 Ligen") }
        AppLog.awaitWritten()
        assertEquals(20, lines("Events aus 10 Ligen").size)
    }

    /** Nach dem Zeitfenster steht eine Zeile mit der Zahl der unterdrückten Wiederholungen. */
    @Test
    fun repeatCountIsCarriedToTheNextLine() {
        val base = 1_000_000L
        assertEquals(0, AppLog.Repeats.countFor("VPN", "Wieder verbunden", base))
        repeat(11) { n -> assertNull(AppLog.Repeats.countFor("VPN", "Wieder verbunden", base + n * 1_000L)) }
        assertEquals(11, AppLog.Repeats.countFor("VPN", "Wieder verbunden", base + AppLog.Repeats.WINDOW_MS + 1))
        // Andere Meldungen bleiben davon unberührt.
        assertEquals(0, AppLog.Repeats.countFor("VPN", "Standort eingerichtet", base + 1))
    }
}
