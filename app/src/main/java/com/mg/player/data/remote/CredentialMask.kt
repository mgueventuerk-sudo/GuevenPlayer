package com.mg.player.data.remote

/**
 * M3U-Listen im Xtream-Format enthalten Benutzername und Passwort in jeder Stream-Adresse
 * (`…/live/BENUTZER/PASSWORT/123.ts`, `…?username=…&password=…`). In der Datenbank stehen stattdessen
 * Platzhalter (`/{u}/{p}/`, `username={u}`, `password={p}`); eingesetzt wird erst beim Abspielen.
 * Gleiches Format wie Version 3.1 – dort gespeicherte Adressen funktionieren weiter.
 */
object CredentialMask {
    private const val USER = "{u}"
    private const val PASS = "{p}"

    fun mask(url: String, username: String, password: String): String {
        if (username.isBlank() || password.isBlank()) return url
        return url
            .replace("/$username/$password/", "/$USER/$PASS/")
            .replace("username=$username&", "username=$USER&")
            .replace(Regex("username=${Regex.escape(username)}$"), "username=$USER")
            .replace("password=$password&", "password=$PASS&")
            .replace(Regex("password=${Regex.escape(password)}$"), "password=$PASS")
            .replace(Regex("password=${Regex.escape(password)}(?=\\|)"), "password=$PASS")
            .replace(Regex("username=${Regex.escape(username)}(?=\\|)"), "username=$USER")
    }

    fun isMasked(url: String): Boolean = USER in url || PASS in url

    fun reveal(url: String, username: String, password: String): String = url.replace(USER, username).replace(PASS, password)
}
