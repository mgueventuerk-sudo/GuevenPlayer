package com.mg.player.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Jede Schema-Version braucht eine Migration – sonst würde Room beim Update die Datenbank löschen
 * (Zugänge, Favoriten, Fortschritt weg). Die Kette muss lückenlos bis zur aktuellen Version reichen.
 */
class MigrationChainTest {

    @Test
    fun migrationsFormContiguousChainToCurrentVersion() {
        val steps = Migrations.ALL.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        var version = steps.first().first
        steps.forEach { (from, to) ->
            assertEquals("Lücke vor Version $from", version, from)
            assertEquals("Migration $from -> $to überspringt Versionen", from + 1, to)
            version = to
        }
        assertEquals(AppDatabase.VERSION, version)
    }
}
