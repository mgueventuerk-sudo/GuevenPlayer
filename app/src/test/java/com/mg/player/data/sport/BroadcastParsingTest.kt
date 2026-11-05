package com.mg.player.data.sport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Senderliste von Bzzoiro tolerant lesen – ein abweichender Feldname ließ vorher alle Spiele ohne Sender (ab 3.48). */
class BroadcastParsingTest {

    private fun parse(json: String) = BzzoiroSource.parseBroadcast(JSONObject(json))

    @Test
    fun flatFields() {
        val b = parse("""{"event_id": 7, "channel_name": "beIN SPORTS 1"}""")!!
        assertEquals(7L, b.eventId)
        assertEquals("beIN SPORTS 1", b.channel)
    }

    @Test
    fun nestedChannelAndEvent() {
        val b = parse("""{"event": {"id": 9}, "channel": {"id": 3, "name": "beIN SPORTS 2"}}""")!!
        assertEquals(9L, b.eventId)
        assertEquals("beIN SPORTS 2", b.channel)
    }

    @Test
    fun plainChannelString() {
        assertEquals("beIN SPORTS 3", parse("""{"event": 11, "channel": "beIN SPORTS 3"}""")!!.channel)
    }

    @Test
    fun withoutChannelOrEventNothing() {
        assertNull(parse("""{"event_id": 7}"""))
        assertNull(parse("""{"channel_name": "beIN SPORTS 1"}"""))
    }
}
