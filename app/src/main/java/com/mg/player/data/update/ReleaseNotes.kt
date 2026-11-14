package com.mg.player.data.update

/** Text des Update-Hinweises aus den Release-Notizen (rein, ohne Android – testbar). */
internal object ReleaseNotes {
    /**
     * Lesbare Notizen für den Update-Hinweis: Überschriften („## Behoben“ → „Behoben:“) und Stichpunkte – aus
     * `release-notes.md` (Alltagssprache) bzw. Pull-Request-Zeilen („… by @user in …/pull/1“ → nur der Titel).
     * Bis 3.60 zählten nur Pull-Request-Zeilen; ohne Pull-Requests stand im Hinweis nichts über die Änderungen.
     */
    fun clean(raw: String): String? {
        val out = ArrayList<String>()
        var skipping = false
        for (line in raw.lines().map { it.trim() }) {
            val heading = HEADING.matchEntire(line)?.groupValues?.get(1)?.trim()
            if (heading != null) {
                // GitHub-eigene Abschnitte: „What's Changed“ ohne Überschrift, „New Contributors“ gar nicht.
                skipping = heading == NEW_CONTRIBUTORS
                if (heading != WHATS_CHANGED && !skipping) out += "$heading:"
                continue
            }
            if (skipping) continue
            bullet(line)?.let { out += "• $it" }
        }
        // Überschrift nur mit Punkten darunter – auch nicht als letzte Zeile, wenn die Grenze sie abschneidet.
        val lines = out.filterIndexed { i, l -> l.startsWith("•") || out.getOrNull(i + 1)?.startsWith("•") == true }
            .take(MAX_NOTES_LINES)
            .dropLastWhile { !it.startsWith("•") }
        return lines.takeIf { list -> list.any { it.startsWith("•") } }?.joinToString("\n")
    }

    private fun bullet(line: String): String? =
        (PR_LINE.find(line) ?: BULLET.matchEntire(line))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /**
     * "* Titel by @user in https://…/pull/123" bzw. "- Titel in #123" → nur "Titel". Nur mit Link bzw. #Nummer am Ende –
     * sonst schnitte es deutsche Sätze ab („… läuft wieder in Ordnung“).
     */
    private val PR_LINE = Regex("""^[*-]\s+(.*?)(?:\s+by\s+@\S+)?\s+in\s+(?:https?://\S+|#\d+)$""")
    private val BULLET = Regex("""^[*-]\s+(.+)$""")
    private val HEADING = Regex("""^#{1,6}\s+(.+)$""")
    private const val WHATS_CHANGED = "What's Changed"
    private const val NEW_CONTRIBUTORS = "New Contributors"
    private const val MAX_NOTES_LINES = 10
}
