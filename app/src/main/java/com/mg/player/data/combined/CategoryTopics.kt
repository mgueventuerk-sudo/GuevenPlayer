package com.mg.player.data.combined

import com.mg.player.core.util.CountryDetector
import com.mg.player.core.util.NameNormalizer
import com.mg.player.core.util.TextFold
import java.util.Locale

/**
 * Ordnet Gruppen verschiedener Anbieter einheitlich ein: Land + Thema. „DE | Sport“, „GERMANY SPORTS HD“ und
 * „Deutschland ▸ Sport“ werden zu „Deutschland · Sport“. Das Thema kommt über Stichwörter in mehreren Sprachen.
 * Gruppen ohne erkennbares Thema werden nur mit gleich benannten Gruppen anderer Zugänge zusammengelegt.
 */
object CategoryTopics {

    enum class Topic(val label: String, vararg val words: String) {
        GENERAL("Vollprogramm", "vollprogramm", "general", "genel", "ulusal", "national", "allgemein", "generalist", "hauptsender"),
        ENTERTAINMENT("Unterhaltung", "unterhaltung", "entertainment", "eglence", "divertissement", "intrattenimento"),
        SPORT("Sport", "sport", "sports", "spor", "deportes", "desporto", "رياضة", "football", "fussball", "futbol"),
        NEWS("Nachrichten", "news", "nachrichten", "haber", "noticias", "actualite", "notizie", "أخبار"),
        KIDS("Kinder", "kids", "kinder", "cocuk", "enfants", "infantil", "cartoon", "bambini", "أطفال"),
        DOCU("Doku", "doku", "documentary", "documentaries", "dokumentation", "belgesel", "documentaire", "documental", "وثائقي"),
        MUSIC("Musik", "music", "musik", "muzik", "musique", "musica", "موسيقى"),
        RELIGION("Religion", "religion", "religious", "islam", "islamic", "quran", "christian", "dini", "ديني"),
        REGIONAL("Regional", "regional", "lokal", "local", "regionale"),
        ADULT("Erwachsene", "adult", "adults", "xxx", "erotik", "erotic"),
        ACTION("Action", "action", "aksiyon"),
        COMEDY("Komödie", "komodie", "comedy", "komedi", "comedie", "commedia"),
        DRAMA("Drama", "drama", "dram"),
        HORROR("Horror", "horror", "korku"),
        THRILLER("Thriller", "thriller", "gerilim"),
        CRIME("Krimi", "krimi", "crime", "suc", "polizia"),
        ANIMATION("Animation", "animation", "animiert", "animasyon", "anime", "zeichentrick"),
        SCIFI("Science-Fiction", "scifi", "science fiction", "sciencefiction", "bilim kurgu", "fantasy"),
        ROMANCE("Romantik", "romantik", "romance", "romantic", "romantik"),
        FAMILY("Familie", "familie", "family", "aile", "famille"),
        WAR("Krieg", "krieg", "war", "savas"),
        HISTORY("Geschichte", "geschichte", "history", "historie", "tarih"),
        WESTERN("Western", "western"),
        ADVENTURE("Abenteuer", "abenteuer", "adventure", "macera"),
        NETFLIX("Netflix", "netflix"),
        AMAZON("Prime Video", "amazon", "prime video", "primevideo"),
        DISNEY("Disney+", "disney"),
        APPLE("Apple TV+", "apple tv", "appletv"),
        SKY("Sky", "sky"),
        UHD("4K / UHD", "4k", "uhd", "2160p"),
    }

    private const val MIN_PREFIX = 4
    private val separators = Regex("[^\\p{L}\\p{N}+]+")

    data class Classified(val key: String, val country: String?, val topic: Topic?, val label: String)

    /** [type] = LIVE/MOVIE/SERIES – Gruppen werden nur innerhalb derselben Art zusammengelegt. */
    fun classify(type: String, name: String): Classified {
        val country = CountryDetector.detect(name)
        val folded = " " + TextFold.plain(name).lowercase(Locale.ROOT)
            .replace('ä', 'a').replace('ö', 'o').replace('ü', 'u').replace('ş', 's').replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i')
            .replace(separators, " ") + " "
        // Ganzes Wort oder Wortanfang („Sports“ → Sport), aber nicht mitten im Wort („Transport“).
        val topic = Topic.entries.firstOrNull { t -> t.words.any { w -> " $w " in folded || (w.length >= MIN_PREFIX && " $w" in folded) } }
        val countryLabel = country?.let { CountryDetector.info(it).label }
        return if (topic != null) {
            Classified("$type|${country.orEmpty()}|${topic.name}", country, topic, listOfNotNull(countryLabel, topic.label).joinToString(" · "))
        } else {
            // Länderangaben gehören zum Land, nicht zum Namen („DE | Eigene“ = „DE: Eigene“ = „Eigene [DE]“).
            val rest = folded.trim().split(' ').filter { it.isNotBlank() && CountryDetector.codeFor(it) == null }
                .joinToString("").ifBlank { NameNormalizer.normalize(name) }
            Classified("$type|${country.orEmpty()}|n:$rest", country, null, name.trim())
        }
    }
}
