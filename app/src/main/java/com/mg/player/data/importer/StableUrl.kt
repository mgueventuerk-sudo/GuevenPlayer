package com.mg.player.data.importer

/**
 * Schlüssel für M3U-Einträge, der Anbieter-Tokens ignoriert: Manche Anbieter hängen an jede Adresse ein
 * Zugangstoken an, das sich bei jedem Abruf ändert (`?token=…`, `&expires=…`). Hinge die ID am vollständigen Link,
 * verlöre man nach jeder Aktualisierung Favoriten und Fortschritt.
 *
 * Adressen ohne solche Parameter bleiben Zeichen für Zeichen gleich – deren IDs ändern sich durch das Update nicht.
 */
internal object StableUrl {

    private val VOLATILE = setOf(
        "token", "auth", "authtoken", "auth_token", "access_token", "sign", "signature", "sig", "expires", "expiry",
        "exp", "st", "hash", "md5", "wmsauthsign", "hdnts", "hdntl", "nimblesessionid", "sessionid", "session_id",
        "timestamp", "validfrom", "validto",
    )

    fun key(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        val params = url.substring(queryStart + 1, fragmentStart).split('&')
        val kept = params.filter { param ->
            val name = param.substringBefore('=').lowercase()
            name !in VOLATILE
        }
        if (kept.size == params.size) return url
        val base = url.substring(0, queryStart)
        return if (kept.isEmpty() || kept.all { it.isEmpty() }) base else base + "?" + kept.joinToString("&")
    }
}
