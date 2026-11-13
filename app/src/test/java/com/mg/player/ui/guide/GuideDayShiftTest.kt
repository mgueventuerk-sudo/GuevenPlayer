package com.mg.player.ui.guide

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bisher ließ sich das EPG-Fenster im TV-Guide nur stundenweise verschieben (shiftWindow(1)/(-1)) - eine
 * mehrtägige Vorschau (z. B. Sport am Wochenende) brauchte viele Tipps. GuideScreen bietet jetzt zusätzlich
 * shiftWindow(±24) als "1 Tag"-Knopf; diese Werte sind rein lokale Arithmetik, kein neuer Mechanismus.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GuideDayShiftTest {

    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Test
    fun shiftingByTwentyFourHoursMovesTheWindowByOneDay() {
        val vm = GuideViewModel(container)
        val before = vm.windowStart.value
        vm.shiftWindow(24)
        assertEquals(before + 24 * GuideViewModel.HOUR, vm.windowStart.value)
        vm.shiftWindow(-24)
        assertEquals(before, vm.windowStart.value)
    }

    @Test
    fun dayShiftsStayWithinTheExistingSevenDayWindowClamp() {
        val vm = GuideViewModel(container)
        repeat(20) { vm.shiftWindow(-24) }
        val now = System.currentTimeMillis()
        assertTrue("Sollte nicht weiter als 7 Tage zurück gehen", vm.windowStart.value >= now - 7 * GuideViewModel.DAY)
    }
}
