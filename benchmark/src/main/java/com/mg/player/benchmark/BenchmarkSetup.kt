package com.mg.player.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Richtet die Test-Playlist für die Macrobenchmarks ein, über die echte Setup-Oberfläche (SetupScreen.kt) - nicht
 * über einen Shortcut in die Datenbank, damit der komplette, echte Importpfad mitgemessen bleibt. Ohne das
 * scheiterte jeder Benchmark sofort an seiner ersten assertNotNull-Prüfung (siehe BenchmarkFixture.kt).
 *
 * UNGEPRÜFT: Diese Automatisierung wurde nie auf einem echten/emulierten Gerät ausgeführt (kein Gerätezugriff in
 * dieser Umgebung) - die Feldbezeichnungen ("Server-URL", "Benutzername", "Passwort", "HTTP zulassen",
 * "Speichern & laden") stammen aus app/src/main/java/com/mg/player/ui/setup/SetupScreen.kt und aus bestehenden,
 * echt laufenden Tests (app/src/test/.../screenshots/DialogShotTest.kt), aber die genaue Bildschirmfolge beim
 * App-Start (z. B. ob vor dem Formular noch ein Begrüssungsschritt kommt) ist nicht bestätigt.
 */
internal object BenchmarkSetup {
    private const val APP_PACKAGE = "com.mg.player"
    private const val SETUP_TIMEOUT_MS = 15_000L
    private const val IMPORT_TIMEOUT_MS = 60_000L

    /**
     * Setzt die App vollständig zurück und richtet sie über die echte Oberfläche mit [fixtureUrl] ein. Danach
     * steht "Live TV" sichtbar auf dem Startbildschirm - derselbe Erfolgs-Nachweis, den die bestehenden
     * Benchmarks (topLevelNavigation etc.) ohnehin schon verlangen.
     */
    fun ensure(device: UiDevice, fixtureUrl: String) {
        device.executeShellCommand("pm clear $APP_PACKAGE")
        device.executeShellCommand("am start -n $APP_PACKAGE/.ui.MainActivity")
        device.wait(Until.hasObject(By.desc("Server-URL")), SETUP_TIMEOUT_MS)

        device.findObject(By.desc("Server-URL"))?.apply {
            click()
            text = fixtureUrl
        }
        device.findObject(By.desc("Benutzername"))?.apply {
            click()
            text = "demo"
        }
        device.findObject(By.desc("Passwort"))?.apply {
            click()
            text = "demo"
        }
        // Fixture läuft unverschlüsselt auf Loopback - ohne diese Freigabe lehnt die App HTTP grundsätzlich ab.
        device.findObject(By.text("HTTP zulassen"))?.click()
        device.findObject(By.text("Speichern & laden"))?.click()

        val ready = device.wait(Until.hasObject(By.text("Live TV")), IMPORT_TIMEOUT_MS)
        check(ready) { "Einrichtung über die Oberfläche hat nicht zu 'Live TV' auf dem Startbildschirm geführt" }
    }

    fun uiDevice(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
}
