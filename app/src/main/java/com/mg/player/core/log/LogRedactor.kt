package com.mg.player.core.log

/**
 * Letzte Sicherung vor dem Weitergeben des Protokolls (Handy, Teilen): Zugangsdaten, Schlüssel, Tokens und
 * E-Mail-Adressen werden geschwärzt – auch falls ein Eintrag sie doch einmal enthielte.
 */
object LogRedactor {

    private const val MASK = "***"

    /** Dieselbe Wortliste wie StreamSecretStorage.secretName, in jeder Schreibweise, die ein Protokolleintrag annehmen kann. */
    private const val KEYWORDS =
        """user(?:name)?|pass(?:word)?|pwd|token|api_?key|api_?token|key|auth|sig(?:nature)?|""" +
            """secret|code|session|jwt|hdnea|wmsauthsign"""

    private val rules = listOf(
        // URL-Parameter: ?username=…&password=…&token=…&secret=…&code=…
        Regex("""(?i)\b($KEYWORDS)=([^&\s"']+)""") to "$1=$MASK",
        // Xtream-Pfade mit Typ: /live/<user>/<pass>/123.ts, /movie/…, /series/…, /timeshift/…
        Regex("""(?i)/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/""") to "/$1/$MASK/$MASK/",
        // Xtream-Pfade ohne Typ-Segment: http://host/<user>/<pass>/123.ts – ältere player_api-Variante.
        // Nur direkt hinter dem Host, genau zwei Segmente, zuletzt eine numerische Stream-ID mit Endung –
        // sonst träfe das auch gewöhnliche REST-Pfade wie /v2/users.json.
        Regex("""(?i)(https?://[^/\s]+)/[^/\s]+/[^/\s]+/(\d+\.\w+)""") to "$1/$MASK/$MASK/$2",
        // Zugangsdaten in Adressen: http://user:pass@host
        Regex("""(?i)(https?://)[^/\s:@]+:[^/\s@]+@""") to "$1$MASK@",
        // WireGuard-Schlüssel und Bearer-Tokens
        Regex("""(?i)(PrivateKey|PresharedKey)\s*=\s*\S+""") to "$1 = $MASK",
        Regex("""(?i)(Bearer|Token)\s+[A-Za-z0-9._\-+/=]{8,}""") to "$1 $MASK",
        // Kopfzeilen "Name: Wert" statt "name=wert" – Authorization (auch ohne Bearer/Token davor, z. B.
        // "Authorization: Basic …" oder ein roher Schlüssel) sowie Cookie/Set-Cookie.
        Regex("""(?i)\b(Authorization|Cookie|Set-Cookie)\s*:\s*\S[^\r\n]*""") to "$1: $MASK",
        // Freitext "passwort: wert" statt "passwort=wert" – dieselbe Wortliste wie oben, aber mit Doppelpunkt.
        // Das Schlüsselwort muss unmittelbar vor dem Doppelpunkt stehen (nur Leerraum dazwischen), sonst träfe
        // das auch Fließtext, der das Wort beiläufig verwendet.
        Regex("""(?i)\b($KEYWORDS)\s*:\s*\S[^\r\n]*""") to "$1: $MASK",
        // JSON-Form "password":"geheim" bzw. "password": "geheim" – dieselbe Wortliste, in Anführungszeichen.
        // Normaler String statt Raw-String: Ein Raw-String kann nicht auf ein Anführungszeichen enden, das
        // unmittelbar vor dem schließenden """ steht (mehrdeutig mit dem Terminator selbst).
        Regex("(?i)\"($KEYWORDS)\"\\s*:\\s*\"([^\"]*)\"") to "\"$1\":\"$MASK\"",
        // E-Mail-Adressen
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to MASK,
    )

    fun redact(text: String): String = rules.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
}
