package com.mg.player.data.importer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.PlayerApp
import com.mg.player.data.db.entity.CategoryEntity
import com.mg.player.data.db.entity.ChannelEntity
import com.mg.player.data.search.SearchText
import com.mg.player.data.sport.guide.KickoffCheck
import com.mg.player.data.sport.guide.SportChannelFinder
import com.mg.player.data.sport.toEntity
import com.mg.player.domain.model.EventStatus
import com.mg.player.domain.model.PlaylistType
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.repository.PlaylistInput
import com.mg.player.integration.FakeXtreamServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Event-Sender, die der Anbieter erst kurz vor dem Spiel umbenennt: 15 min vor Anstoß werden nur die Event- und
 * Sport-Gruppen neu abgefragt, die neuen Namen übernommen und das Spiel landet auf dem Event-Sender.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EventGroupRefreshTest {

    private val xtream = FakeXtreamServer()
    private val container get() = ApplicationProvider.getApplicationContext<PlayerApp>().container
    private var pid = 0L
    private val min = 60_000L
    @Before
    fun setUp() = runBlocking {
        xtream.liveCategories = xtream.liveCategories + ("6" to "DE | LIVE EVENTS")
        xtream.live = xtream.live + listOf(Triple("601", "DE: DAZN EVENT 1", "6"), Triple("602", "DE: DAZN EVENT 2", "6"))
        pid = container.playlistRepository.addPlaylist(
            PlaylistInput(name = "Test", type = PlaylistType.XTREAM, serverUrl = xtream.start(), username = "u", password = "p", allowInsecureHttp = true),
        )
        container.playlistRepository.refreshContent(pid) {}
    }

    @After
    fun tearDown() = xtream.shutdown()

    private suspend fun name(streamId: String) = container.database.channelDao().entity("$pid:L:$streamId")?.name

    /** Kurz vor dem Spiel: Event-Sender umbenannt, ein neuer dazu – und nebenbei ZDF umbenannt (keine Sportgruppe). */
    private fun providerRenamesBeforeKickoff() {
        xtream.live = xtream.live.map { (id, n, cat) ->
            when (id) {
                "601" -> Triple(id, "DE: DAZN EVENT 1: Galatasaray – Fenerbahçe", cat)
                "101" -> Triple(id, "DE: ZDF HD (neu)", cat)
                else -> Triple(id, n, cat)
            }
        } + Triple("603", "DE: DAZN EVENT 3: Bayern – Dortmund", "6")
    }

    private fun apiRequests(): List<String> = (0 until xtream.server.requestCount).mapNotNull {
        xtream.server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS)?.requestUrl?.let { u ->
            u.queryParameter("action")?.let { a ->
                a +
                    (u.queryParameter("category_id")?.let { c -> ":$c" } ?: "")
            }
        }
    }

    @Test
    fun onlyEventAndSportGroupsAreAskedAndRenamesArrive() = runBlocking {
        apiRequests() // Import-Anfragen verwerfen
        providerRenamesBeforeKickoff()
        val refresh = container.sport.eventGroupRefresh

        assertEquals(2, refresh.refresh(setOf(pid)))
        // Event-Gruppe zuerst, dann die Sport-Gruppe; keine andere Gruppe, kein voller Abruf.
        assertEquals(listOf("get_live_streams:6", "get_live_streams:5"), apiRequests())
        assertEquals("DE: DAZN EVENT 1: Galatasaray – Fenerbahçe", name("601"))
        assertEquals("DE: DAZN EVENT 3: Bayern – Dortmund", name("603"))
        assertEquals("DE: ZDF HD", name("101"))
        // Der neue Sender gehört zur Generation des letzten Imports (die nächste volle Aktualisierung räumt ihn ggf. auf).
        val dao = container.database.channelDao()
        assertEquals(dao.entity("$pid:L:601")!!.generation, dao.entity("$pid:L:603")!!.generation)

        // Suchindex sofort aktuell.
        val hits = container.database.searchDao().matching(SearchText.ftsQuery("galatasaray")!!, "LIVE", container.searchIndexer.activeKey(pid)!!, 10)
        assertEquals(listOf("$pid:L:601"), hits)

        // Mehrere Spiele zur selben Zeit: innerhalb von 10 Minuten keine zweite Abfrage.
        assertEquals(0, refresh.refresh(setOf(pid)))
        assertTrue(apiRequests().isEmpty())
    }

    @Test
    fun panelIgnoringCategoryFilterNeedsOnlyOneRequest() = runBlocking {
        apiRequests()
        xtream.ignoresCategoryFilter = true
        providerRenamesBeforeKickoff()

        assertEquals(2, container.sport.eventGroupRefresh.refresh(setOf(pid)))
        assertEquals(listOf("get_live_streams:6"), apiRequests())
        // Auch dann bleibt alles außerhalb der Sportgruppen unverändert.
        assertEquals("DE: ZDF HD", name("101"))
    }

    @Test
    fun emptyAnswerDeletesNothing() = runBlocking {
        xtream.live = emptyList()
        assertEquals(0, container.sport.eventGroupRefresh.refresh(setOf(pid)))
        assertEquals("DE: DAZN EVENT 1", name("601"))
        assertEquals("DE: ZDF HD", name("101"))
    }

    @Test
    fun fifteenMinutesBeforeKickoffRefreshesGroupsAndRematchesTheGame() = runBlocking {
        val now = System.currentTimeMillis()
        val game = SportEvent(
            "espn:soccer/tur.1#9", "soccer", "Süper Lig", "Galatasaray – Fenerbahçe", "Galatasaray", "Fenerbahce",
            now + 30 * min, null, EventStatus.PRE, emptyList(),
        )
        val dao = container.database.sportDao()
        dao.upsertEvents(listOf(game.toEntity(now)))
        var clock = now
        val check = KickoffCheck(dao, { emptyList() }, { clock }, refreshGroups = { container.sport.eventGroupRefresh.refresh(setOf(pid)) })
        var rematchedEvents = 0
        val rematch: suspend (List<SportEvent>) -> Unit = { rematchedEvents += it.size }
        providerRenamesBeforeKickoff()

        // 30 min vorher: noch nicht.
        check.run(rematch)
        assertEquals("DE: DAZN EVENT 1", name("601"))
        assertEquals(0, rematchedEvents)

        // 15 min vorher: Gruppen neu abgefragt, Spiel auf dem Event-Sender.
        clock = now + 15 * min
        check.run(rematch)
        assertEquals("DE: DAZN EVENT 1: Galatasaray – Fenerbahçe", name("601"))
        assertEquals(1, rematchedEvents)
    }

    @Test
    fun groupSelectionAndMerge() {
        fun cat(id: String, name: String) = CategoryEntity("1:L:$id", 1, "LIVE", id, name, id.toInt(), 0)
        val groups = EventGroupRefresh.eventGroups(
            listOf(
                cat("1", "DE | VOLLPROGRAMM"),
                cat("2", "DE | SPORT"),
                cat("3", "TR | SPOR"),
                cat("4", "DE | SKYLINE DOKU"),
                cat("5", "DE | PPV EVENTS"),
                cat("6", "UK | TRANSPORT"),
                cat("7", "DE | SKY CINEMA"),
                cat("8", "TR | MAÇ YAYINLARI"),
            ),
        )
        // Events zuerst (auch „MAÇ“), „Skyline“, „Transport“ und „Sky Cinema“ sind kein Sport.
        assertEquals(listOf("5", "8", "2", "3"), groups.map { it.remoteId })

        val old = ChannelEntity(
            "1:L:9", 1, "1:L:5", "9", "DE: DAZN EVENT 1", null, "xt://1/live/9.ts", "", "dazn event 1", "dazn.de",
            sortIndex = 7, generation = 5,
        )
        val same = old.copy()
        assertNull(EventGroupRefresh.merge(old, same))
        val renamed = EventGroupRefresh.merge(
            old,
            old.copy(name = "DE: DAZN EVENT 1: A – B", normalizedName = "dazn event 1 a b", epgKey = "name:dazn event 1 a b"),
        )!!
        // Zugeordnete EPG-Kennung bleibt, Sortierung und Generation auch.
        assertEquals("dazn.de", renamed.epgKey)
        assertEquals(7, renamed.sortIndex)
        assertEquals(5, renamed.generation)
    }
}
