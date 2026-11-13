package com.mg.player.ui.player

import com.mg.player.data.handoff.HandoffRequest
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.PlayRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * „Auf dem Fernseher weiterschauen“ übergibt den **laufenden** Inhalt – nicht den Wunsch, mit dem der Player geöffnet
 * wurde. Vorher ging nach dem Zappen der Start-Sender an den Fernseher, nach „Nächste Folge“ die vorige Folge.
 */
class PlayableHandoffTest {

    private fun playable(kind: PlayableKind, id: String, channel: Channel? = null, seriesId: String? = null) = Playable(
        kind = kind,
        id = id,
        title = "Titel",
        subtitle = null,
        url = "http://127.0.0.1:0/stream",
        startPositionMs = 0L,
        progressKey = null,
        seriesId = seriesId,
        channel = channel,
    )

    private val channel = Channel(
        id = "7:L:42",
        name = "Zweiter Sender",
        logoUrl = null,
        streamUrl = "http://127.0.0.1:0/live",
        categoryId = "1",
        epgKey = "k",
        nowTitle = null,
        nowStartMs = null,
        nowEndMs = null,
        playlistId = 7,
    )

    @Test
    fun liveHandsOverTheChannelThatIsPlayingNow() {
        val zappedTo = playable(PlayableKind.LIVE, channel.id, channel = channel)
        assertEquals(PlayRequest.Live(channel.id, Category.ALL), zappedTo.toPlayRequest(positionMs = 123_000L))
    }

    @Test
    fun moviesAndEpisodesCarryTheCurrentPosition() {
        assertEquals(PlayRequest.Movie("7:M:9", 42_000L), playable(PlayableKind.MOVIE, "7:M:9").toPlayRequest(42_000L))
        assertEquals(PlayRequest.Episode("7:E:3", "7:S:1", 9_000L), playable(PlayableKind.EPISODE, "7:E:3", seriesId = "7:S:1").toPlayRequest(9_000L))
    }

    @Test
    fun catchupIsNotHandedOverAndEverythingElseSurvivesTheWire() {
        assertNull(playable(PlayableKind.CATCHUP, "${channel.id}@1", channel = channel).toPlayRequest(0L))
        listOf(
            playable(PlayableKind.LIVE, channel.id, channel = channel),
            playable(PlayableKind.MOVIE, "7:M:9"),
            playable(PlayableKind.EPISODE, "7:E:3", seriesId = "7:S:1"),
            playable(PlayableKind.RECORDING, "r-1"),
        ).forEach { p -> assertTrue("${p.kind} muss übergebbar sein", HandoffRequest.isSupported(p.toPlayRequest(0L)!!)) }
    }
}
