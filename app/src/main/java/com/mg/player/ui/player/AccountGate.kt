package com.mg.player.ui.player

import androidx.compose.runtime.Immutable
import com.mg.player.core.di.AppContainer
import com.mg.player.core.log.AppLog
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.data.combined.Combined
import com.mg.player.data.repository.Counterpart
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.playlistIdOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nachfrage an den Nutzer: Zugang belegt (vor dem Start) oder Stream ausgefallen (während der Wiedergabe). */
@Immutable
data class AccountPrompt(val title: String, val text: String, val alternatives: List<Counterpart>, val continueLabel: String)

/** Antwort auf [AccountPrompt]. */
sealed interface AccountChoice {
    data class Switch(val counterpart: Counterpart) : AccountChoice
    data object Continue : AccountChoice
    data object Cancel : AccountChoice
}

/**
 * Fragt nach, bevor ein anderer Zuschauer aus seinem Stream geworfen wird – und bietet bei einem Ausfall denselben
 * Inhalt über einen anderen Zugang an. Es wird immer gefragt, nie automatisch gewechselt.
 */
class AccountGate(private val container: AppContainer) {

    private val _prompt = MutableStateFlow<AccountPrompt?>(null)
    val prompt: StateFlow<AccountPrompt?> = _prompt.asStateFlow()
    private var answer: CompletableDeferred<AccountChoice>? = null

    /** Zugänge, die bei diesem Inhalt schon angeboten wurden (je Ausfall jeden höchstens einmal). */
    private val offered = HashSet<Long>()

    fun answer(choice: AccountChoice) {
        _prompt.value = null
        answer?.complete(choice)
        answer = null
    }

    /**
     * Vor dem Start: Ist der Zugang voll belegt (und nicht durch dieses Gerät), fragen.
     * Liefert die Wahl; [AccountChoice.Continue] auch, wenn nichts zu fragen ist.
     */
    suspend fun beforeStart(contentId: String): AccountChoice {
        val playlistId = playlistIdOf(contentId) ?: return AccountChoice.Continue
        val monitor = container.connectionMonitor
        if (monitor.recentlyUsedHere(playlistId)) return AccountChoice.Continue
        val status = monitor.checkNow(playlistId) ?: return AccountChoice.Continue
        // Der eigene Blick der Bildbeweis-Suche ist kein fremder Zuschauer: Er endet beim Start (startPlayable).
        val ownProbe = if (container.sport.probes.isRunning(playlistId)) 1 else 0
        if (!status.copy(active = status.active - ownProbe).full) return AccountChoice.Continue
        val name = playlistName(playlistId)
        AppLog.log("Zugang", "$name belegt (${status.label}) – Nachfrage vor dem Start")
        return ask(
            AccountPrompt(
                title = "Zugang belegt",
                text = "Über „$name“ schaut gerade jemand (${status.label}). Wenn du startest, wird diese Wiedergabe " +
                    "beim Anbieter wahrscheinlich beendet.",
                alternatives = freeAlternatives(contentId),
                continueLabel = "Trotzdem starten",
            ),
        )
    }

    /** Nach einem Ausfall (alle Wiederholungen erfolglos): denselben Inhalt über einen anderen Zugang anbieten. */
    suspend fun afterFailure(contentId: String): AccountChoice {
        val playlistId = playlistIdOf(contentId) ?: return AccountChoice.Cancel
        offered += playlistId
        val alternatives = freeAlternatives(contentId).filter { it.playlistId !in offered }
        if (alternatives.isEmpty()) return AccountChoice.Cancel
        alternatives.forEach { offered += it.playlistId }
        return ask(
            AccountPrompt(
                title = "Stream ausgefallen",
                text = "„${playlistName(playlistId)}“ liefert diesen Inhalt gerade nicht. Derselbe Inhalt ist in einem anderen Zugang vorhanden.",
                alternatives = alternatives,
                continueLabel = "Erneut versuchen",
            ),
        )
    }

    /** Neuer Inhalt (z. B. Senderwechsel): Zugänge dürfen wieder angeboten werden. */
    fun resetOffers() = offered.clear()

    private suspend fun ask(prompt: AccountPrompt): AccountChoice {
        val deferred = CompletableDeferred<AccountChoice>()
        answer?.complete(AccountChoice.Cancel)
        answer = deferred
        _prompt.value = prompt
        return deferred.await()
    }

    /** Gegenstücke, deren Zugang nicht voll belegt ist (unbekannter Stand zählt als frei), höchstens zwei. */
    private suspend fun freeAlternatives(contentId: String): List<Counterpart> {
        val statuses: Map<Long, ConnectionStatus> = container.connectionMonitor.status.value
        return runCatchingCancellable { container.counterpartFinder.forItem(contentId) }.getOrDefault(emptyList())
            // „Alle Zugänge“ selbst ist kein anderer Zugang.
            .filter { it.playlistId != Combined.ID && statuses[it.playlistId]?.full != true }
            .sortedBy { it.qualityRank }
            .take(2)
    }

    private suspend fun playlistName(id: Long): String = container.playlistRepository.getPlaylist(id)?.name ?: "Zugang"
}

/** Abspiel-Anfrage für das Gegenstück im anderen Zugang (Position wird übernommen). */
suspend fun AppContainer.requestFor(counterpart: Counterpart, original: Playable, positionMs: Long): PlayRequest? = when (original.kind) {
    PlayableKind.LIVE -> PlayRequest.Live(counterpart.itemId, Category.ALL)
    PlayableKind.MOVIE -> PlayRequest.Movie(counterpart.itemId, positionMs)
    PlayableKind.EPISODE -> seriesRepository.episode(counterpart.itemId)?.let { PlayRequest.Episode(it.id, it.seriesId, positionMs) }
    PlayableKind.CATCHUP, PlayableKind.RECORDING -> null
}
