package com.mg.player.ui.player

import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.PlayRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rangfolge der Quellen (Entscheidungen 03.11.2026) und Wechsel-Logik für Filme/Folgen. */
class SourceRankerTest {

    private fun c(id: String, pid: Long, quality: String?, lang: String? = null) =
        SourceRanker.Candidate(id, id, pid, quality, CombinedPlayables.qualityRank(quality), lang)

    /** Ab 3.50: Quelle mit Hängern hinter einer schlechteren Qualität ohne Probleme. */
    /** Stargate in „Alle Zugänge“: Die nächste Folge bleibt beim Zugang der vorigen, auch wenn ein anderer FHD hat. */
    @Test
    fun nextEpisodeStaysOnTheAccountOfThePreviousOne() {
        val ranked = SourceRanker.rank(
            listOf(c("en-fhd", 2, "FHD"), c("de-hd", 1, "HD")),
            rememberedId = null,
            supports4K = true,
            statuses = emptyMap(),
            preferredPlaylist = 1,
        )
        assertEquals(listOf("de-hd", "en-fhd"), ranked)
    }

    /** Die eigene, gerade beendete Folge belegt den Zugang beim Anbieter noch kurz – das ist kein fremder Zuschauer. */
    @Test
    fun ownJustEndedPlaybackDoesNotMakeTheAccountFull() {
        val statuses = SourceRanker.withoutOwnUse(mapOf(1L to ConnectionStatus(1, 1), 2L to ConnectionStatus(1, 1))) { it == 1L }
        assertEquals(ConnectionStatus(0, 1), statuses[1L])
        assertEquals(ConnectionStatus(1, 1), statuses[2L])
        val ranked = SourceRanker.rank(
            listOf(c("en", 2, "FHD", "EN"), c("de", 1, "FHD", "DE")),
            rememberedId = null,
            supports4K = true,
            statuses = statuses,
            preferredLanguage = "DE",
        )
        assertEquals(listOf("de", "en"), ranked)
    }

    @Test
    fun healthBeatsQuality() {
        val ranked = SourceRanker.rank(
            listOf(c("fhd", 1, "FHD"), c("hd", 2, "HD"), c("sd", 3, "SD")),
            rememberedId = "fhd",
            supports4K = true,
            statuses = emptyMap(),
            health = { if (it == "fhd") 1 else 0 },
        )
        assertEquals(listOf("hd", "sd", "fhd"), ranked)
    }

    @Test
    fun deviceThenFreeThenQualityThenFreestThenOrder() {
        val ranked = SourceRanker.rank(
            listOf(c("a-4k", 1, "4K"), c("b-fhd", 2, "FHD"), c("c-fhd", 3, "FHD"), c("d-hd", 4, "HD")),
            rememberedId = null,
            supports4K = false,
            statuses = mapOf(2L to ConnectionStatus(1, 2), 3L to ConnectionStatus(0, 2), 4L to ConnectionStatus(0, 1)),
        )
        // 4K kann das Gerät nicht → hinten. FHD vor HD. Bei zweimal FHD: Zugang 3 hat mehr frei (2) als Zugang 2 (1).
        assertEquals(listOf("c-fhd", "b-fhd", "d-hd", "a-4k"), ranked)
    }

    @Test
    fun fullAccountGoesBackAndLanguageCounts() {
        val ranked = SourceRanker.rank(
            listOf(c("full", 1, "FHD", "DE"), c("en", 2, "FHD", "EN"), c("de", 3, "HD", "DE")),
            rememberedId = null,
            supports4K = true,
            statuses = mapOf(1L to ConnectionStatus(1, 1)),
            preferredLanguage = "DE",
        )
        assertEquals(listOf("de", "en", "full"), ranked)
    }

    @Test
    fun tieBreakUsesAccountOrderAfterFreest() {
        val ranked = SourceRanker.rank(
            listOf(c("x", 5, "HD"), c("y", 6, "HD")),
            rememberedId = null,
            supports4K = true,
            statuses = emptyMap(),
            accountRank = { if (it == 6L) 0 else 1 },
        )
        assertEquals(listOf("y", "x"), ranked)
    }

    private fun src(pid: Long, d: Int? = null) = VodSource("$pid:M:1", pid, "u", "Z$pid", "FHD", 30, "DE", d)

    @Test
    fun vodSwitchingAcrossAccountsOnlyWhenAllowed() {
        val base = Playable(PlayableKind.MOVIE, "-2:M:x", "T", null, "u", 0, null, vodSources = listOf(src(1), src(2)))
        assertEquals(1, base.nextSourceIndex())
        assertNull(base.copy(autoAcrossAccounts = false).nextSourceIndex())
        assertNull(base.copy(variantIndex = 1).nextSourceIndex())
        assertEquals("Quelle 1/2 · Z1 · FHD · DE", base.sourceLabel)
    }

    @Test
    fun positionCarriesOver() {
        assertEquals(600_000L, CombinedPlayables.mapPosition(600_000, 6_000, 6_100))
        // 10 % längere Fassung: anteilig und 10 s früher.
        assertEquals(650_000L, CombinedPlayables.mapPosition(600_000, 6_000, 6_600))
        assertEquals(600_000L, CombinedPlayables.mapPosition(600_000, null, 6_600))
        PlayRequest.Movie("x", 0) // nur Kompilierprüfung der Anfrage-Art
    }
}
