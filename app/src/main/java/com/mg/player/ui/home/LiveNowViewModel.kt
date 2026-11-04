package com.mg.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.Category
import com.mg.player.ui.live.LiveStillScanner
import com.mg.player.ui.live.MosaicPlan
import kotlinx.coroutines.flow.first

/**
 * „Läuft gerade“ (ab 3.41): Favoriten-Sender (sonst die ersten der Liste) mit echten, aktuellen Standbildern statt
 * Logos – man sieht, was läuft, bevor man umschaltet. Nutzt den Abtaster des Mosaiks: eine Verbindung, stumm,
 * Bilder alle 2 min, höchstens 5 Runden je Besuch der Startseite.
 */
class LiveNowViewModel(private val container: AppContainer) : ViewModel() {

    val scanner = LiveStillScanner(container, viewModelScope, logTag = "Läuft gerade")

    fun setActive(active: Boolean) {
        if (!active) {
            scanner.stop()
            return
        }
        scanner.start(roundMs = MosaicPlan.HOME_ROUND_MS, maxRounds = MosaicPlan.HOME_MAX_ROUNDS) {
            val playlistId = container.settingsRepository.settings.first().activePlaylistId ?: return@start emptyList()
            val live = container.liveRepository
            val count = MosaicPlan.homeCount(scanner.lowTier)
            val ids = live.channelIds(playlistId, Category.FAVORITES).ifEmpty { live.channelIds(playlistId, Category.ALL) }
            MosaicPlan.pick(ids, null, count).mapNotNull { live.channel(it) }
        }
    }

    override fun onCleared() {
        scanner.release()
    }
}
