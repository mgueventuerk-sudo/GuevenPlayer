package com.mg.player.core.util

import java.util.Locale

/** Ein Land bzw. eine Sprachregion, wie sie in IPTV-Gruppennamen vorkommt. */
data class CountryInfo(val code: String, val label: String)

/**
 * Erkennt aus Gruppennamen wie "DE | Sport", "TR: Ulusal", "|AR| News", "VOD - FR", "Filme [TR]"
 * oder "GERMANY HD", zu welchem Land bzw. welcher Sprache eine Gruppe gehört.
 * Arabische Länder werden zu "Arabisch", Skandinavien und Ex-Jugoslawien jeweils zusammengefasst.
 */
object CountryDetector {

    val countries: Map<String, CountryInfo> = listOf(
        "DE" to "Deutschland", "AT" to "Österreich", "CH" to "Schweiz", "TR" to "Türkei", "AR" to "Arabisch",
        "KU" to "Kurdisch", "FA" to "Persisch", "UK" to "Großbritannien", "EN" to "Englisch", "US" to "USA",
        "CA" to "Kanada", "FR" to "Frankreich", "BE" to "Belgien", "NL" to "Niederlande", "IT" to "Italien",
        "ES" to "Spanien", "PT" to "Portugal", "BR" to "Brasilien", "LATAM" to "Lateinamerika", "PL" to "Polen",
        "RU" to "Russland", "UA" to "Ukraine", "RO" to "Rumänien", "BG" to "Bulgarien", "GR" to "Griechenland",
        "AL" to "Albanien", "BALKAN" to "Ex-Jugoslawien", "HU" to "Ungarn", "CZ" to "Tschechien",
        "SK" to "Slowakei", "NORDIC" to "Skandinavien", "IN" to "Indien", "PK" to "Pakistan",
        "AF" to "Afghanistan", "AZ" to "Aserbaidschan", "IL" to "Israel", "AU" to "Australien",
        "AFRICA" to "Afrika", "ASIA" to "Asien",
    ).associate { (code, label) -> code to CountryInfo(code, label) }

    /** Kürzel (2–4 Buchstaben), wie sie meist vor dem Gruppennamen stehen. */
    private val shortCodes: Map<String, String> = buildMap {
        countries.keys.filter { it.length == 2 }.forEach { put(it, it) }
        putAll(
            mapOf(
                // Manche Anbieter schreiben "GE" statt "DE" für deutsche Inhalte.
                "GE" to "DE", "GER" to "DE", "DEU" to "DE", "DACH" to "DE", "TUR" to "TR", "TRK" to "TR", "ARA" to "AR", "ARB" to "AR",
                "GB" to "UK", "ENG" to "EN", "USA" to "US", "FRA" to "FR", "ITA" to "IT", "SPA" to "ES", "ESP" to "ES",
                "POR" to "PT", "BRA" to "BR", "NED" to "NL", "POL" to "PL", "RUS" to "RU", "ROM" to "RO", "ALB" to "AL",
                "EXYU" to "BALKAN", "YU" to "BALKAN", "RS" to "BALKAN", "SRB" to "BALKAN", "HR" to "BALKAN",
                "HRV" to "BALKAN", "BA" to "BALKAN", "BIH" to "BALKAN", "SI" to "BALKAN", "MK" to "BALKAN",
                "SE" to "NORDIC", "SWE" to "NORDIC", "NO" to "NORDIC", "DK" to "NORDIC", "FI" to "NORDIC",
                "IR" to "FA", "IRN" to "FA", "AFG" to "AF", "IE" to "UK", "NZ" to "AU", "MX" to "LATAM",
                "CO" to "LATAM", "CL" to "LATAM", "LAT" to "LATAM", "CN" to "ASIA", "JP" to "ASIA", "KR" to "ASIA",
                "TH" to "ASIA", "VN" to "ASIA", "PH" to "ASIA", "XK" to "AL",
                // Arabische Länder
                "SA" to "AR", "KSA" to "AR", "AE" to "AR", "UAE" to "AR", "EG" to "AR", "MA" to "AR", "DZ" to "AR",
                "TN" to "AR", "IQ" to "AR", "SY" to "AR", "LB" to "AR", "JO" to "AR", "KW" to "AR", "QA" to "AR",
                "BH" to "AR", "OM" to "AR", "YE" to "AR", "LY" to "AR", "SD" to "AR", "PS" to "AR",
            ),
        )
    }

    /** Ausgeschriebene Länder- und Sprachnamen – werden überall im Namen erkannt. */
    private val words: Map<String, String> = mapOf(
        "GERMANY" to "DE", "GERMAN" to "DE", "DEUTSCH" to "DE", "DEUTSCHLAND" to "DE", "ALMANYA" to "DE",
        "AUSTRIA" to "AT", "ÖSTERREICH" to "AT", "OESTERREICH" to "AT", "SCHWEIZ" to "CH", "SWISS" to "CH",
        "SWITZERLAND" to "CH", "TURKEY" to "TR", "TURKIYE" to "TR", "TÜRKIYE" to "TR", "TURKISH" to "TR",
        "TÜRK" to "TR", "TURK" to "TR", "TÜRKEI" to "TR", "ARABIC" to "AR", "ARAB" to "AR", "ARABIA" to "AR",
        "ARABISCH" to "AR", "MAGHREB" to "AR", "KURD" to "KU", "KURDISH" to "KU", "KURDISTAN" to "KU",
        "KURDÎ" to "KU", "IRAN" to "FA", "PERSIAN" to "FA", "FARSI" to "FA", "ENGLAND" to "UK", "BRITISH" to "UK",
        "ENGLISH" to "EN", "AMERICA" to "US", "CANADA" to "CA", "FRANCE" to "FR", "FRENCH" to "FR",
        "FRANCAIS" to "FR", "BELGIUM" to "BE", "BELGIE" to "BE", "NETHERLANDS" to "NL", "NEDERLAND" to "NL",
        "DUTCH" to "NL", "HOLLAND" to "NL", "ITALY" to "IT", "ITALIA" to "IT", "ITALIAN" to "IT", "SPAIN" to "ES",
        "ESPANA" to "ES", "ESPAÑA" to "ES", "SPANISH" to "ES", "PORTUGAL" to "PT", "PORTUGUESE" to "PT",
        "BRAZIL" to "BR", "BRASIL" to "BR", "LATINO" to "LATAM", "LATAM" to "LATAM", "MEXICO" to "LATAM",
        "ARGENTINA" to "LATAM", "COLOMBIA" to "LATAM", "POLAND" to "PL", "POLSKA" to "PL", "POLISH" to "PL",
        "RUSSIA" to "RU", "RUSSIAN" to "RU", "UKRAINE" to "UA", "ROMANIA" to "RO", "BULGARIA" to "BG",
        "GREECE" to "GR", "GREEK" to "GR", "HELLAS" to "GR", "ALBANIA" to "AL", "SHQIP" to "AL",
        "SHQIPERI" to "AL", "KOSOVO" to "AL", "BALKAN" to "BALKAN", "SERBIA" to "BALKAN", "CROATIA" to "BALKAN",
        "BOSNIA" to "BALKAN", "SLOVENIA" to "BALKAN", "MACEDONIA" to "BALKAN", "MONTENEGRO" to "BALKAN",
        "HUNGARY" to "HU", "CZECH" to "CZ", "SLOVAKIA" to "SK", "SWEDEN" to "NORDIC", "SVERIGE" to "NORDIC",
        "NORWAY" to "NORDIC", "DENMARK" to "NORDIC", "FINLAND" to "NORDIC", "NORDIC" to "NORDIC",
        "SCANDINAVIA" to "NORDIC", "INDIA" to "IN", "INDIAN" to "IN", "HINDI" to "IN", "PUNJABI" to "IN", "TAMIL" to "IN",
        "PAKISTAN" to "PK", "URDU" to "PK", "AFGHANISTAN" to "AF", "AZERBAIJAN" to "AZ", "ISRAEL" to "IL",
        "AUSTRALIA" to "AU", "AFRICA" to "AFRICA", "AFRIQUE" to "AFRICA", "NIGERIA" to "AFRICA", "ASIA" to "ASIA",
        "CHINA" to "ASIA", "CHINESE" to "ASIA", "JAPAN" to "ASIA", "KOREA" to "ASIA", "THAILAND" to "ASIA",
        "PHILIPPINES" to "ASIA", "INDONESIA" to "ASIA",
    )

    /** Kürzel, die auch normale Wörter sind – zählen nur mit eindeutigem Trennzeichen ("IT |", "[NO]"). */
    private val ambiguous = setOf("IN", "NO", "IT", "AL", "BE", "ID", "AT", "AM", "IS", "OR", "SI", "MA", "CO", "PS", "OM", "SE", "BA")

    /** Häufige Zusätze, die keine Länder sind. */
    private val noise = setOf("VOD", "TV", "HD", "FHD", "UHD", "SD", "4K", "8K", "HEVC", "H265", "24", "7", "PPV", "LIVE", "VIP", "NEW")

    private val splitter = Regex("[^A-ZÄÖÜÇŞĞÎ0-9]+")
    private val exYu = Regex("EX[\\s-]YU")
    private const val SEPARATORS = "|:▎┃•\\-–—/\\[\\]()»>#★●"

    /**
     * ISO-3166-Ländercode (z. B. aus dem Senderkatalog) → Kürzel dieser App („SE“ → „NORDIC“). „AR“ ist hier
     * Argentinien, nicht Arabisch (das App-Kürzel „AR“ steht für arabische Sender). Unbekannt → null.
     */
    fun fromIso(iso: String): String? {
        val upper = iso.uppercase(Locale.ROOT)
        if (upper in isoOverrides) return isoOverrides[upper]
        return shortCodes[upper] ?: upper.takeIf { it in countries }
    }

    private val isoOverrides = mapOf("AR" to "LATAM", "PE" to "LATAM", "VE" to "LATAM", "EC" to "LATAM", "UY" to "LATAM", "PY" to "LATAM", "BO" to "LATAM")

    /** Liefert den Ländercode (z. B. "DE") oder null, wenn der Name keinen Hinweis enthält. */
    fun detect(raw: String): String? {
        flagCode(raw)?.let { iso -> (shortCodes[iso] ?: iso.takeIf { it in countries })?.let { return it } }
        // Zierschrift der Anbieter ("ᴅᴇ ┃ ꜱᴘᴏʀᴛ") wie normale Schrift behandeln.
        val name = TextFold.plain(raw)
        // Regex nur, wenn überhaupt „EX“ vorkommt – bis 3.23 wurde sie für jeden Sendernamen neu übersetzt (Import, Plan B4).
        val upper = name.uppercase(Locale.ROOT).replace('İ', 'I').let { if ("EX" in it) exYu.replace(it, "EXYU") else it }
        val tokens = upper.split(splitter).filter { it.isNotEmpty() && it !in noise }
        tokens.take(3).forEachIndexed { index, token ->
            val code = shortCodes[token] ?: return@forEachIndexed
            val delimited = isDelimited(upper, token)
            val accepted = when {
                token in ambiguous -> delimited

                index == 0 -> true

                // "LIGA DE CAMPEONES" ist spanisch – "DE" mitten im Namen zählt nur mit Trennzeichen.
                else -> delimited || index == tokens.lastIndex
            }
            if (accepted) return code
        }
        tokens.forEach { token -> words[token]?.let { return it } }
        if (name.any { it in '؀'..'ۿ' }) return "AR"
        if (name.any { it in 'Ͱ'..'Ͽ' }) return "GR"
        if (tokens.size > 1) {
            val last = tokens.last()
            shortCodes[last]?.takeIf { last !in ambiguous }?.let { return it }
        }
        return null
    }

    fun info(code: String): CountryInfo = countries[code] ?: CountryInfo(code, code)

    /** Normalisiert ein einzelnes Kürzel oder Wort: "GE", "GER", "DEU", "GERMANY" -> "DE"; unbekannt -> null. */
    fun codeFor(token: String): String? {
        val upper = token.trim().uppercase(Locale.ROOT).replace('İ', 'I')
        return shortCodes[upper] ?: words[upper]
    }

    private val delimiterPatterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun isDelimited(upper: String, token: String): Boolean =
        delimiterPatterns.getOrPut(token) { Regex("(^|[$SEPARATORS])\\s*${Regex.escape(token)}\\s*($|[$SEPARATORS])") }
            .containsMatchIn(upper)

    /** Flaggen-Emoji (zwei Regional-Indicator-Zeichen) -> ISO-Code, z. B. 🇩🇪 -> "DE". */
    private fun flagCode(name: String): String? {
        var previous = -1
        var index = 0
        while (index < name.length) {
            val point = name.codePointAt(index)
            if (point in REGIONAL_A..REGIONAL_Z && previous in REGIONAL_A..REGIONAL_Z) {
                return "${'A' + (previous - REGIONAL_A)}${'A' + (point - REGIONAL_A)}"
            }
            previous = point
            index += Character.charCount(point)
        }
        return null
    }

    private const val REGIONAL_A = 0x1F1E6
    private const val REGIONAL_Z = 0x1F1FF
}
