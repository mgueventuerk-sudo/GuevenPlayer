package com.mg.player.data.combined

import com.mg.player.core.util.CountryDetector
import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.importer.ChannelGrouping
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Nur von Hand: echte Senderliste(n) durch die Zuordnung schicken und verdächtige Einträge ausgeben.
 * Datei (`-PrealNames=/pfad`): Zeilen `zugang<TAB>gruppe<TAB>name<TAB>epg-id` – ohne Adressen und Zugangsdaten.
 * Verdächtig = ein Eintrag, dessen Quellen verschiedene Namensschlüssel haben (ohne Land).
 */
class RealListAnalysisTest {

    @Test
    fun analyse() {
        val path = System.getProperty("realNames")?.takeIf(String::isNotBlank)
        assumeTrue(path != null && File(path).isFile)
        val catalog = GZIPInputStream(FileInputStream("src/main/assets/channel-catalog.dat")).use(ChannelCatalog::parse)
        var n = 0
        val rows = File(path!!).readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) return@mapNotNull null
            val playlist = p[0].toLong()
            val g = ChannelGrouping.of(p[2], CountryDetector.detect(p[1]))
            ChannelSourceRow("$playlist:L:${n++}", playlist, "$playlist:${p[1]}", p[2], null, p[3], p[3].lowercase(), g.key, g.rank, n, 0)
        }
        val stats = MatchStats()
        val clusters = ChannelClusterer.cluster(rows, MatchRules(), EpgPrints(), catalog, stats)
        val out = StringBuilder()
        out.appendLine("REAL ${rows.size} Quellen → ${clusters.size} Einträge · Katalog erkannt ${stats.identified} · verhindert ${stats.refused}")
        val mixed = clusters.filter { c -> c.members.mapNotNull { MatchKeys.channelName(it.groupKey)?.let(MatchKeys::withoutCountry) }.distinct().size > 1 }
        out.appendLine("REAL gemischte Einträge: ${mixed.size}")
        mixed.sortedByDescending { it.members.size }.forEach { c ->
            out.appendLine("MIX " + c.members.joinToString(" | ") { "${it.name} [${it.epgChannelId}]" })
        }
        // Doppelte Einträge: verschiedene Gruppen mit demselben Namensschlüssel (beim Zappen „gleicher Sender, andere Quelle“).
        val byName = HashMap<String, MutableList<Cluster<ChannelSourceRow>>>()
        clusters.forEach { c ->
            c.members.mapNotNull { MatchKeys.channelName(it.groupKey) }.distinct().forEach { k -> byName.getOrPut(k) { ArrayList() } += c }
        }
        val dups = byName.filterValues { it.size > 1 }
        out.appendLine("REAL doppelte Namen: ${dups.size}")
        dups.entries.sortedByDescending { it.value.size }.forEach { (k, cs) ->
            out.appendLine("DUP $k: " + cs.joinToString(" // ") { c -> c.members.joinToString(" | ") { "${it.name} [${it.epgChannelId}]" } })
        }
        // Listeneinträge je Zugang: vorher je exakt gleichem Gruppenschlüssel, nachher je Gruppe der Zuordnung.
        rows.groupBy { it.playlistId }.forEach { (pid, own) ->
            val before = own.map { it.groupKey }.distinct().size
            val after = ChannelClusterer.cluster(own, MatchRules(), EpgPrints(), catalog).size
            out.appendLine("REAL Zugang $pid: Listeneinträge $before → $after")
        }
        File(System.getProperty("realOut") ?: "build/real-analysis.txt").writeText(out.toString())
    }
}
