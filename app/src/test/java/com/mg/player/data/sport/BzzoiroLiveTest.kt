package com.mg.player.data.sport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.core.network.HttpFetcher
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gegen den echten Dienst – nur von Hand: BZZOIRO_KEY=… ./gradlew testDebugUnitTest --tests "*BzzoiroLiveTest".
 * Der Schlüssel kommt nur aus der Umgebung und landet weder im Code noch im Bericht.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BzzoiroLiveTest {

    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @Test
    fun realLeaguesEventsAndStations() = runBlocking {
        val key = System.getenv("BZZOIRO_KEY")
        assumeTrue("BZZOIRO_KEY nicht gesetzt", !key.isNullOrBlank())
        SportEndpoints.bzzoiro = "https://sports.bzzoiro.com/api/v2"
        val source = BzzoiroSource(HttpFetcher(OkHttpClient()), token = { key!! })
        val leagues = source.leagues()
        val report = StringBuilder("Ligen: ${leagues.size}\n")

        // Umstellung wie in der App („Bzzoiro verbunden – … Ligen umgestellt“), mit allen ESPN-Ligen gefolgt.
        container.settingsRepository.update { it.copy(sportLeagues = EspnSource.LEAGUES.map { l -> l.key }.toSet(), bzzoiroToken = key!!) }
        val message = container.sport.repository.adoptBzzoiro()
        val followed = container.sport.repository.followed(container.settingsRepository.current())
        report.append("Meldung: $message\nBzzoiro-Ligen gefolgt: ${followed.filter { it.startsWith("bsd:") }.sorted()}\n")
        assertTrue("Süper Lig fehlt", "bsd:11" in followed)

        val now = System.currentTimeMillis()
        for (league in listOf("bsd:43", "bsd:5", "bsd:11", "bsd:1")) {
            val events = source.fetch(league, now - 12 * 3_600_000L, now + 7 * 86_400_000L)
            report.append("\n$league: ${events.size} Spiele, ${events.count { it.stations.isNotEmpty() }} mit Sender\n")
            events.take(10).forEach { e -> report.append("  ${e.title} (${e.status}): ${e.stations}\n") }
        }
        val text = report.toString()
        assertTrue(key!! !in text)
        println(text)
        File("build/bzzoiro-live.txt").writeText(text)
    }
}
