package com.mg.player.integration

import androidx.paging.testing.asSnapshot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.repository.PlaylistInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Prüft einen Audit-Punkt gegen den echten Code: LiveRepositoryImpl.categories() liefert "Favoriten"
 * (Category.FAVORITES) für JEDE Senderliste, nicht nur für PhoneLiveScreen. GuideViewModel.categories und
 * .channels geben beide unverändert weiter (reine Pass-through-Aufrufe, siehe GuideViewModel.kt Zeile 61-71),
 * und PhoneGuideScreen zeigt dieselbe CategoryChipRow wie PhoneLiveScreen. Ein Favoriten-Filter im Guide fehlt
 * also nicht; dieser Test belegt das auf Ebene des Repositories, statt es nur zu behaupten.
 *
 * Bewusst ohne GuideViewModel selbst: dessen StateFlows laufen über viewModelScope (Dispatchers.Main.immediate) -
 * unter Robolectric mit einem simplen runBlocking() auf dem Hauptthread blockiert das sich gegenseitig, ohne
 * dass es hier zusätzliche Prüf-Aussagekraft gegenüber dem reinen Repository-Aufruf hätte.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GuideFavoritesFilterTest {

    private val xtream = FakeXtreamServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container

    @After
    fun tearDown() = xtream.shutdown()

    @Test
    fun liveCategoriesIncludeFavoritesAndFilterTheChannelList() = runBlocking {
        val pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = xtream.start(), username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
        val favoriteId = "$pid:L:105" // |DE| RTL
        container.playbackRepository.toggleFavorite(ContentType.LIVE, favoriteId)

        val categories = container.liveRepository.categories(pid).first()
        assertTrue(
            "Guide-Kategorien sollten Favoriten enthalten, gefunden: ${categories.map { it.name }}",
            categories.any { it.id == Category.FAVORITES },
        )

        val filtered = container.liveRepository.channels(pid, Category.FAVORITES).asSnapshot()
        assertEquals(listOf(favoriteId), filtered.map { it.id })
    }
}
