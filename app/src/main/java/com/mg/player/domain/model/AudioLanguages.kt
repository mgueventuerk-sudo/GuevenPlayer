package com.mg.player.domain.model

/**
 * Bevorzugte Sprachen (ab 3.60, Einstellungen → Wiedergabe → Sprache): eine Standardsprache, optional weitere in
 * Reihenfolge. Gilt überall: Tonspur in jedem Player (ExoPlayer, LibVLC, System-Player) und die Wahl der Quelle in
 * „Alle Zugänge“. Mit [AppSettings.onlyPreferredLanguages] nimmt die App nie von sich aus eine Fassung in einer anderen
 * Sprache, solange es eine in den gewählten gibt. Rein (ohne Android), damit voll testbar.
 */
object AudioLanguages {

    /** Wählbare Sprachen (ISO 639-1) mit deutschem Namen, in Anzeigereihenfolge. */
    val choices: List<Pair<String, String>> = listOf(
        "de" to "Deutsch",
        "tr" to "Türkisch",
        "en" to "Englisch",
        "fr" to "Französisch",
        "it" to "Italienisch",
        "es" to "Spanisch",
        "pt" to "Portugiesisch",
        "nl" to "Niederländisch",
        "pl" to "Polnisch",
        "ru" to "Russisch",
        "ar" to "Arabisch",
        "ku" to "Kurdisch",
        "fa" to "Persisch",
    )

    val DEFAULT: List<String> = listOf("de")

    fun label(code: String): String = choices.firstOrNull { it.first == code }?.second ?: code

    /** ISO 639-2 (bibliographisch und terminologisch) dazu: Streams kennzeichnen Spuren mal „de“, mal „deu“ oder „ger“. */
    private val iso3 = mapOf(
        "de" to listOf("deu", "ger"), "tr" to listOf("tur"), "en" to listOf("eng"), "fr" to listOf("fra", "fre"),
        "it" to listOf("ita"), "es" to listOf("spa"), "pt" to listOf("por"), "nl" to listOf("nld", "dut"),
        "pl" to listOf("pol"), "ru" to listOf("rus"), "ar" to listOf("ara"), "ku" to listOf("kur"), "fa" to listOf("fas", "per"),
    )

    /** Kürzel für die Tonspur-Wahl der Player, Reihenfolge = Vorrang: „de, deu, ger, tr, tur“. */
    fun trackCodes(languages: List<String>): List<String> = sanitize(languages).flatMap { listOf(it) + iso3[it].orEmpty() }

    /**
     * Kennungen, unter denen „Alle Zugänge“ die Fassung einer Quelle führt (aus Titel oder Gruppe, siehe CountryDetector):
     * Deutsch steckt in DE, AT und CH, Englisch in UK, US, EN, CA und AU.
     */
    private val sourceCodes = mapOf(
        "de" to setOf("DE", "AT", "CH"), "tr" to setOf("TR"), "en" to setOf("EN", "UK", "US", "CA", "AU"),
        "fr" to setOf("FR", "BE"), "it" to setOf("IT"), "es" to setOf("ES", "LATAM"), "pt" to setOf("PT", "BR"),
        "nl" to setOf("NL"), "pl" to setOf("PL"), "ru" to setOf("RU"), "ar" to setOf("AR"), "ku" to setOf("KU"), "fa" to setOf("FA"),
    )

    /** Sprache einer Quelle aus ihrer Kennung („AT“ → „de“), null = unbekannt. */
    fun languageOfSource(code: String?): String? = code?.uppercase()?.let { c -> sourceCodes.entries.firstOrNull { c in it.value }?.key }

    /**
     * Rang einer Quelle nach Sprache: Position in [languages] (0 = Standardsprache), unbekannte Sprache danach, eine
     * erkannt fremde Sprache ganz hinten. Unbekannt vor fremd: Viele Anbieter schreiben die Sprache nicht in den Titel.
     */
    fun rank(sourceCode: String?, languages: List<String>): Int {
        val list = sanitize(languages)
        val language = languageOfSource(sourceCode) ?: return list.size
        val index = list.indexOf(language)
        return if (index >= 0) index else list.size + 1
    }

    /** „Nur diese Sprachen“: Quelle erkannt in einer anderen Sprache? Unbekannte gelten nicht als fremd. */
    fun isForeign(sourceCode: String?, languages: List<String>): Boolean {
        val language = languageOfSource(sourceCode) ?: return false
        return language !in sanitize(languages)
    }

    /** Gespeicherte Liste bereinigen: nur bekannte Kürzel, ohne Doppelte; leer → [DEFAULT]. */
    fun sanitize(languages: List<String>): List<String> =
        languages.map { it.trim().lowercase() }.filter { code -> choices.any { it.first == code } }.distinct().ifEmpty { DEFAULT }

    /** Neue Standardsprache: steht danach vorn, die übrigen bleiben in ihrer Reihenfolge dahinter. */
    fun withDefault(languages: List<String>, code: String): List<String> = sanitize(listOf(code) + sanitize(languages).filter { it != code })

    /** Weitere Sprache an- oder abwählen; die Standardsprache (erste) bleibt immer. */
    fun toggled(languages: List<String>, code: String): List<String> {
        val list = sanitize(languages)
        return when {
            code == list.first() -> list
            code in list -> list - code
            else -> list + code
        }
    }
}
