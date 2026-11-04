package com.mg.player.data.combined

import com.mg.player.core.util.MediaTitle
import com.mg.player.core.util.NameNormalizer
import com.mg.player.data.repository.TitleKey
import com.mg.player.data.tmdb.TitleCleaner
import java.util.Locale

/**
 * Vergleichsschlüssel für „Alle Zugänge“: Jeder Anbieter schreibt Namen anders, verglichen werden deshalb nur
 * Merkmale, die unabhängig von der Schreibweise sind (Plan Namensabgleich, 3.28).
 * - Sender: EPG-ID ([epg]) und Land + bereinigter Name ([channelName], aus dem beim Import berechneten groupKey –
 *   ohne Länderpräfix, Qualität, Zierschrift, aber mit Regionalfenstern und „+1“), beides über ein Synonym-Wörterbuch.
 * - Filme/Serien: TMDB-ID, sonst bereinigter Titel + Jahr ([title]).
 */
object MatchKeys {

    private val TLD_LENGTH = 2..3
    private val ignoredEpgIds = setOf("", "none", "null", "0", "-", "noepg", "no epg", "n/a", "na", "unknown")
    // „hd“ vor „fhd/uhd“: „ZDFHD“ ist ZDF + HD; die Lesart ohne F/U ergänzt [epgKeys].
    private val qualitySuffixes = listOf("hd", "sd", "4k", "hevc", "h265")

    /** Hauptschlüssel der EPG-ID ([epgKeys] liefert alle Lesarten). */
    fun epg(epgChannelId: String): String? = epgKeys(epgChannelId).firstOrNull()

    /**
     * "ZDF.de" → "zdf.de", "DasErste.de" → "ard.de". Angehängte Qualität wird abgeschnitten; bei „…FHD/UHD“ ist offen, ob
     * das F/U zum Namen gehört („ZDFHD“ = ZDF + HD, „ZDFFHD“ = ZDF + FHD) – dann gelten beide Lesarten.
     * Unbrauchbare Platzhalter („none“, reine Nummern) → leer.
     */
    fun epgKeys(epgChannelId: String): List<String> {
        val raw = epgChannelId.trim().lowercase(Locale.ROOT)
        if (raw in ignoredEpgIds || raw.all { it.isDigit() }) return emptyList()
        val dot = raw.lastIndexOf('.')
        val tld = raw.substring(dot + 1).takeIf { dot > 0 && it.length in TLD_LENGTH && it.all(Char::isLetter) }
        val base = NameNormalizer.normalize(if (tld != null) raw.substring(0, dot) else raw)
        val readings = LinkedHashSet<String>()
        val stripped = qualitySuffixes.firstOrNull { base.length > it.length + 1 && base.endsWith(it) }?.let { base.removeSuffix(it) }
        if (stripped == null) {
            readings += base
        } else {
            readings += stripped
            val fuBeforeHd = base.endsWith("hd") && stripped.last() in "fu"
            if (fuBeforeHd && stripped.length > 2) readings += stripped.dropLast(1)
        }
        return readings.filter { it.length >= 2 }.map { ChannelSynonyms.canonical(it) }.map { if (tld != null) "$it.$tld" else it }
    }

    /** groupKey „DE:daserste“ → „DE:ard“. Ohne Land bleibt der Doppelpunkt vorn („:ard“). */
    fun channelName(groupKey: String): String? {
        if (groupKey.isBlank()) return null
        val country = groupKey.substringBefore(':', "")
        val name = groupKey.substringAfter(':')
        if (name.length < 2) return null
        return "$country:${ChannelSynonyms.canonical(name)}"
    }

    /**
     * Wie [channelName], aber mit dem Originalnamen: Wird ein Name nur durch das Entfernen von „4K/UHD/8K“ zu einer
     * Dachmarke, die das Synonym-Wörterbuch auf einen nummerierten Sender abbildet („TRT 4K“ → „TRT“ → „TRT 1“), ist
     * es ein eigener UHD-Sender und kein TRT 1 in 4K. Gemeldet: „TRT 4K ist ein eingestellter Sender und hat nichts
     * mit TRT 1 oder TRT 2 zu tun.“ Echte Qualitätsvarianten („TRT 1 UHD“, „ZDF UHD“) bleiben unberührt.
     */
    fun channelName(groupKey: String, rawName: String?): String? {
        val canonical = channelName(groupKey) ?: return null
        if (rawName == null) return canonical
        val country = groupKey.substringBefore(':', "")
        val name = groupKey.substringAfter(':')
        val synonym = ChannelSynonyms.canonical(name)
        val addsNumber = synonym != name && synonym.startsWith(name) && synonym.substring(name.length).all(Char::isDigit)
        if (!addsNumber) return canonical
        val badges = MediaTitle.parse(rawName).badges
        return if ("4K" in badges || "8K" in badges) "$country:${name}uhd" else canonical
    }

    fun countryOf(nameKey: String): String = nameKey.substringBefore(':', "")

    fun withoutCountry(nameKey: String): String = nameKey.substringAfter(':')

    /**
     * Filme/Serien: „tmdb:603“ bzw. „t:matrix|1999“; null = zu unsicher (kurzer Titel, Film ohne Jahr und ID).
     * Ein Jahr ohne Klammern („Inception 2010 [FHD]“, „Dune.Part.Two.2024.MULTI“) erkennt der TMDB-Titelreiniger.
     */
    fun title(name: String, year: String?, tmdbId: String?, yearRequired: Boolean): TitleParts? {
        // Erst Qualität/Länderpräfix weg (MediaTitle), dann das Jahr ohne Klammern suchen (TitleCleaner).
        val cleaned = TitleCleaner.clean(MediaTitle.parse(name).title)
        val key = TitleKey.of(name, year, tmdbId, yearRequired = false)?.takeIf { it.year != null || !yearRequired || it.tmdbId != null }
            ?: TitleKey.of(cleaned.title, year ?: cleaned.year, tmdbId, yearRequired)
            ?: return null
        return TitleParts(tmdb = key.tmdbId?.let { "tmdb:$it" }, title = "t:${key.title}|${key.year.orEmpty()}", titleOnly = "t:${key.title}|")
    }

    data class TitleParts(val tmdb: String?, val title: String, val titleOnly: String)
}

/** Eingebautes Wörterbuch gleichbedeutender Sendernamen (wird nicht aus dem Netz nachgeladen). */
object ChannelSynonyms {
    private val map: Map<String, String> = buildMap {
        fun same(canonical: String, vararg aliases: String) {
            put(canonical, canonical)
            aliases.forEach { put(it, canonical) }
        }
        same("ard", "daserste", "erstes", "ard1", "dasersteard", "ardeins")
        same("rtl2", "rtlii", "rtlzwei")
        same("prosieben", "pro7")
        same("prosiebenmaxx", "pro7maxx")
        same("sat1", "sateins")
        same("kabel1", "kabeleins")
        same("kabel1doku", "kabeleinsdoku")
        same("rtlnitro", "nitro")
        same("welt", "weltn24", "n24")
        same("3sat", "dreisat")
        same("kika", "kinderkanal", "kikavonardundzdf")
        same("tagesschau24", "tagesschau")
        same("eurosport1", "eurosport", "eurosporteins")
        same("superrtl", "rtlsuper")
        same("bbcone", "bbc1")
        same("bbctwo", "bbc2")
        same("bbcthree", "bbc3")
        same("bbcfour", "bbc4")
        same("itv1", "itv")
        same("channel4", "ch4")
        same("channel5", "ch5")
        same("trt1", "trt")
        same("kanald", "kanaldhd")
        same("orf1", "orfeins")
        same("orf2", "orfzwei")
        same("srf1", "srfeins")
        same("srfzwei", "srf2")
    }

    fun canonical(name: String): String = map[name] ?: name
}
