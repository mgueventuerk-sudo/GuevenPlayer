package com.mg.player.data.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.data.remote.ConnectionMonitor
import com.mg.player.domain.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verbindungen aus user_info – Panels liefern Zahlen mal als Text, mal als Zahl. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class XtreamAccountParserTest {

    private fun parse(json: String) = XtreamAccountParser.parse(json.byteInputStream())

    @Test
    fun readsConnectionsAsStringsOrNumbers() {
        assertEquals(
            ConnectionStatus(1, 1, 1_767_225_600_000),
            parse("""{"user_info":{"auth":1,"active_cons":"1","max_connections":"1","exp_date":"1767225600"},"server_info":{}}"""),
        )
        assertEquals(ConnectionStatus(0, 3), parse("""{"user_info":{"auth":"1","active_cons":0,"max_connections":3,"exp_date":null}}"""))
        assertEquals(ConnectionStatus(2, 4), parse("""{"server_info":{"x":1},"user_info":{"auth":1,"active_cons":"2","max_connections":"4"}}"""))
    }

    @Test
    fun noUsableAnswer() {
        assertNull(parse("""{"user_info":{"auth":0}}"""))
        assertNull("ohne max_connections", parse("""{"user_info":{"auth":1,"status":"Active"}}"""))
        assertNull(parse("[]"))
    }

    @Test
    fun labels() {
        val full = ConnectionStatus(1, 1)
        assertEquals("1/1", full.label)
        assertTrue(full.inUse && full.full)
        assertEquals("In Nutzung – 1 von 1 Verbindungen belegt (voll)", full.description)
        val free = ConnectionStatus(0, 2)
        assertFalse(free.inUse || free.full)
        assertEquals("Frei – 0 von 2 Verbindungen belegt", free.description)
        assertEquals("1/∞", ConnectionStatus(1, 0).label)
        assertFalse(ConnectionStatus(5, 0).full)
    }

    @Test
    fun m3uLinkInXtreamFormat() {
        val c = ConnectionMonitor.credentialsFromM3u("http://tv.example:8080/get.php?username=max&password=geheim&type=m3u_plus&output=ts")
        assertEquals("http://tv.example:8080", c?.baseUrl)
        assertEquals("max", c?.username)
        assertEquals("geheim", c?.password)
        assertNull(ConnectionMonitor.credentialsFromM3u("https://liste.example/tv.m3u"))
    }
}
