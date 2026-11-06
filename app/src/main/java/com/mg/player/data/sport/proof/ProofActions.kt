package com.mg.player.data.sport.proof

import com.mg.player.data.db.dao.SportDao
import com.mg.player.data.sport.SportRepository
import com.mg.player.data.sport.guide.GuideCatalog
import com.mg.player.data.sport.leagueOfEvent
import com.mg.player.domain.model.SportEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Beweise aus deiner Bedienung (Plan „Spiel-Finder“ H): Zuschauen, Wahl im Mosaik, „Falscher Sender“. */
class ProofActions(private val repository: SportRepository, private val dao: SportDao, private val proofs: ProofStore) {

    /**
     * Du schaust einen Sender seit 5 Minuten bzw. hast ihn im Mosaik gewählt: Für jedes laufende Spiel, für das er Kandidat
     * ist, gilt er als bewiesen – auf allen Playlists mit demselben Sender. [eventId]: nur dieses Spiel (Mosaik).
     */
    suspend fun confirm(channelId: String, channelName: String, by: String, eventId: String? = null) = withContext(Dispatchers.IO) {
        val hits = if (eventId == null) watched(channelId) else listOfNotNull(eventOf(eventId))
        if (hits.isEmpty()) return@withContext
        val key = GuideCatalog.nameKey(channelName)
        hits.forEach { event ->
            proofs.record(event.id, channelId, channelName, ProofEvaluator.Verdict.PROVEN, by)
            proofs.record(event.id, key, channelName, ProofEvaluator.Verdict.PROVEN, by)
        }
        repository.rematch(hits)
    }

    /** Zuschauen: alle laufenden Spiele, für die dieser Sender schon Kandidat ist. */
    private suspend fun watched(channelId: String): List<SportEvent> {
        val live = repository.board.value?.live.orEmpty().map { it.event }
        if (live.isEmpty()) return emptyList()
        val ids = dao.matchesFor(live.map { it.id }).filter { it.channelId == channelId }.mapTo(HashSet()) { it.eventId }
        return live.filter { it.id in ids }
    }

    /**
     * Mosaik „Sender suchen“: Der gewählte Sender muss kein Kandidat sein (er wurde ja gezielt gewählt). Die Suche startet
     * auch bei kommenden Spielen und bei Spielen ganz ohne Sender – die stehen nicht in [SportRepository.board].live.
     */
    private suspend fun eventOf(eventId: String): SportEvent? {
        val board = repository.board.value
        val listed = (board?.live.orEmpty() + board?.upcoming?.values?.flatten().orEmpty()).map { it.event }
        return listed.firstOrNull { it.id == eventId } ?: repository.unmatched.first().firstOrNull { it.id == eventId }
    }

    /** „Falscher Sender“ im Spiel-Dialog: für dieses Spiel widerlegt und für den Wettbewerb gesperrt. */
    suspend fun markWrong(event: SportEvent, channelId: String, channelName: String) = withContext(Dispatchers.IO) {
        val key = GuideCatalog.nameKey(channelName)
        proofs.record(event.id, channelId, channelName, ProofEvaluator.Verdict.OTHER, ProofHunter.BY_WRONG)
        proofs.block(leagueOfEvent(event.id), channelId)
        proofs.block(leagueOfEvent(event.id), key)
        repository.rematch(listOf(event))
    }
}
