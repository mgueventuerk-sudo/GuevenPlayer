package com.mg.player.data.sport.proof

import com.mg.player.core.util.StreamMemory
import com.mg.player.data.sport.proof.ProofEvaluator.Verdict
import com.mg.player.domain.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Beweise, „Falscher Sender“ und die Regel „Freie Playlist“ (Plan „Spiel-Finder“ C/H, Tests 15, 16, 21, 23, 24). */
class ProofStoreTest {

    private class MapStore : StreamMemory.Store {
        val data = HashMap<String, String>()

        override fun all(): Map<String, String> = HashMap(data)

        override fun put(key: String, value: String) {
            data[key] = value
        }

        override fun remove(keys: Collection<String>) {
            keys.forEach(data::remove)
        }
    }

    private val backing = MapStore()

    @Test
    fun stationProofCountsForEveryPlaylistEventChannelOnlyForItself() {
        val store = ProofStore(backing) { 1_000L }
        store.record("e1", "dazn1", "DE: DAZN 1 HD", Verdict.PROVEN, ProofHunter.BY_PICTURE)
        // Andere Playlist, gleicher Sender: gleicher Schlüssel.
        assertEquals(Verdict.PROVEN, store.verdictFor("e1", listOf("7:L:99", "dazn1")))
        store.record("e1", "3:L:16", "DE| PRIME DE 016", Verdict.PLACEHOLDER, ProofHunter.BY_PICTURE)
        assertNull(store.verdictFor("e1", listOf("5:L:16", "primede016")))
        assertEquals(Verdict.PLACEHOLDER, store.verdictFor("e1", listOf("3:L:16")))
    }

    @Test
    fun survivesRestartAndBlocksPerCompetition() {
        val store = ProofStore(backing) { 1_000L }
        store.record("e1", "1:L:5", "Sky Sport 1", Verdict.PROVEN, ProofHunter.BY_WATCHING)
        store.block("espn:soccer/ger.1", "skysport1")
        val again = ProofStore(backing) { 2_000L }
        assertTrue(again.hasProof("e1"))
        assertEquals(listOf("1:L:5"), again.provenChannelIds("e1"))
        assertTrue(again.isBlocked("espn:soccer/ger.1", listOf("skysport1")))
        assertFalse(again.isBlocked("espn:soccer/eng.1", listOf("skysport1")))
    }

    @Test
    fun unknownIsNotStored() {
        val store = ProofStore(backing) { 1_000L }
        store.record("e1", "x", "X", Verdict.UNKNOWN, ProofHunter.BY_PICTURE)
        assertNull(store.verdictFor("e1", listOf("x")))
        assertTrue(store.recent().isEmpty())
    }

    /** Regel „Freie Playlist“: nur mit bekannter Grenze und freiem Platz. */
    @Test
    fun onlyFreePlaylists() {
        assertTrue(ProofHunter.hasRoom(ConnectionStatus(active = 0, max = 1)))
        assertFalse(ProofHunter.hasRoom(ConnectionStatus(active = 1, max = 1)))
        assertFalse(ProofHunter.hasRoom(ConnectionStatus(active = 0, max = 0)))
        assertFalse(ProofHunter.hasRoom(null))
    }

    @Test
    fun liveScoreParsing() {
        assertEquals(0 to 1, ProofHunter.goalsOf("0:1 · 23'"))
        assertEquals(23, ProofHunter.minuteOf("0:1 · 23'"))
        assertNull(ProofHunter.minuteOf("2:1 · Ende"))
        assertNull(ProofHunter.goalsOf(null))
    }
}
