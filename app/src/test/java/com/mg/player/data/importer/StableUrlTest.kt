package com.mg.player.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableUrlTest {

    @Test
    fun urlsWithoutTokensStayIdentical() {
        listOf(
            "http://host:8080/user/pass/12345.ts",
            "http://host/live/user/pass/1.m3u8",
            "http://host/play.php?id=55&type=live",
            "http://host/stream?channel=zdf",
        ).forEach { assertEquals(it, StableUrl.key(it)) }
    }

    @Test
    fun rotatingTokensAreIgnored() {
        val a = StableUrl.key("http://host/play.php?id=55&token=abc123&expires=1700000000")
        val b = StableUrl.key("http://host/play.php?id=55&token=zzz999&expires=1800000000")
        assertEquals("http://host/play.php?id=55", a)
        assertEquals(a, b)
        assertEquals("http://cdn/x/index.m3u8", StableUrl.key("http://cdn/x/index.m3u8?wmsAuthSign=QWERTY"))
        assertEquals("http://cdn/x/index.m3u8", StableUrl.key("http://cdn/x/index.m3u8?Token=1&ST=2"))
    }

    @Test
    fun differentStreamsStayDifferent() {
        assertNotEquals(
            StableUrl.key("http://host/play.php?id=55&token=a"),
            StableUrl.key("http://host/play.php?id=56&token=a"),
        )
    }

    @Test
    fun fragmentDoesNotBreakParsing() {
        assertEquals("http://host/a.ts", StableUrl.key("http://host/a.ts?token=1#frag"))
    }
}
