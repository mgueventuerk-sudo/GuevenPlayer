package com.mg.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NameNormalizerTest {

    @Test
    fun `entfernt Länderkürzel und Qualitätsangaben`() {
        assertEquals("daserste", NameNormalizer.normalize("DE: Das Erste HD"))
        assertEquals("bbcone", NameNormalizer.normalize("|UK| BBC One FHD"))
        assertEquals("prosieben", NameNormalizer.normalize("ProSieben (RAW)"))
    }

    @Test
    fun `schreibt Umlaute aus`() {
        assertEquals("phoenix", NameNormalizer.normalize("Phönix"))
    }

    @Test
    fun `entfernt Domain-Endung bei EPG-IDs`() {
        assertEquals("daserste", NameNormalizer.normalizeEpgId("DasErste.de"))
    }
}
