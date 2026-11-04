package com.mg.player.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.live.LiveStillCard
import com.mg.player.ui.live.LiveStillScanner
import com.mg.player.ui.live.MosaicPlan
import com.mg.player.ui.player.EngineSurface
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppText
import com.mg.player.ui.theme.CardSize
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.theme.Spacing
import kotlinx.coroutines.delay
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

@Composable
internal fun LiveNowRow(onOpen: (Channel) -> Unit, onLongClick: (Channel) -> Unit) {
    val vm = appViewModel(key = "home-live-now") { LiveNowViewModel(it) }
    val tiles by vm.scanner.tiles.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val engine by vm.scanner.engine.collectAsStateWithLifecycle()
    // Nur solange die Zeile zu sehen ist: Scrollt sie aus dem Bild oder öffnet sich ein anderer Bildschirm, endet
    // die Verbindung zum Anbieter.
    LifecycleResumeEffect(Unit) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val list = tiles
    if (list.isNullOrEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
        Text(
            "Läuft gerade",
            Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
            style = AppText.title,
            color = AppColors.TextPrimary,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = Spacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap),
        ) {
            itemsIndexed(list, key = { _, tile -> tile.channel.id }, contentType = { _, _ -> "live-now" }) { index, tile ->
                LiveStillCard(
                    tile = tile,
                    live = scanning == index,
                    content = {
                        val current = engine
                        if (scanning == index && current != null) {
                            EngineSurface(current, Modifier.fillMaxWidth().aspectRatio(CardSize.LANDSCAPE_RATIO), onContainer = vm.scanner::onSurface)
                        }
                    },
                    onClick = { onOpen(tile.channel) },
                    onLongClick = { onLongClick(tile.channel) },
                    nowMs = nowMs,
                    modifier = Modifier.width(CardSize.LandscapeWidth).aspectRatio(CardSize.LANDSCAPE_RATIO),
                )
            }
        }
    }
}
