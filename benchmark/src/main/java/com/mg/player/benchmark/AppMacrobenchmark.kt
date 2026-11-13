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

/** Den "benchmark"-Build-Typ messen (R8-optimiert wie Release, aber ohne isProfileable auf dem echten Release). */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class AppMacrobenchmark {

    @get:Rule
    val benchmark = MacrobenchmarkRule()

    /**
     * Setzt vor jedem Testfall eine eigene Test-Playlist über die echte Oberfläche auf (BenchmarkFixture.kt +
     * BenchmarkSetup.kt) - vorher brauchte jeder der Benchmarks unten eine von Hand eingerichtete Playlist und
     * scheiterte in CI sofort an der ersten assertNotNull-Prüfung, ohne je eine Zahl zu liefern.
     *
     * UNGEPRÜFT: Diese Methode (wie der ganze Fixture-Aufbau) lief nie auf einem echten/emulierten Gerät - siehe
     * Warnhinweis in BenchmarkSetup.kt.
     */
    private fun withFixturePlaylist(block: () -> Unit) {
        val fixture = BenchmarkFixture()
        val url = fixture.start()
        try {
            BenchmarkSetup.ensure(BenchmarkSetup.uiDevice(), url)
            block()
        } finally {
            fixture.shutdown()
        }
    }

    @Test
    fun coldStartWithoutCompilation() = withFixturePlaylist { startup(CompilationMode.None()) }

    @Test
    fun coldStartWithBaselineProfile() = withFixturePlaylist { startup(CompilationMode.Partial()) }

    /** Grober UI-Rundgang durch die Hauptbereiche. */
    @Test
    fun topLevelNavigation() = withFixturePlaylist {
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
    fun playerStartsFromFixtureChannel() = withFixturePlaylist {
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
     * Testfall 2 (Overhaul-Prompt): 60 s durch eine lange Senderliste, Ziel < 5 % Janky Frames.
     */
    @Test
    fun liveListScroll() = withFixturePlaylist {
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

    /**
     * Audit-Fund (Performance): PhoneSeriesDetail.kt/SeriesDetailScreen.kt berechnen die Staffelliste
     * (episodes.map{it.season}.distinct()) ungeschützt durch remember() bei jeder Rekomposition - misst, ob sich
     * das beim Scrollen durch eine Serie mit mehreren Staffeln/Folgen als Jank bemerkbar macht.
     */
    @Test
    fun seriesDetailScroll() = withFixturePlaylist {
        benchmark.measureRepeated(
            packageName = APP_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 3,
            startupMode = StartupMode.WARM,
            setupBlock = {
                device.executeShellCommand("am force-stop $APP_PACKAGE")
                startActivityAndWait()
                val seriesTab = device.wait(Until.findObject(By.text("Serien")), HOME_TIMEOUT_MS)
                assertNotNull("Benchmark benötigt eine eingerichtete Test-Playlist.", seriesTab)
                seriesTab!!.click()
                val series = device.wait(Until.findObject(By.text(TEST_SERIES)), HOME_TIMEOUT_MS)
                assertNotNull("Testserie '$TEST_SERIES' fehlt in der Playlist.", series)
                series!!.click()
                device.wait(Until.hasObject(By.textStartsWith("Folge ")), HOME_TIMEOUT_MS)
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
        const val TEST_SERIES = "Benchmark-Serie"
        const val SCROLL_STEPS = 240
        const val SCROLL_PAUSE_MS = 250L
    }
}
