package com.mg.player.data.importer

import com.mg.player.data.importer.ImportGuard.Verdict
import com.mg.player.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportGuardTest {

    @Test
    fun `erster Import wird immer übernommen`() {
        assertEquals(Verdict.ACCEPT, ImportGuard().judge(1, ContentType.LIVE, previous = 0, fresh = 0))
    }

    @Test
    fun `leere Antwort behält die alte Liste`() {
        assertEquals(Verdict.KEEP_OLD, ImportGuard().judge(1, ContentType.LIVE, previous = 5_000, fresh = 0))
    }

    @Test
    fun `starker Einbruch behält die alte Liste`() {
        assertEquals(Verdict.KEEP_OLD, ImportGuard().judge(1, ContentType.MOVIE, previous = 10_000, fresh = 900))
    }

    @Test
    fun `normale Schwankung wird übernommen`() {
        assertEquals(Verdict.ACCEPT, ImportGuard().judge(1, ContentType.MOVIE, previous = 10_000, fresh = 9_400))
    }

    @Test
    fun `kleine Listen dürfen schrumpfen`() {
        assertEquals(Verdict.ACCEPT, ImportGuard().judge(1, ContentType.SERIES, previous = 150, fresh = 10))
    }

    @Test
    fun `zweimal dasselbe verdächtige Ergebnis wird übernommen`() {
        val guard = ImportGuard()
        assertEquals(Verdict.KEEP_OLD, guard.judge(1, ContentType.LIVE, previous = 5_000, fresh = 300))
        assertEquals(Verdict.ACCEPT, guard.judge(1, ContentType.LIVE, previous = 5_000, fresh = 300))
    }

    @Test
    fun `anderes verdächtiges Ergebnis zählt neu`() {
        val guard = ImportGuard()
        assertEquals(Verdict.KEEP_OLD, guard.judge(1, ContentType.LIVE, previous = 5_000, fresh = 0))
        assertEquals(Verdict.KEEP_OLD, guard.judge(1, ContentType.LIVE, previous = 5_000, fresh = 120))
    }

    @Test
    fun `Bereiche und Zugänge werden getrennt bewertet`() {
        val guard = ImportGuard()
        assertEquals(Verdict.KEEP_OLD, guard.judge(1, ContentType.LIVE, previous = 5_000, fresh = 0))
        assertEquals(Verdict.KEEP_OLD, guard.judge(2, ContentType.LIVE, previous = 5_000, fresh = 0))
        assertEquals(Verdict.KEEP_OLD, guard.judge(1, ContentType.MOVIE, previous = 5_000, fresh = 0))
    }

    /** Fall aus der Prüfung: Wer nur einmal pro App-Start aktualisiert, bekam einen echten Rückgang nie übernommen. */
    @Test
    fun suspicionSurvivesRestart() {
        val store = ImportGuard.MemorySuspicionStore()
        assertEquals(Verdict.KEEP_OLD, ImportGuard(store).judge(1, ContentType.LIVE, previous = 5_000, fresh = 300))
        // Neue Instanz = App neu gestartet, gleicher dauerhafter Speicher.
        assertEquals(Verdict.ACCEPT, ImportGuard(store).judge(1, ContentType.LIVE, previous = 5_000, fresh = 300))
    }
}
