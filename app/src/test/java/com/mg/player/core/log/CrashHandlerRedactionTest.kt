package com.mg.player.core.log

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regressionsschutz für den Crash-Log-Passwort-Leak-Fund: installCrashHandler() (AppLog.kt) schrieb
 * throwable.message und den Stacktrace bisher roh in last_crash.txt/app.log, ohne den LogRedactor-Durchlauf, den
 * jeder andere Schreibpfad hat (write(), Audit A-09). CrashReport.kt reicht den rohen Text beim Teilen weiter -
 * ein Nutzer hätte sein Xtream-Passwort (steht laut XtreamApi.kt immer als Klartext-Query-Parameter/Pfadsegment
 * in der Request-URL) unwissentlich mitgeteilt, wenn eine Exception-Message diese URL enthielt.
 *
 * Löst den echten, von AppLog.init() installierten Handler aus - mit einer Exception-Message, wie sie im echten
 * Betrieb vorkommt (Zugangsdaten als URL-Parameter) - und prüft, dass das Passwort jetzt in BEIDEN Pfaden
 * geschwärzt ankommt.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CrashHandlerRedactionTest {

    @Before
    fun setUp() {
        AppLog.init(ApplicationProvider.getApplicationContext<PlayerApp>())
        AppLog.clear()
        AppLog.awaitWritten()
    }

    @Test
    fun crashMarkerNoLongerLeaksThePassword() {
        val secret = "geheim123"
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        checkNotNull(handler) { "AppLog.init() muss einen Handler installiert haben" }
        val thrown = IllegalStateException(
            "Verbindung zu http://panel.example.com/player_api.php?username=max&password=$secret fehlgeschlagen",
        )

        handler.uncaughtException(Thread.currentThread(), thrown)

        val crashText = AppLog.takeLastCrash()
        checkNotNull(crashText) { "Crash-Marker wurde nicht geschrieben" }
        assertFalse(
            "Regression: last_crash.txt enthält das Passwort wieder im Klartext",
            crashText.contains(secret),
        )
    }

    @Test
    fun reportStillRedactsThePassword() {
        val secret = "geheim456"
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        checkNotNull(handler)
        val thrown = IllegalStateException(
            "Verbindung zu http://panel.example.com/player_api.php?username=max&password=$secret fehlgeschlagen",
        )

        handler.uncaughtException(Thread.currentThread(), thrown)
        AppLog.awaitWritten()
        val report = AppLog.report()

        assertFalse("AppLog.report() (write()-Pfad) muss weiterhin redigieren", report.contains(secret))
    }
}
