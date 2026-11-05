package com.mg.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mg.player.core.di.AppContainer
import com.mg.player.data.sport.proof.ProofHunter
import com.mg.player.domain.model.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
            sportEventId(categoryId)?.let { return@start sportCandidates(it) }
            val playlistId = container.settingsRepository.settings.first().activePlaylistId ?: return@start emptyList()
            val live = container.liveRepository
            MosaicPlan.pick(live.channelIds(playlistId, categoryId), startChannelId, _size.value).mapNotNull { live.channel(it) }
        }
    }

    fun confirmChoice(channel: Channel, eventId: String) {
        viewModelScope.launch { container.sport.proofActions.confirm(channel.id, channel.name, ProofHunter.BY_CHOICE, eventId) }
    }

    /** „Sender suchen“ (Plan „Spiel-Finder“ I): die wahrscheinlichsten Sender des Spiels statt einer Gruppe. */
    private suspend fun sportCandidates(eventId: String): List<Channel> {
        val board = container.sport.repository.board.value
        val event = board?.let { b -> (b.live + b.upcoming.values.flatten()).firstOrNull { it.event.id == eventId }?.event }
            ?: container.sport.repository.unmatched.first().firstOrNull { it.id == eventId }
            ?: return emptyList()
        return container.sport.candidateChannels(event, _size.value).mapNotNull { container.liveRepository.channel(it.id) }
    }

    override fun onCleared() {
        scanner.release()
    }
}

/** Mosaik für ein Spiel: Kategorie „sport:<Event-ID>“ (Plan „Spiel-Finder“ I). */
internal const val SPORT_MOSAIC_PREFIX = "sport:"

internal fun sportEventId(categoryId: String): String? = categoryId.takeIf { it.startsWith(SPORT_MOSAIC_PREFIX) }?.removePrefix(SPORT_MOSAIC_PREFIX)
