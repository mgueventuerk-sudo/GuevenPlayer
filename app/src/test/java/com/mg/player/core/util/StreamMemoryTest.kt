package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fall aus der Prüfung: Gleicher Sendername in zwei Zugängen überschrieb sich, gelöschte Zugänge blieben gemerkt. */
class StreamMemoryTest {

    @Test
    fun accountsDoNotOverwriteEachOther() {
        val memory = StreamMemory()
        memory.remember(1, "zdf|de", "1:a")
        memory.remember(2, "zdf|de", "2:b")
        assertEquals("1:a", memory[1, "zdf|de"])
        assertEquals("2:b", memory[2, "zdf|de"])
    }

    @Test
    fun forgetRemovesOnlyThatAccount() {
        val memory = StreamMemory()
        memory.remember(1, "zdf|de", "1:a")
        memory.remember(12, "zdf|de", "12:a")
        memory.forget(1)
        assertNull(memory[1, "zdf|de"])
        assertEquals("12:a", memory[12, "zdf|de"])
    }

    @Test
    fun blankGroupIsNeverStored() {
        val memory = StreamMemory()
        memory.remember(1, "", "x")
        assertNull(memory[1, ""])
    }
}
