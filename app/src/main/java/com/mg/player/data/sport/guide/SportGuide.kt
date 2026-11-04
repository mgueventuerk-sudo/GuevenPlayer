package com.mg.player.data.sport.guide

/** Ein Sender aus dem öffentlichen Programm, z. B. id „Sky.Sport.Premier.League.de“, Name „Sky Sport Premier League“. */
data class GuideChannel(val id: String, val name: String, val country: String)

/**
 * Eine Sendung auf einem Sender des öffentlichen Programms. [title] enthält bei Sport auch den Untertitel
 * („Fußball: Premier League – FC Liverpool – Brighton“), [text] zusätzlich den Anfang der Beschreibung (für die Suche).
 */
data class GuideProgram(val channelId: String, val startMs: Long, val endMs: Long, val title: String, val text: String)

/** Geladenes Sport-Programm aller nötigen Länder, nach Beginn sortiert (schnelle Suche per Binärsuche). */
class SportGuide(val channels: Map<String, GuideChannel>, programs: List<GuideProgram>) {

    val programs: List<GuideProgram> = programs.sortedBy { it.startMs }
    private val starts = LongArray(this.programs.size) { this.programs[it].startMs }

    val isEmpty: Boolean get() = programs.isEmpty()

    /** Sendungen, die zwischen [fromMs] und [toMs] beginnen. */
    fun startingBetween(fromMs: Long, toMs: Long): List<GuideProgram> {
        if (fromMs > toMs || starts.isEmpty()) return emptyList()
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < fromMs) lo = mid + 1 else hi = mid
        }
        val result = ArrayList<GuideProgram>()
        var i = lo
        while (i < starts.size && starts[i] <= toMs) result += programs[i++]
        return result
    }

    companion object {
        val EMPTY = SportGuide(emptyMap(), emptyList())

        /** Mehrere Länder zu einem Programm zusammenfassen. */
        fun merge(parts: List<SportGuide>): SportGuide = if (parts.size == 1) {
            parts[0]
        } else {
            SportGuide(parts.flatMap { it.channels.entries }.associate { it.key to it.value }, parts.flatMap { it.programs })
        }
    }
}
