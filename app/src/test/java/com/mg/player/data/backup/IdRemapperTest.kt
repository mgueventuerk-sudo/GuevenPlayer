package com.mg.player.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class IdRemapperTest {

    private val map = mapOf(5L to 9L)

    @Test
    fun `schreibt Playlist-ID in Einträgen um`() {
        assertEquals("9:M:123", IdRemapper.remapId("5:M:123", map))
        assertEquals("9:S:7", IdRemapper.remapId("5:S:7", map))
    }

    @Test
    fun `schreibt Fortschritts-Schlüssel um`() {
        assertEquals("movie:9:M:123", IdRemapper.remapKey("movie:5:M:123", map))
        assertEquals("live:9:L:1", IdRemapper.remapKey("live:5:L:1", map))
    }

    @Test
    fun `unbekannte IDs bleiben unverändert`() {
        assertEquals("7:M:1", IdRemapper.remapId("7:M:1", map))
        assertEquals("abc", IdRemapper.remapId("abc", map))
    }
}
