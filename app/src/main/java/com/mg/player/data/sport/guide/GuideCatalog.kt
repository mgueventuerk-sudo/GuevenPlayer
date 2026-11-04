package com.mg.player.data.sport.guide

import com.mg.player.core.util.TextFold
import com.mg.player.data.search.SearchText
import java.util.Locale

/**
 * Welche öffentlichen Programmdateien geladen werden und was davon Sport ist. Quelle: epgshare01 (XMLTV, frei, ohne
 * Schlüssel). Geladen werden nur Länder, aus denen der eigene Zugang Sender hat.
 */
object GuideCatalog {

    @Volatile var baseUrl = "https://epgshare01.online/epgshare01"

    /** Land (wie im Sendernamen „DE|“) → Dateien. */
    private val FILES = mapOf(
        "de" to listOf("DE1"), "at" to listOf("AT1"), "ch" to listOf("CH1"), "tr" to listOf("TR1", "TR3"),
        "uk" to listOf("UK1"), "nl" to listOf("NL1"), "fr" to listOf("FR1"), "it" to listOf("IT1"), "es" to listOf("ES1"),
        "pl" to listOf("PL1"), "pt" to listOf("PT1"), "us" to listOf("US_SPORTS1"), "ar" to listOf("BEIN1"),
    )

    /** Ländercodes, die als Vorsatz im Sendernamen („DE|“, „TR:“, „[UK]“) bzw. als Endung der Kennung („.de“) vorkommen. */
    val COUNTRIES: Set<String> = FILES.keys + setOf("gb", "en", "ger", "tur", "be", "dk", "se", "no", "gr", "ro", "al", "rs", "hr", "ba")

    /** Höchstens so viele Länder laden (Zeit und Datenmenge am Fire-TV-Stick). */
    const val MAX_COUNTRIES = 4

    fun urlFor(file: String) = "$baseUrl/epg_ripper_$file.xml.gz"

    fun filesFor(country: String): List<String> = FILES[canonicalCountry(country)].orEmpty()

    fun canonicalCountry(code: String): String = when (val c = code.lowercase(Locale.ROOT)) {
        "gb", "en" -> "uk"
        "ger" -> "de"
        "tur" -> "tr"
        else -> c
    }

    /** Länder mit den meisten Sportsendern im eigenen Zugang zuerst (nur solche, für die es Programmdateien gibt). */
    fun countriesToLoad(sportChannelCountries: List<String>): List<String> = sportChannelCountries
        .map(::canonicalCountry)
        .filter { it in FILES }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(MAX_COUNTRIES)
        .map { it.key }

    private val SPORT_CHANNEL_WORDS = listOf(
        "sport", "spor", "dazn", "bein", "eurosport", "mysports", "blue sport", "sportdigital", "setanta", "arena",
        "tnt sports", "premier sports", "espn", "ziggo", "viaplay", "eleven", "tivibu", "exxen", "tabii", "ppv", "event",
        "laliga", "movistar", "canal+ sport", "sky sport", "sky bundesliga", "nfl", "nba", "ufc", "motorvision",
    )

    /** Sportsender: Name bzw. Kennung enthält ein typisches Wort („Sky Sport 1“, „beIN SPORTS 2“, „TRT Spor“, „DAZN 1“). */
    fun isSportChannel(id: String, names: List<String>): Boolean {
        val text = plain((listOf(id.replace('.', ' ').replace('_', ' ')) + names).joinToString(" "))
        return SPORT_CHANNEL_WORDS.any { it in text }
    }

    private val SPORT_PROGRAM_WORDS = listOf(
        "fußball", "fussball", "futbol", "football", "soccer", "bundesliga", "süper lig", "super lig", "champions league",
        "şampiyonlar", "europa league", "länderspiel", "milli takım", "formel 1", "formula 1", "motogp", "ufc", "boxen",
        "boks", "darts", "handball", "hentbol", "euroleague", "olympia", "olimpiyat", "nfl", "nba", "nhl", "tennis",
        "wimbledon", "kupası", "pokal", "live:", "canlı",
    )

    /** Sportsendung auf einem allgemeinen Sender (z. B. Länderspiel im ZDF, Milli Takım auf TV8). */
    fun isSportProgram(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT)
        return SPORT_PROGRAM_WORDS.any { it in t }
    }

    private val QUALITY = setOf(
        "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "raw", "50fps", "60fps", "1080p",
        "1080i", "720p", "2160p", "hdr", "backup", "orig", "vip", "live", "tv",
    )
    private val PREFIX = Regex("""^\W*([a-z]{2,3})\s*[|:\]\-┃▎│¦/]+\s*""")

    /** Zierschrift („ᴅᴇ ┃ ꜱᴋʏ ꜱᴘᴏʀᴛ“) in normale Buchstaben, klein. */
    private fun plain(s: String) = TextFold.plain(s).lowercase(Locale.ROOT)

    /** Land aus dem Vorsatz des Sendernamens („DE| SKY SPORT“ → de), sonst null. */
    fun countryOfName(name: String): String? {
        val m = PREFIX.find(plain(name)) ?: return null
        return m.groupValues[1].takeIf { it in COUNTRIES }?.let(::canonicalCountry)
    }

    /** Land aus der Kennung des Programms („Sky.Sport.1.de“ → de), sonst null. */
    fun countryOfId(id: String): String? = id.substringAfterLast('.', "").lowercase(Locale.ROOT)
        .takeIf { it.length in 2..3 && it in COUNTRIES }?.let(::canonicalCountry)

    /**
     * Vergleichsschlüssel eines Sendernamens: ohne Länder-Vorsatz, ohne Qualitätsangaben, ohne Satzzeichen.
     * „DE| SKY SPORT BUNDESLIGA 1 UHD“, „Sky Sport Bundesliga 1“ und „Sky.Sport.Bundesliga.1.de“ → „skysportbundesliga1“.
     */
    fun nameKey(raw: String): String {
        var s = plain(raw.trim())
        if (countryOfName(s) != null) s = PREFIX.replace(s, "")
        return SearchText.words(s.replace("+", " plus ").replace("&amp;", "&")).filter { it !in QUALITY }.joinToString("")
    }

    /** Schlüssel der Kennung: Endung („.de“) weg, Punkte und Unterstriche als Trenner. */
    fun idKey(id: String): String {
        val base = if (countryOfId(id) != null) id.substringBeforeLast('.') else id
        return nameKey(base.replace('.', ' ').replace('_', ' '))
    }

    /** Lockerer Schlüssel: „Sky Bundesliga 1“ = „Sky Sport Bundesliga 1“. Nur eindeutig und ab 6 Zeichen benutzt. */
    fun looseKey(key: String): String = key.replace("sports", "").replace("sport", "")
}
