package com.mg.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Live-Mosaik: 4 oder 9 Sender einer Gruppe ab [startChannelId] als Standbilder, alle 30 s neu ([LiveStillScanner],
 * Regeln in [MosaicPlan]).
 */
class MosaicViewModel(
    private val container: AppContainer,
    private val categoryId: String,
    private val startChannelId: String?,
) : ViewModel() {

    val scanner = LiveStillScanner(container, viewModelScope, logTag = "Mosaik")

    private val _size = MutableStateFlow(MosaicPlan.defaultSize(scanner.lowTier))
    val size: StateFlow<Int> = _size.asStateFlow()

    private var active = false

    /** Bildschirm sichtbar (true) bzw. verdeckt/pausiert (false): Ohne Sichtbarkeit keine Verbindung zum Anbieter. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) start(reload = false) else scanner.stop()
    }

    fun toggleSize() {
        _size.value = MosaicPlan.toggled(_size.value)
        if (active) start(reload = true)
    }

    private fun start(reload: Boolean) {
        scanner.start(roundMs = MosaicPlan.ROUND_MS, reload = reload) {
            val playlistId = container.settingsRepository.settings.first().activePlaylistId ?: return@start emptyList()
            val live = container.liveRepository
            MosaicPlan.pick(live.channelIds(playlistId, categoryId), startChannelId, _size.value).mapNotNull { live.channel(it) }
        }
    }

    override fun onCleared() {
        scanner.release()
    }
}
