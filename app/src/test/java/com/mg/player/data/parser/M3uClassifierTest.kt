package com.mg.player.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uClassifierTest {

    private fun entry(name: String, url: String) = M3uParser.Entry(name, url, null, "Gruppe", "", null)

    @Test
    fun `erkennt Typ anhand der URL`() {
        assertEquals(M3uClassifier.Kind.SERIES, M3uClassifier.classify(entry("X", "http://s/series/u/p/1.mkv"), "mkv"))
        assertEquals(M3uClassifier.Kind.MOVIE, M3uClassifier.classify(entry("X", "http://s/movie/u/p/1.mp4"), "mp4"))
        assertEquals(M3uClassifier.Kind.LIVE, M3uClassifier.classify(entry("X", "http://s/live/u/p/1.ts"), "ts"))
    }

    @Test
    fun `Videodatei mit Episodenmuster ist eine Serie`() {
        assertEquals(M3uClassifier.Kind.SERIES, M3uClassifier.classify(entry("Dark S02E05", "http://s/a.mkv"), "mkv"))
        assertEquals(M3uClassifier.Kind.MOVIE, M3uClassifier.classify(entry("Inception", "http://s/b.mkv"), "mkv"))
    }

    @Test
    fun `liest Staffel und Folge`() {
        assertEquals(M3uClassifier.SeriesInfo("Dark", 2, 5), M3uClassifier.seriesInfo("Dark S02E05 Titel"))
        assertEquals(M3uClassifier.SeriesInfo("Show", 1, 3), M3uClassifier.seriesInfo("Show 1x03"))
        assertNull(M3uClassifier.seriesInfo("Inception"))
    }

    @Test
    fun `liest Jahr in Klammern`() {
        assertEquals("2019", M3uClassifier.year("Film (2019)"))
        assertNull(M3uClassifier.year("Film ohne Jahr"))
    }
}
