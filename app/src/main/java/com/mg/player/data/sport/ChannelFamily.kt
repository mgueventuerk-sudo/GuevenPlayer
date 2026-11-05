package com.mg.player.data.sport

/**
 * Senderfamilien für den von Hand gewählten Ersatzsender einer Liga (ab 3.46): Gleichzeitige Spiele laufen nie alle auf
 * demselben Sender, sondern auf „beIN SPORTS 1“, „… 2“, „… 3“ usw. Ist für ein Spiel nichts Sichereres bekannt
 * (Online-Sendeliste, Programm, EPG), bekommt es den ersten Sender der Familie, der zu seiner Anstoßzeit frei ist.
 * Sender, die schon sicher für ein anderes Spiel feststehen, gelten in dessen Zeit als belegt.
 */
internal object ChannelFamily {

    /** So lange gilt ein Sender als belegt (Spiel inkl. Vor- und Nachbericht). */
    const val SLOT_MS = 2L * 60 * 60 * 1000 + 15 * 60 * 1000

    /** Höchste Nummer, nach der gesucht wird (beIN SPORTS 1–8, Sky Sport 1–10 …). */
    const val MAX_NUMBER = 10

    // Die Sendernummer: letzte allein stehende Zahl mit 1–2 Stellen („|TR| BEIN SPORTS 1 FHD“ → 1, nicht „4K“ oder „1080“).
    private val number = Regex("(?<![\\p{L}\\p{N}])(\\d{1,2})(?![\\p{L}\\p{N}])")

    /** Nummer des Senders in seiner Familie, null = keine Nummer im Namen (dann gibt es keine Geschwister). */
    fun numberOf(name: String): Int? = number.findAll(name).lastOrNull()?.value?.toIntOrNull()

    /** Namen der Geschwister (Nummer → Name), z. B. „|TR| BEIN SPORTS 2 FHD“ … bis [MAX_NUMBER]; leer ohne Nummer. */
    fun siblingNames(name: String): List<Pair<Int, String>> {
        val match = number.findAll(name).lastOrNull() ?: return emptyList()
        return (1..MAX_NUMBER).map { n -> n to name.replaceRange(match.range, n.toString()) }
    }

    /**
     * Verteilt die offenen Spiele ([open]: ID → Anstoß) auf die Familie ([family], in Nummernfolge). [busy]: Anstoß →
     * Sender, der schon sicher für ein anderes Spiel feststeht. Je Spiel der erste Sender, der zur Anstoßzeit frei ist;
     * sind alle belegt, bekommt das Spiel keinen (lieber keiner als ein falscher).
     */
    fun <C> allocate(open: List<Pair<String, Long>>, family: List<C>, busy: List<Pair<Long, C>> = emptyList()): Map<String, C> {
        if (family.isEmpty()) return emptyMap()
        val taken = busy.toMutableList()
        val result = LinkedHashMap<String, C>()
        for ((id, start) in open.sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })) {
            val free = family.firstOrNull { channel -> taken.none { (s, c) -> c == channel && overlaps(s, start) } } ?: continue
            result[id] = free
            taken += start to free
        }
        return result
    }

    private fun overlaps(a: Long, b: Long): Boolean = a < b + SLOT_MS && b < a + SLOT_MS
}
