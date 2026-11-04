package com.mg.player.ui.player

import com.mg.player.data.combined.Combined
import com.mg.player.data.repository.Counterpart
import com.mg.player.domain.model.Category
import kotlinx.coroutines.launch

/**
 * Vor dem Start: Ist der Zugang voll belegt, fragt der Player nach (Gegenstück, trotzdem starten, abbrechen).
 * null = abgebrochen – der Player schließt sich.
 */
internal suspend fun PlayerViewModel.confirmStart(playable: Playable): Playable? {
    if (playable.kind == PlayableKind.RECORDING) return playable
    // „Alle Zugänge“: gefragt wird für die Quelle, die gleich startet (belegte Zugänge stehen schon hinten).
    return when (val choice = accounts.beforeStart(playable.activeItemId)) {
        AccountChoice.Continue -> playable

        AccountChoice.Cancel -> {
            eventSink.emit(PlayerEvent.Close)
            null
        }

        is AccountChoice.Switch -> playable.sourceIndexOf(choice.counterpart.itemId)?.let { resolver.variant(playable, it) }
            ?: container.requestFor(choice.counterpart, playable, playable.startPositionMs)?.let { resolver.resolve(it) } ?: playable
    }
}

/**
 * Stream ausgefallen (alle Wiederholungen erfolglos): denselben Inhalt über einen anderen Zugang anbieten.
 * Ohne Internet nicht – dort hilft kein anderer Zugang.
 */
internal fun PlayerViewModel.offerOtherAccount() {
    val playable = uiState.value.playable?.takeIf(::canOfferOtherAccount) ?: return
    scope.launch {
        val position = if (playable.isLive) 0 else (engineHolder.value?.positionMs?.takeIf { it > 0 } ?: lastKnownPosition)
        when (val choice = accounts.afterFailure(playable.activeItemId)) {
            is AccountChoice.Switch -> {
                if (switchWithinCombined(playable, choice.counterpart)) return@launch
                val request = container.requestFor(choice.counterpart, playable, position) ?: return@launch
                val next = resolver.resolve(request) ?: return@launch
                toast("Weiter über „${choice.counterpart.playlistName}“")
                next.channel?.let { live.loadChannelList(it.playlistId, Category.ALL) }
                switchingAccount = true
                startPlayable(next)
            }

            AccountChoice.Continue -> {
                guard?.onUserInterrupt()
                beginPlayback(playable, position)
            }

            AccountChoice.Cancel -> Unit
        }
    }
}

/** Innerhalb von „Alle Zugänge“: zur Quelle dieses Zugangs wechseln – Liste und Fortschritt bleiben. */
private suspend fun PlayerViewModel.switchWithinCombined(playable: Playable, counterpart: Counterpart): Boolean {
    val index = playable.sourceIndexOf(counterpart.itemId) ?: return false
    toast("Weiter über „${counterpart.playlistName}“")
    switchVariant(playable, index)
    return true
}

/** Kein Angebot bei Aufnahmen, ohne Internet und wenn der automatische Quellenwechsel schon alle Zugänge versucht hat. */
private fun PlayerViewModel.canOfferOtherAccount(playable: Playable): Boolean =
    playable.kind != PlayableKind.RECORDING && container.networkMonitor.status.value.online &&
        !(Combined.isCombinedItem(playable.id) && playable.autoAcrossAccounts)
