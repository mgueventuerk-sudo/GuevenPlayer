package com.mg.player.data.combined

import com.mg.player.core.util.CountryDetector
import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.importer.ChannelGrouping
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * Massentest statt Einzelbeispielen: Jeder Sender des Katalogs (~30.000) wird so nachgebaut, wie ihn drei Anbieter
 * liefern – Länderpräfix, Großschreibung, HD/FHD, Zweitnamen, fehlende und **falsche** EPG-IDs (jeder sechste Sender
 * beim dritten Anbieter mit der EPG-ID eines anderen Senders desselben Landes). Gezählt wird, was der Nutzer sieht:
 *
 * - **Falsch zusammengelegt:** zwei echte Sender in einem Eintrag, obwohl ihre Namen unterscheidbar sind.
 * - **Nicht zusammengelegt:** ein echter Sender auf mehrere Einträge verteilt.
 *
 * Läuft einmal mit Katalog und einmal ohne (= Sender, die der Katalog nicht kennt).
 */
class CatalogBenchmarkTest {

    private class Source(val id: String, val country: String, val names: List<String>)

    private fun sources(): List<Source> {
        val out = ArrayList<Source>()
        GZIPInputStream(FileInputStream("src/main/assets/channel-catalog.dat")).bufferedReader().forEachLine { line ->
            if (line.startsWith("#")) return@forEachLine
            val (id, iso, names) = line.split('\t', limit = 3)
            val country = CountryDetector.fromIso(iso) ?: return@forEachLine
            if (CountryDetector.detect("$country | TV") != country) return@forEachLine
            out += Source(id, country, names.split(';'))
        }
        return out
    }

    private fun rows(sources: List<Source>, random: Random): List<ChannelSourceRow> {
        val byCountry = sources.groupBy { it.country }
        val rows = ArrayList<ChannelSourceRow>(sources.size * 3)
        var n = 0
        fun add(playlist: Long, s: Source, name: String, epg: String) {
            val grouping = ChannelGrouping.of(name, CountryDetector.detect("${s.country} | TV"))
            rows += ChannelSourceRow("${s.id}#$playlist#${n++}", playlist, "$playlist:c", name, null, epg, epg.lowercase(), grouping.key, grouping.rank, n, 0)
        }
        for (s in sources) {
            add(1, s, "${s.country}: ${s.names.first()} HD", s.id)
            add(2, s, "|${s.country}| ${s.names.last()}", "")
            val wrong = random.nextInt(6) == 0
            val epg = if (wrong) byCountry.getValue(s.country).random(random).id else s.id
            add(3, s, "${s.names.first().uppercase()} FHD", epg)
        }
        return rows
    }

    private class Result(val wrongMerges: Int, val split: Int, val channels: Int, val refused: Int)

    private fun measure(catalog: ChannelCatalog): Result {
        val sources = sources()
        val rows = rows(sources, Random(42))
        val stats = MatchStats()
        val clusters = ChannelClusterer.cluster(rows, MatchRules(), EpgPrints(), catalog, stats)
        val nameKeys = sources.associate { s -> s.id to s.names.mapNotNull { ChannelCatalog.keyFor(it, s.country) }.toSet() }
        val truth = { r: ChannelSourceRow -> r.id.substringBefore('#') }
        var wrong = 0
        for (c in clusters) {
            val ids = c.members.map(truth).distinct()
            if (ids.size < 2) continue
            // Unterscheidbar = kein gemeinsamer Namensschlüssel (sonst kann auch ein Mensch sie nicht trennen).
            val distinguishable = ids.indices.any { i ->
                (i + 1 until ids.size).any { j -> nameKeys[ids[i]].orEmpty().intersect(nameKeys[ids[j]].orEmpty()).isEmpty() }
            }
            if (distinguishable) {
                wrong++
                if (wrong <= 40 && System.getenv("BENCH_DUMP") != null) {
                    println("WRONG " + c.members.joinToString(" | ") { "${it.name} [${it.epgChannelId}] =${truth(it)}" })
                }
            }
        }
        val clustersPerId = HashMap<String, MutableSet<String>>()
        clusters.forEach { c -> c.members.forEach { clustersPerId.getOrPut(truth(it)) { HashSet() } += c.key } }
        val split = clustersPerId.values.count { it.size > 1 }
        if (System.getProperty("benchDump").orEmpty().isNotBlank()) {
            val byKey = clusters.associateBy { it.key }
            clustersPerId.filterValues { it.size > 1 }.entries.take(40).forEach { (id, keys) ->
                println("SPLIT $id: " + keys.joinToString(" // ") { k -> byKey.getValue(k).members.joinToString(" | ") { "${it.name} [${it.epgChannelId}]" } })
            }
        }
        return Result(wrong, split, sources.size, stats.refused)
    }

    private fun report(label: String, r: Result) {
        val wrongPct = 100.0 * r.wrongMerges / r.channels
        val splitPct = 100.0 * r.split / r.channels
        val line = "BENCH $label: ${r.channels} Sender · falsch zusammengelegt ${r.wrongMerges} (%.2f %%) · " +
            "nicht zusammengelegt ${r.split} (%.2f %%) · verhindert ${r.refused}"
        println(line.format(wrongPct, splitPct))
    }

    @Test
    fun withCatalog() {
        val catalog = GZIPInputStream(FileInputStream("src/main/assets/channel-catalog.dat")).use(ChannelCatalog::parse)
        val r = measure(catalog)
        report("mit Katalog", r)
        assertTrue("falsch zusammengelegt: ${r.wrongMerges}", r.wrongMerges * 1000 <= r.channels)
        assertTrue("nicht zusammengelegt: ${r.split}", r.split * 100 <= r.channels * 6)
    }

    @Test
    fun withoutCatalog() {
        val r = measure(ChannelCatalog.EMPTY)
        report("ohne Katalog", r)
        assertTrue("falsch zusammengelegt: ${r.wrongMerges}", r.wrongMerges * 1000 <= r.channels)
    }
}
