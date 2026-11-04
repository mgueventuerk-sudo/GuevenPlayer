package com.mg.player.data.parser

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

fun jsonReader(input: InputStream): JsonReader = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = true }

/** Liest Strings, Zahlen und Booleans als String – Xtream-Panels sind da sehr uneinheitlich. */
fun JsonReader.nextStringOrNull(): String? = when (peek()) {
    JsonToken.NULL -> {
        nextNull()
        null
    }

    JsonToken.STRING, JsonToken.NUMBER -> nextString()

    JsonToken.BOOLEAN -> nextBoolean().toString()

    JsonToken.BEGIN_ARRAY -> nextFirstArrayString()

    else -> {
        skipValue()
        null
    }
}

/** Für Felder wie backdrop_path, die mal ein String und mal ein Array sind. */
private fun JsonReader.nextFirstArrayString(): String? {
    var first: String? = null
    beginArray()
    while (hasNext()) {
        val token = peek()
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) {
            val value = nextString()
            if (first == null && value.isNotBlank()) first = value
        } else {
            skipValue()
        }
    }
    endArray()
    return first
}

private fun JsonReader.nextCappedString(): String? = nextStringOrNull()?.let { if (it.length > MAX_FIELD_CHARS) it.substring(0, MAX_FIELD_CHARS) else it }

/** Höchstens so viele Felder je Eintrag – echte Xtream-Einträge haben rund 20–40. */
internal const val MAX_FIELDS = 128

/** Längere Werte werden gekürzt: Namen, Adressen und Beschreibungen sind nie so lang, kaputte Panels schon. */
internal const val MAX_FIELD_CHARS = 8 * 1024

/**
 * Liest ein JSON-Objekt flach in eine Map. Verschachtelte Objekte werden übersprungen,
 * Arrays liefern ihren ersten String-Wert. Antworten des Anbieters sind fremd: Feldzahl und Feldlänge sind
 * begrenzt, sonst bläht ein defektes Panel (z. B. 1 MB „plot“ je Film) Speicher und Datenbank auf.
 */
fun JsonReader.readFlatObject(): Map<String, String?> {
    val map = HashMap<String, String?>(24)
    beginObject()
    while (hasNext()) {
        val name = nextName()
        if (peek() == JsonToken.BEGIN_OBJECT || map.size >= MAX_FIELDS) skipValue() else map[name] = nextCappedString()
    }
    endObject()
    return map
}

/**
 * Ruft [onItem] für jedes Objekt einer Liste auf. Manche Panels liefern statt eines
 * Arrays ein Objekt mit Index-Schlüsseln – beides wird unterstützt. Speicherbedarf bleibt konstant.
 */
suspend fun JsonReader.forEachObject(onItem: suspend (Map<String, String?>) -> Unit) {
    when (peek()) {
        JsonToken.BEGIN_ARRAY -> {
            beginArray()
            while (hasNext()) {
                if (peek() == JsonToken.BEGIN_OBJECT) onItem(readFlatObject()) else skipValue()
            }
            endArray()
        }

        JsonToken.BEGIN_OBJECT -> {
            beginObject()
            while (hasNext()) {
                nextName()
                if (peek() == JsonToken.BEGIN_OBJECT) onItem(readFlatObject()) else skipValue()
            }
            endObject()
        }

        else -> skipValue()
    }
}

fun Map<String, String?>.str(key: String): String? = this[key]?.takeIf { it.isNotBlank() && it != "null" }

fun Map<String, String?>.float(key: String): Float? = str(key)?.replace(',', '.')?.toFloatOrNull()

fun Map<String, String?>.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
