package com.mg.player.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Release-Variante messen, nicht die langsamere Debug-Oberfläche. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class AppMacrobenchmark {

    @get:Rule
    val benchmark = MacrobenchmarkRule()

    @Test
    fun coldStartWithoutCompilation() = startup(CompilationMode.None())

    @Test
    fun coldStartWithBaselineProfile() = startup(CompilationMode.Partial())

    /**
     * Grober UI-Rundgang durch die Hauptbereiche. Für aussagekräftige Ergebnisse vorher auf beiden Geräten
     * denselben Testzugang mit derselben Sender-/Film-/Serienmenge einrichten.
     */
    @Test
    fun topLevelNavigation() {
        benchmark.measureRepeated(
            packageName = APP_PACKAGE,
            metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            val liveTab = device.wait(Until.findObject(By.text("Live TV")), HOME_TIMEOUT_MS)
            assertNotNull(
                "Benchmark benötigt eine eingerichtete Test-Playlist; erwartet Home mit dem Tab 'Live TV'.",
                liveTab,
            )
            listOf("Live TV", "Filme", "Serien", "Suche").forEach { label ->
                val tab = device.wait(Until.findObject(By.text(label)), HOME_TIMEOUT_MS)
                assertNotNull("Hauptbereich '$label' wurde nicht gefunden.", tab)
                tab!!.click()
                device.waitForIdle()
            }
        }
    }

    /** Misst den Weg vom sichtbaren Testkanal bis zur laufenden Wiedergabe. */
    @Test
    fun playerStartsFromFixtureChannel() {
        benchmark.measureRepeated(
            packageName = APP_PACKAGE,
            metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                device.executeShellCommand("am force-stop $APP_PACKAGE")
                startActivityAndWait()
                val liveTab = device.wait(Until.findObject(By.text("Live TV")), HOME_TIMEOUT_MS)
                assertNotNull("Benchmark benötigt eine eingerichtete Test-Playlist.", liveTab)
                liveTab!!.click()
                val channel = device.wait(Until.findObject(By.text(TEST_CHANNEL)), HOME_TIMEOUT_MS)
                assertNotNull("Testkanal '$TEST_CHANNEL' fehlt in der Playlist.", channel)
            },
        ) {
            val channel = device.wait(Until.findObject(By.text(TEST_CHANNEL)), HOME_TIMEOUT_MS)
            assertNotNull("Testkanal '$TEST_CHANNEL' ist nicht sichtbar.", channel)
            channel!!.click()
            val playing = device.wait(Until.findObject(By.desc("Pause")), PLAYER_TIMEOUT_MS)
            assertNotNull("Teststream '$TEST_CHANNEL' hat nicht mit der Wiedergabe begonnen.", playing)
        }
    }

    private fun startup(compilationMode: CompilationMode) {
        benchmark.measureRepeated(
            packageName = APP_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = compilationMode,
            iterations = 10,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            // TTFD: Die App meldet reportFullyDrawn, sobald Kategorien bzw. Startseiten-Reihen geladen sind.
            device.wait(Until.hasObject(By.textStartsWith("Alle ")), HOME_TIMEOUT_MS)
        }
    }

    /**
     * Testfall 2 (Overhaul-Prompt): 60 s durch eine lange Senderliste, Ziel < 5 % Janky Frames. Voraussetzung wie
     * oben: Test-Playlist mit mindestens 500 Sendern in „Alle Sender“.
     */
    @Test
    fun liveListScroll() {
        benchmark.measureRepeated(
            packageName = APP_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 3,
            startupMode = StartupMode.WARM,
            setupBlock = {
                startActivityAndWait()
                val all = device.wait(Until.findObject(By.text("Alle Sender")), HOME_TIMEOUT_MS)
                assertNotNull("Benchmark benötigt eine Test-Playlist mit 'Alle Sender'.", all)
                device.pressDPadRight()
                device.waitForIdle()
            },
        ) {
            repeat(SCROLL_STEPS) {
                device.pressDPadDown()
                Thread.sleep(SCROLL_PAUSE_MS)
            }
        }
    }

    private companion object {
        const val APP_PACKAGE = "com.mg.player"
        const val HOME_TIMEOUT_MS = 10_000L
        const val PLAYER_TIMEOUT_MS = 30_000L
        const val TEST_CHANNEL = "BENCHMARK_TEST_CHANNEL"
        const val SCROLL_STEPS = 240
        const val SCROLL_PAUSE_MS = 250L
    }
}
