package com.mg.player.data.sport

import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.model.SportLeague

/** Eine Online-Quelle für Sport-Events (immer online abgefragt; nichts davon ist fest in der App hinterlegt). */
interface SportSource {
    val name: String

    /** Ligen, denen man über diese Quelle folgen kann. */
    suspend fun leagues(): List<SportLeague>

    /** Gehört diese Liga (Schlüssel wie „espn:soccer/ger.1“) zu dieser Quelle? */
    fun handles(leagueKey: String): Boolean

    /** Events einer Liga zwischen [fromMs] und [toMs]. IDs beginnen mit „<Liga>#“ (siehe [leagueOfEvent]). */
    suspend fun fetch(leagueKey: String, fromMs: Long, toMs: Long): List<SportEvent>
}

/** Liga eines Events aus seiner ID („espn:soccer/ger.1#401234“ → „espn:soccer/ger.1“). */
fun leagueOfEvent(eventId: String): String = eventId.substringBefore('#')

/** Adressen der Online-Quellen (Tests leiten sie auf einen lokalen Server um). */
object SportEndpoints {
    @Volatile var espn = "https://site.api.espn.com/apis/site/v2/sports"

    @Volatile var sportmonks = "https://api.sportmonks.com/v3/football"

    @Volatile var bzzoiro = "https://sports.bzzoiro.com/api/v2"
}
