package com.mg.player.data.combined

import android.content.Context
import com.mg.player.core.log.AppLog
import com.mg.player.core.util.CountryDetector
import com.mg.player.core.util.MediaTitle
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Senderkatalog für die automatische Zuordnung: rund 30.000 Sender weltweit mit Land, Namen und Zweitnamen
 * (iptv-org/database, gemeinfrei – `tools/channel-catalog/build_catalog.py` baut die Datei neu).
 *
 * Er beantwortet „welcher echte Sender ist das?“ unabhängig von der Schreibweise des Anbieters: „Das Erste HD“, „ARD“
 * und die EPG-ID „DasErste.de“ führen alle zu `DasErste.de`; „ZDFneo“ führt nie zu `ZDF.de`. Ist ein Name im selben
 * Land mehrdeutig, gibt es keine Antwort – dann gilt der bisherige Abgleich.
 */
class ChannelCatalog internal constructor(
    /** „DE:ard“ → „DasErste.de“; mehrdeutige Namen stehen nicht drin. */
    private val byName: Map<String, String>,
    /** „dasErste.de“ (klein) → „DasErste.de“. */
    private val byId: Map<String, String>,
    /** „DasErste.de“ → Namensschlüssel ohne Land („ard“, …) – prüft, ob eine EPG-ID zum Namen passt. */
    private val names: Map<String, List<String>> = emptyMap(),
) {
    val size: Int get() = byId.size

    /** Katalog-ID über den Namen (Land + bereinigter Name wie beim Import, [MatchKeys.channelName]). */
    fun byNameKey(nameKey: String?): String? = nameKey?.let { byName[it] }

    /**
     * Katalog-ID über die EPG-ID des Anbieters („ntv.de“, „ntv.de@HD“). Mit [bareName] nur, wenn der Name des Anbieters
     * zum Katalog-Sender passt – sonst ist die EPG-ID vermutlich falsch kopiert und sagt nichts.
     */
    fun byEpgId(epgChannelId: String, bareName: String? = null, rawName: String? = null): String? {
        val raw = epgChannelId.trim().substringBefore('@').lowercase(Locale.ROOT)
        val id = (if (raw.isEmpty()) null else byId[raw]) ?: return null
        if (bareName == null) return id
        val known = names[id] ?: return id
        val fits = known.any { NameRules.compatible(it, bareName) }
        val contains = rawName != null && known.none { NameRules.clash(it, bareName) } && containsName(rawName, known)
        return id.takeIf { fits || contains }
    }

    /** „ARD Das Erste HD“ enthält den Katalognamen „Das Erste“ als ganze Wörter (nicht „ZDFneo“ den Namen „ZDF“). */
    private fun containsName(rawName: String, known: List<String>): Boolean {
        val words = MediaTitle.parse(rawName).title.split(' ').filter(String::isNotBlank).take(MAX_WORDS)
        for (from in words.indices) {
            for (to in from + 1..words.size) {
                val part = MediaTitle.groupKey(words.subList(from, to).joinToString(" "), null).substringAfter(':')
                if (part.length >= 2 && MatchKeys.channelName(":$part")?.substringAfter(':') in known) return true
            }
        }
        return false
    }

    companion object {
        /** gzip-Daten; bewusst nicht „.gz“ – das Build-Werkzeug (aapt) würde die Datei sonst entpacken und umbenennen. */
        private const val ASSET = "channel-catalog.dat"
        private const val FIELDS = 3
        private const val MAX_WORDS = 8

        /** Leerer Katalog (Tests, fehlende Datei): Die Zuordnung arbeitet dann wie bis 3.33. */
        val EMPTY = ChannelCatalog(emptyMap(), emptyMap())

        /**
         * Aus dem Internet abgeglichener Katalog ([ChannelCatalogUpdate]), sonst der mitgelieferte. Ist die geladene
         * Datei unplausibel klein, gilt ebenfalls der mitgelieferte.
         */
        fun load(context: Context): ChannelCatalog {
            val online = java.io.File(context.filesDir, ChannelCatalogUpdate.FILE)
            if (online.isFile) {
                val catalog = try {
                    online.inputStream().use { parse(GZIPInputStream(it)) }
                } catch (e: java.io.IOException) {
                    AppLog.log("Zuordnung", "Aktueller Senderkatalog nicht lesbar (${e.javaClass.simpleName}) – nehme den mitgelieferten")
                    null
                }
                if (catalog != null && catalog.size >= ChannelCatalogUpdate.MIN_CHANNELS) return catalog
            }
            return try {
                context.assets.open(ASSET).use { parse(GZIPInputStream(it)) }
            } catch (e: java.io.IOException) {
                AppLog.log("Zuordnung", "Senderkatalog nicht lesbar: ${e.javaClass.simpleName}")
                EMPTY
            }
        }

        /**
         * Zeilen „id<TAB>land<TAB>name;zweitname…“; Zeilen mit # sind Kommentare.
         *
         * Führen zwei Sender im selben Land zum selben Schlüssel, gewinnt der echte Name vor dem abgeleiteten: „TRT 4K“
         * wird nach dem Entfernen der Qualität zu „TRT“ (= TRT 1) – das darf „TRT 1“ selbst nicht verdrängen. Erst bei
         * gleichem Rang ist der Name mehrdeutig und bleibt ohne Antwort.
         */
        fun parse(input: InputStream): ChannelCatalog {
            val byName = HashMap<String, Claim>()
            val byId = HashMap<String, String>()
            val nameKeys = HashMap<String, List<String>>()
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t', limit = FIELDS)
                if (parts.size < FIELDS) return@forEachLine
                val (id, iso, names) = parts
                byId[id.lowercase(Locale.ROOT)] = id
                val country = CountryDetector.fromIso(iso) ?: return@forEachLine
                val list = names.split(';')
                list.forEachIndexed { index, name -> claim(byName, name, country, id, rank(name, index)) }
                nameKeys[id] = list.mapNotNull { keyFor(it, country)?.let(MatchKeys::withoutCountry) }.distinct()
            }
            return ChannelCatalog(byName.mapNotNullValuesTo(), byId, nameKeys)
        }

        private fun HashMap<String, Claim>.mapNotNullValuesTo(): Map<String, String> {
            val out = HashMap<String, String>(size)
            for ((key, claim) in this) claim.id?.let { out[key] = it }
            return out
        }

        /** Rang: unveränderter Name zählt doppelt, der Hauptname (erster) einfach dazu. */
        private fun rank(name: String, index: Int): Int {
            val exact = MediaTitle.parse(name).title.equals(name.trim(), ignoreCase = true)
            return (if (exact) 2 else 0) + (if (index == 0) 1 else 0)
        }

        private fun claim(byName: HashMap<String, Claim>, name: String, country: String, id: String, rank: Int) {
            val key = keyFor(name, country) ?: return
            val current = byName[key]
            when {
                current == null || rank > current.rank -> byName[key] = Claim(rank, id)
                rank == current.rank && current.id != id -> current.id = null
            }
        }

        /** Derselbe Schlüssel wie beim Import (Qualität und Zierschrift weg, Synonyme vereinheitlicht). */
        internal fun keyFor(name: String, country: String): String? {
            val clean = MediaTitle.parse(name).title
            return MatchKeys.channelName(MediaTitle.groupKey(clean, country), name)
        }
    }

    /** Wer einen Namensschlüssel beansprucht; [id] = null: mehrdeutig auf diesem Rang. */
    private class Claim(val rank: Int, var id: String?)
}
