package com.mg.player.domain.model

/** Sender in Kurzform (Suche, Zuordnung): ID, Name und Zugang. Auch Ergebnis-Typ von Datenbankabfragen. */
data class ChannelKeyRow(val id: String, val name: String, val playlistId: Long)

/** Gemerkter Favorit mit Anzeigenamen (für die eigene Reihenfolge). */
data class FavoriteName(val id: String, val name: String?)
