package com.mg.player.data.parser

import android.util.Base64
import android.util.JsonToken
import android.util.Xml
import com.mg.player.core.util.TimeUtils
import java.io.InputStream
import kotlinx.coroutines.yield
import org.xmlpull.v1.XmlPullParser

/** Streaming-XMLTV-Parser auf Basis von XmlPullParser (funktioniert auch für .xml.gz). */
object XmltvParser {

    private const val INITIAL_TEXT_CAPACITY = 256

    /** So viele XML-Ereignisse zwischen zwei Abbruchpruefungen - gross genug, um nicht ins Gewicht zu fallen. */
    private const val YIELD_EVERY = 4_096

    interface Callback {
        suspend fun onChannel(id: String, displayNames: List<String>)
        suspend fun onProgramme(channelId: String, startMs: Long, endMs: Long, title: String, description: String?)
    }

    suspend fun parse(input: InputStream, callback: Callback) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var event = parser.eventType
        var events = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            // Abbrechbar bleiben: Sendungen, die der Aufrufer verwirft, enthalten keinen Aufhaengepunkt. Ein grosser
            // Anbieter liefert Millionen Zeilen, von denen nur ein Bruchteil uebernommen wird (siehe EpgImporter) -
            // ohne dieses yield liefe das Parsen nach einem Abbruch die ganze Datei zu Ende weiter.
            if (++events % YIELD_EVERY == 0) yield()
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser, callback)
                    "programme" -> readProgramme(parser, callback)
                }
            }
            event = parser.next()
        }
    }

    private suspend fun readChannel(parser: XmlPullParser, callback: Callback) {
        val id = parser.getAttributeValue(null, "id")?.take(MAX_CHANNEL_ID) ?: return
        val names = ArrayList<String>(2)
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.name == "channel") break
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                if (names.size < MAX_DISPLAY_NAMES) names += readText(parser, MAX_DISPLAY_NAME).trim() else skipElement(parser)
            }
        }
        callback.onChannel(id, names)
    }

    private suspend fun readProgramme(parser: XmlPullParser, callback: Callback) {
        val channel = parser.getAttributeValue(null, "channel")?.take(MAX_CHANNEL_ID)
        val start = TimeUtils.parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = TimeUtils.parseXmltvTime(parser.getAttributeValue(null, "stop"))
        var title: String? = null
        var desc: String? = null
        var subTitle: String? = null
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.name == "programme") break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title == null) title = readText(parser, MAX_TITLE).trim() else skipElement(parser)
                    "desc" -> if (desc == null) desc = readText(parser, MAX_DESCRIPTION).trim() else skipElement(parser)
                    "sub-title" -> if (subTitle == null) subTitle = readText(parser, MAX_SUBTITLE).trim() else skipElement(parser)
                }
            }
        }
        if (channel != null && start != null && stop != null && !title.isNullOrBlank()) {
            callback.onProgramme(channel, start, stop, withSportSubtitle(title, subTitle), desc)
        }
    }

    /**
     * Sport-Sendungen: „Fußball: Bundesliga“ + Untertitel „FC Bayern München – Borussia Dortmund“ zusammen als Titel –
     * viele Quellen nennen die Paarung nur im Untertitel, und die Sport-Sektion sucht im Titel. Andere Sendungen bleiben
     * unverändert (Folgentitel gehören nicht in den Sendungstitel).
     */
    internal fun withSportSubtitle(title: String, subTitle: String?): String {
        if (subTitle.isNullOrBlank() || title.contains(subTitle, ignoreCase = true)) return title
        val lower = title.lowercase()
        return if (SPORT_WORDS.any { it in lower }) "$title – $subTitle" else title
    }

    /**
     * Wie nextText(), verträgt aber verschachtelte Tags (z. B. `<title>Tatort <i>Live</i></title>`) –
     * nextText() wirft dort eine Exception und die ganze EPG-Quelle wäre verloren.
     */
    private fun readText(parser: XmlPullParser, maxChars: Int): String {
        val builder = StringBuilder(minOf(maxChars, INITIAL_TEXT_CAPACITY))
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val text = parser.text
                    val remaining = maxChars - builder.length
                    if (remaining > 0) builder.append(text, 0, minOf(text.length, remaining))
                }
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return builder.toString()
            }
        }
        return builder.toString()
    }

    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Begrenzungen für Anbietertexte: beschränken sowohl den gespeicherten Wert als auch den eigenen Akkumulator. */
    private const val MAX_CHANNEL_ID = 256
    private const val MAX_DISPLAY_NAMES = 8
    private const val MAX_DISPLAY_NAME = 256
    private const val MAX_TITLE = 600
    private const val MAX_DESCRIPTION = 600
    private const val MAX_SUBTITLE = 120
    private val SPORT_WORDS = listOf(
        "fußball", "fussball", "football", "soccer", "bundesliga", "liga", "league", "pokal", "champions",
        "sport", "formel", "formula", "ufc", "nba", "nfl", "nhl", "tennis", "handball", "basketball", "boxen", "boxing",
    )

    data class ShortEpgItem(val title: String, val description: String?, val startMs: Long, val endMs: Long)

    /** Xtream get_short_epg: Titel und Beschreibung sind Base64-kodiert. */
    fun parseShortEpg(input: InputStream): List<ShortEpgItem> {
        val result = ArrayList<ShortEpgItem>()
        jsonReader(input).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return result
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "epg_listings" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        val item = reader.readFlatObject()
                        val start = item.str("start_timestamp")?.toLongOrNull()
                        val stop = item.str("stop_timestamp")?.toLongOrNull()
                        val title = decodeBase64(item.str("title"))
                        if (start != null && stop != null && !title.isNullOrBlank()) {
                            result += ShortEpgItem(title, decodeBase64(item.str("description")), start * 1000, stop * 1000)
                        }
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
        return result
    }

    private fun decodeBase64(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            value
        }
    }
}
