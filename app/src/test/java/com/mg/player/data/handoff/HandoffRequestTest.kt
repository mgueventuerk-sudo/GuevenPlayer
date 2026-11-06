package com.mg.player.data.handoff

import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlayerEngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** „Auf dem Fernseher weiterschauen“: Was hingeht, muss drüben dasselbe sein – und Unbekanntes darf nichts starten. */
class HandoffRequestTest {

    @Test
    fun everySupportedKindSurvivesTheTrip() {
        val cases = listOf(
            PlayRequest.Live("12:L:345", Category.ALL),
            PlayRequest.Movie("m-99", 1_234_000L),
            PlayRequest.Episode("e-7", "s-3", 42_000L),
            PlayRequest.Recording("r-1"),
        )
        cases.forEach { original ->
            val back = HandoffRequest.decode(HandoffRequest.encode(original)!!)
            assertEquals(original, back)
        }
    }

    @Test
    fun theReceivingDeviceChoosesItsOwnEngine() {
        // Die Engine des Handys sagt nichts ueber den Fernseher – sie wird nie uebernommen.
        val withEngine = PlayRequest.Movie("m-1", 5_000L, PlayerEngineType.MEDIA3)
        val back = HandoffRequest.decode(HandoffRequest.encode(withEngine)!!)
        assertEquals(PlayRequest.Movie("m-1", 5_000L), back)
        assertNull((back as PlayRequest.Movie).engine)
    }

    @Test
    fun catchupIsNotHandedOver() {
        // Das Archivfenster haengt am Zugang des Geraets – eine Uebergabe koennte drueben ins Leere greifen.
        val catchup = PlayRequest.Catchup("12:L:345", Category.ALL, 1_000L, 2_000L, "Tagesschau")
        assertNull(HandoffRequest.encode(catchup))
        assertFalse(HandoffRequest.isSupported(catchup))
        assertTrue(HandoffRequest.isSupported(PlayRequest.Movie("m-1", 0L)))
    }

    @Test
    fun brokenOrUnknownInputStartsNothing() {
        assertNull(HandoffRequest.decode(emptyMap()))
        assertNull(HandoffRequest.decode(mapOf("k" to "movie")))
        assertNull(HandoffRequest.decode(mapOf("k" to "movie", "i" to "   ")))
        assertNull(HandoffRequest.decode(mapOf("k" to "zukunft", "i" to "x")))
        // Folge ohne Serien-Kennung ist unvollstaendig.
        assertNull(HandoffRequest.decode(mapOf("k" to "episode", "i" to "e-1")))
        // Uebermaessig lange Kennungen sind kein Inhalt dieses Geraets.
        assertNull(HandoffRequest.decode(mapOf("k" to "movie", "i" to "x".repeat(200))))
    }

    @Test
    fun aBrokenPositionNeverRewindsPastTheStart() {
        assertEquals(PlayRequest.Movie("m-1", 0L), HandoffRequest.decode(mapOf("k" to "movie", "i" to "m-1", "p" to "-9")))
        assertEquals(PlayRequest.Movie("m-1", 0L), HandoffRequest.decode(mapOf("k" to "movie", "i" to "m-1", "p" to "keine")))
        assertEquals(PlayRequest.Movie("m-1", 0L), HandoffRequest.decode(mapOf("k" to "movie", "i" to "m-1")))
    }

    @Test
    fun theSignedPayloadCoversTheContentAndIsStable() {
        // Der Inhalt gehoert in die Signatur: Sonst liesse sich ein mitgeschnittener Aufruf auf einen anderen
        // Film umbiegen. Die Reihenfolge der Felder darf das Ergebnis nicht aendern.
        val a = HandoffServer.payload(mapOf("k" to "movie", "i" to "m-1", "p" to "5000"))
        val b = HandoffServer.payload(mapOf("p" to "5000", "i" to "m-1", "k" to "movie"))
        assertEquals(a, b)
        assertTrue(a.contains("m-1"))
        assertTrue(a.contains("5000"))
        // Ein anderer Film ergibt einen anderen Inhalt – und damit eine andere Signatur.
        assertFalse(a == HandoffServer.payload(mapOf("k" to "movie", "i" to "m-2", "p" to "5000")))
        // Unlesbares ergibt leeren Inhalt, nie eine zufaellige Zeichenkette.
        assertEquals("", HandoffServer.payload(mapOf("k" to "zukunft")))
    }
}
