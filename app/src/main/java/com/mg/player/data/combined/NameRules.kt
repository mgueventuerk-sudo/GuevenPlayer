package com.mg.player.data.combined

/**
 * Wann zwei Sendernamen (bereinigte Schlüssel ohne Land, Synonyme vereinheitlicht: „prosieben“, „zdfneo“) sicher
 * **verschiedene** Sender bezeichnen – unabhängig von Katalog und EPG-ID:
 *
 * 1. **Andere Zahl** bei gleichen Buchstaben: TRT 1/TRT 2, Sky Sport 1/2, RTL/RTL 2, 3sat/Sat.1, Télétoon+/Télétoon+1.
 * 2. **Zusatzwort** vorn oder hinten: ProSieben/ProSieben MAXX, ZDF/ZDFneo, RTL/RTL Nitro, beIN Sports/beIN Sports
 *    XTRA, Channel 4/Channel 4 +1, Sport/Eurosport. Ausgenommen sind Wörter, die nichts über den Sender sagen
 *    (Qualität, „TV“, Ländernamen, Hinweise wie „Events only“) – „Comedy Central“ = „Comedy Central France“.
 *
 * Gefunden an einer echten Liste mit 21.822 Sendern (04.11.2026): Die frühere reine Ähnlichkeit (Bigramme) hielt
 * „prosieben“ und „prosiebenmaxx“ für denselben Sender.
 */
internal object NameRules {
    private const val SIMILAR = 0.8
    private const val MIN_STEM = 3
    private const val MIN_COMPOUND = 3
    private const val MIN_EXTRA = 2

    /** Wörter, die als Zusatz nichts ändern (alle klein, ohne Leerzeichen – die Schlüssel haben keine). */
    private val neutral = listOf(
        "tv", "channel", "kanal", "hd", "fhd", "uhd", "sd", "hevc", "raw", "4k", "8k", "ov", "live",
        "events", "eventsonly", "only", "backup", "bk", "vip", "fbs",
        "de", "deutschland", "germany", "at", "austria", "osterreich", "ch", "schweiz", "fr", "france", "es", "espana",
        "it", "italia", "italy", "uk", "gb", "us", "usa", "nl", "nederland", "tr", "turkiye", "turkey", "pl", "polska",
        "latam", "latino", "balkan", "exyu", "nordic", "asia", "arab", "arabic", "africa", "afrique", "brasil", "brazil",
        "ultra", "tele", "du", "la", "le", "les", "the", "of", "and", "et",
    ).sortedByDescending { it.length }

    /** Sind [a] und [b] nach Zahl oder Zusatzwort sicher verschiedene Sender? */
    fun clash(a: String, b: String): Boolean {
        if (a == b || a.length < MIN_EXTRA || b.length < MIN_EXTRA) return false
        return numbersDiffer(a, b) || differentTails(a, b)
    }

    /** Irgendein Name der einen Gruppe widerspricht irgendeinem der anderen. */
    fun clash(a: Set<String>?, b: Set<String>?): Boolean {
        if (a == null || b == null) return false
        return a.any { x -> b.any { y -> clash(x, y) } }
    }

    fun merge(a: Set<String>?, b: Set<String>?): Set<String>? = when {
        a == null -> b
        b == null -> a
        else -> a + b
    }

    /**
     * Namen passen für eine Verbindung über die EPG-ID: kein Widerspruch nach [clash] und ähnlich geschrieben
     * (Bigramm-Dice ≥ 0,7 – „13emerue“/„13erue“). Ohne Namen: ja.
     */
    fun compatible(a: String?, b: String?): Boolean {
        if (a == null || b == null || a == b) return true
        return !clash(a, b) && dice(a, b) >= SIMILAR
    }

    private fun numbersDiffer(a: String, b: String): Boolean {
        val (la, da) = split(a)
        val (lb, db) = split(b)
        return la == lb && da != db
    }

    /** „trt1“ → („trt“, „1“); „channel4plus1“ → („channel“, „4/1“). */
    private fun split(key: String): Pair<String, String> {
        val letters = StringBuilder()
        val digits = StringBuilder()
        var lastDigit = false
        for (ch in key.replace("plus", "")) {
            if (ch.isDigit()) {
                if (!lastDigit && digits.isNotEmpty()) digits.append('/')
                digits.append(ch)
            } else {
                letters.append(ch)
            }
            lastDigit = ch.isDigit()
        }
        return letters.toString() to digits.toString()
    }

    /**
     * Gleicher Stamm, aber ein bedeutsamer Unterschied vorn, in der Mitte oder hinten: „prosieben“/„prosiebenmaxx“,
     * „beinsports9“/„beinsportsxtra3“, „discoverywest“/„discoveryidwest“, „hbofamilyeast“/„hbofamilywest“.
     * Kein Unterschied: Tippfehler mit einem Zeichen („aljazeera“/„aljazera“), neutrale Wörter („ushuaiatv“),
     * vertauschte Reihenfolge („starsports1hindi“/„starsportshindi1“), Synonyme („arddaserste“ = „ard“).
     */
    private fun differentTails(a: String, b: String): Boolean {
        var p = 0
        while (p < a.length && p < b.length && a[p] == b[p]) p++
        var q = 0
        while (q < a.length - p && q < b.length - p && a[a.length - 1 - q] == b[b.length - 1 - q]) q++
        if (p + q < MIN_STEM) return false
        val ta = a.substring(p, a.length - q)
        val tb = b.substring(p, b.length - q)
        val sameLetters = ta.toList().sorted() == tb.toList().sorted()
        val synonym = (ta.isEmpty() && ChannelSynonyms.canonical(tb) == a) || (tb.isEmpty() && ChannelSynonyms.canonical(ta) == b)
        return !sameLetters && !synonym && (significant(ta) || significant(tb))
    }

    private fun significant(tail: String): Boolean = tail.length >= MIN_EXTRA && !onlyNeutral(tail)

    /**
     * Ist [rest] ein neutrales Wort oder eine Folge neutraler Wörter mit mindestens 3 Buchstaben („tvhd“, „france“)?
     * Zweibuchstabige Kürzel nur einzeln – sonst ergab „pl“ + „us“ das neutrale „plus“ (FanDuel Florida = Florida Plus).
     */
    private fun onlyNeutral(rest: String): Boolean {
        if (rest in neutral) return true
        val ok = BooleanArray(rest.length + 1)
        ok[0] = true
        for (i in 0 until rest.length) {
            if (!ok[i]) continue
            for (w in neutral) if (w.length >= MIN_COMPOUND && rest.startsWith(w, i)) ok[i + w.length] = true
        }
        return ok[rest.length]
    }

    private fun dice(a: String, b: String): Double {
        val ga = bigrams(a)
        val gb = bigrams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        val counts = HashMap<String, Int>()
        gb.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var common = 0
        for (g in ga) {
            val c = counts[g] ?: 0
            if (c > 0) {
                common++
                counts[g] = c - 1
            }
        }
        return 2.0 * common / (ga.size + gb.size)
    }

    private fun bigrams(s: String): List<String> = (0 until s.length - 1).map { s.substring(it, it + 2) }
}
