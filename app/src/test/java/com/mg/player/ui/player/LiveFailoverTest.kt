package com.mg.player.ui.player

import com.mg.player.core.util.StreamMemory
import com.mg.player.domain.model.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Abläufe des Auto-Failovers, wie sie in der Prüfung aufgefallen sind. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveFailoverTest {

    private val scope = TestScope()
    private val memory = StreamMemory()
    private var online = true
    private val switches = mutableListOf<Int>()
    private var failSwitch = false
    private lateinit var failover: LiveFailover

    private fun variant(id: String, quality: String) =
        Channel(id, "ZDF $quality", null, "http://x/$id", "1:L:1", "zdf", null, null, null, playlistId = 1, groupKey = "zdf|de", quality = quality)

    private val variants = listOf(variant("a", "FHD"), variant("b", "HD"), variant("c", "SD"))

    private fun playable(index: Int) = Playable(
        kind = PlayableKind.LIVE, id = "a", title = "ZDF", subtitle = null, url = "http://x/${variants[index].id}",
        startPositionMs = 0, progressKey = null, channel = variants[0], variants = variants, variantIndex = index,
    )

    private fun setUp() {
        failover = LiveFailover(scope, memory, { _, index ->
            if (failSwitch) throw IllegalStateException("Adresse nicht auflösbar")
            switches += index
            failover.onPrepared(playable(index))
        }, {}) { online }
    }

    @Test
    fun noPictureAfterTimeoutSwitchesToNextVariant() {
        setUp()
        failover.onPrepared(playable(0))
        scope.advanceTimeBy(2_900)
        assertEquals(emptyList<Int>(), switches)
        scope.advanceTimeBy(200)
        assertEquals("Nach 3 s ohne Bild zur nächsten Quelle", listOf(1), switches)
    }

    @Test
    fun bufferingLongerThanThreeSecondsSwitchesButIsNotRemembered() {
        setUp()
        failover.onPrepared(playable(0))
        failover.onPlaying()
        failover.onBuffering()
        scope.advanceTimeBy(2_500)
        failover.onPlaying() // kurzer Hänger: läuft wieder
        scope.advanceTimeBy(5_000)
        assertEquals(emptyList<Int>(), switches)
        failover.onBuffering()
        scope.advanceTimeBy(3_100)
        assertEquals(listOf(1), switches)
        failover.onPlaying()
        assertEquals("a", memory[1, "zdf|de"])
    }

    @Test
    fun pauseDoesNotCountAsStall() {
        setUp()
        failover.onPrepared(playable(0))
        failover.onPlaying()
        failover.onBuffering()
        failover.onIdle()
        scope.advanceTimeBy(10_000)
        assertEquals(emptyList<Int>(), switches)
    }

    @Test
    fun watchdogDoesNotSwitchWhileOffline() {
        setUp()
        online = false
        failover.onPrepared(playable(0))
        scope.advanceTimeBy(10_000)
        assertEquals(emptyList<Int>(), switches)
    }

    @Test
    fun variantAfterHttpErrorIsRemembered() {
        setUp()
        failover.onPrepared(playable(0))
        assertTrue(failover.onStreamProblem(remember = true))
        scope.runCurrent()
        failover.onPlaying()
        assertEquals("b", memory[1, "zdf|de"])
    }

    /** Internet am Router weg, WLAN verbunden: nach der Störung nicht dauerhaft auf der schlechteren Quelle bleiben. */
    @Test
    fun variantAfterConnectionErrorsIsNotRemembered() {
        setUp()
        failover.onPrepared(playable(0))
        assertTrue(failover.onStreamProblem(remember = false))
        scope.runCurrent()
        failover.onPlaying()
        assertNull(memory[1, "zdf|de"])
    }

    @Test
    fun lastVariantCannotFailOver() {
        setUp()
        failover.onPrepared(playable(2))
        assertFalse(failover.onStreamProblem())
    }

    /** Ein fehlgeschlagener Wechsel darf den Failover nicht dauerhaft blockieren (früher blieb "busy" hängen). */
    @Test
    fun failedSwitchReleasesBusyFlag() {
        setUp()
        failSwitch = true
        failover.onPrepared(playable(0))
        assertTrue(failover.onStreamProblem())
        scope.runCurrent()
        assertFalse(failover.busy)
    }

    @Test
    fun rememberedVariantSurvivesRestartAndExpires() {
        val saved = HashMap<String, String>()
        var now = 1_000_000L
        val store = object : StreamMemory.Store {
            override fun all() = HashMap(saved)
            override fun put(key: String, value: String) {
                saved[key] = value
            }
            override fun remove(keys: Collection<String>) {
                keys.forEach { saved.remove(it) }
            }
        }
        StreamMemory(store) { now }.remember(1, "zdf|de", "b")
        val restarted = StreamMemory(store) { now }
        assertEquals("b", restarted[1, "zdf|de"])
        now += StreamMemory.MAX_AGE_MS + 1
        assertNull("Nach 3 Tagen wieder die erste Qualität probieren", restarted[1, "zdf|de"])
        assertTrue(saved.isEmpty())
    }
}
