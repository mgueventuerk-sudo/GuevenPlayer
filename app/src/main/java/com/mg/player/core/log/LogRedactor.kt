package com.mg.player.core.log

/**
 * Letzte Sicherung vor dem Weitergeben des Protokolls (Handy, Teilen): Zugangsdaten, Schlüssel, Tokens und
 * E-Mail-Adressen werden geschwärzt – auch falls ein Eintrag sie doch einmal enthielte.
 */
object LogRedactor {

    private const val MASK = "***"

    private val rules = listOf(
        // URL-Parameter: ?username=…&password=…&token=…&secret=…&code=… – dieselbe Wortliste wie
        // StreamSecretStorage.secretName, damit beide Stellen dasselbe als Zugangsdaten erkennen.
        Regex(
            """(?i)\b(user(?:name)?|pass(?:word)?|pwd|token|api_?key|api_?token|key|auth|sig(?:nature)?|""" +
                """secret|code|session|jwt|hdnea|wmsauthsign)=([^&\s"']+)""",
        ) to "$1=$MASK",
        // Xtream-Pfade: /live/<user>/<pass>/123.ts, /movie/…, /series/…, /timeshift/…
        Regex("""(?i)/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/""") to "/$1/$MASK/$MASK/",
        // Zugangsdaten in Adressen: http://user:pass@host
        Regex("""(?i)(https?://)[^/\s:@]+:[^/\s@]+@""") to "$1$MASK@",
        // WireGuard-Schlüssel und Bearer-Tokens
        Regex("""(?i)(PrivateKey|PresharedKey)\s*=\s*\S+""") to "$1 = $MASK",
        Regex("""(?i)(Bearer|Token)\s+[A-Za-z0-9._\-+/=]{8,}""") to "$1 $MASK",
        // Kopfzeilen "Name: Wert" statt "name=wert" – Authorization (auch ohne Bearer/Token davor, z. B.
        // "Authorization: Basic …" oder ein roher Schlüssel) sowie Cookie/Set-Cookie.
        Regex("""(?i)\b(Authorization|Cookie|Set-Cookie)\s*:\s*\S[^\r\n]*""") to "$1: $MASK",
        // E-Mail-Adressen
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to MASK,
    )

    fun redact(text: String): String = rules.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
}
