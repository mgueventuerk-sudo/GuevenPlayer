package com.mg.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Standardwerte für neue Installationen – hier bewusst, nicht beiläufig, geändert. */
class SettingsDefaultsTest {

    @Test
    fun newInstallsDefaultToTheBrandAccentNotBlue() {
        // War AccentColor.BLUE: Logo und Markenzeichen sind rot-gelb, aber jeder Akzent in der echten
        // Oberfläche war blau - Marke und App wirkten wie zwei verschiedene Produkte. Bestehende Nutzer mit
        // einer gespeicherten Wahl sind davon nicht betroffen (die steht in der Datenbank, nicht hier).
        assertEquals(AccentColor.BRAND, AppSettings().accent)
    }
}
