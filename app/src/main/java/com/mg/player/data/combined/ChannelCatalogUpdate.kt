package com.mg.player.data.combined

import android.content.SharedPreferences
import android.util.JsonReader
import android.util.JsonToken
import androidx.core.content.edit
import com.mg.player.core.log.AppLog
import com.mg.player.core.network.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.Writer
import java.util.zip.GZIPOutputStream

/**
 * Abgleich mit dem Internet: holt den aktuellen Senderkatalog von iptv-org (öffentliche Liste, täglich gepflegt,
 * ~1 MB komprimiert) und legt ihn im App-Speicher ab. Gesendet wird nichts außer der Anfrage selbst – keine Sendernamen,
 * keine Zugangsdaten.
 *
 * Sicher gegen kaputte Downloads: Die Datei wird beim Lesen umgewandelt, geprüft (Mindestzahl Sender, bekannte Sender
 * vorhanden) und erst dann gegen die alte getauscht. Schlägt etwas fehl, bleibt der bisherige Katalog.
 */
class ChannelCatalogUpdate(
    private val dir: File,
    private val http: HttpFetcher,
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val url: String = URL,
    private val check: Check = Check(),
    /** Nur Tests (lokaler Server ohne TLS); die echte Adresse ist HTTPS. */
    private val allowHttp: Boolean = false,
) {
    /** Plausibilität des Downloads (Tests verkleinern sie). */
    class Check(val minChannels: Int = MIN_CHANNELS, val mustContain: Set<String> = MUST_CONTAIN)

    private val mutex = Mutex()

    /** Ergebnis eines Abgleichs. */
    sealed interface Outcome {
        data class Updated(val channels: Int) : Outcome

        data object NotDue : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /** Wann zuletzt erfolgreich abgeglichen (0 = nie, dann gilt der mitgelieferte Katalog). */
    val lastUpdateMs: Long get() = prefs.getLong(KEY_AT, 0L)

    fun isDue(): Boolean = clock() - lastUpdateMs >= INTERVAL_MS

    /** [force] = auf Wunsch („Neu zuordnen“), sonst höchstens einmal pro Woche. */
    suspend fun refresh(force: Boolean = false): Outcome = mutex.withLock {
        if (!force && !isDue()) return@withLock Outcome.NotDue
        // Kurz nach einem Versuch nicht gleich wieder (auch bei Fehlern), außer von Hand.
        if (!force && clock() - prefs.getLong(KEY_TRIED, 0L) < RETRY_MS) return@withLock Outcome.NotDue
        prefs.edit { putLong(KEY_TRIED, clock()) }
        withContext(Dispatchers.IO) { download() }
    }

    private fun download(): Outcome {
        val target = File(dir, FILE)
        val temp = File(dir, "$FILE.part")
        return try {
            val count = http.openStream(url, allowHttp).use { input ->
                GZIPOutputStream(temp.outputStream()).bufferedWriter(Charsets.UTF_8).use { out -> convert(input.reader(Charsets.UTF_8), out, check) }
            }
            if (!temp.renameTo(target)) throw IOException("Datei nicht ersetzbar")
            prefs.edit {
                putLong(KEY_AT, clock())
                putInt(KEY_COUNT, count)
            }
            AppLog.log(TAG, "Senderkatalog aus dem Internet aktualisiert: $count Sender (iptv-org)")
            Outcome.Updated(count)
        } catch (e: IOException) {
            temp.delete()
            AppLog.log(TAG, "Senderkatalog nicht aktualisiert (${e.javaClass.simpleName}) – der bisherige bleibt")
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalStateException) {
            // JsonReader meldet unerwarteten Aufbau so.
            temp.delete()
            AppLog.log(TAG, "Senderkatalog nicht aktualisiert (Aufbau unerwartet: ${e.message}) – der bisherige bleibt")
            Outcome.Failed("Aufbau unerwartet: ${e.message}")
        }
    }

    companion object {
        const val FILE = "channel-catalog.dat"
        const val URL = "https://iptv-org.github.io/api/channels.json"
        private const val TAG = "Zuordnung"
        private const val KEY_AT = "catalog_online_at"
        private const val KEY_TRIED = "catalog_online_tried"
        private const val KEY_COUNT = "catalog_online_count"
        private const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        private const val RETRY_MS = 6L * 60 * 60 * 1000

        /** Weniger Sender = Download abgeschnitten oder Liste kaputt (heute ~30.000). */
        internal const val MIN_CHANNELS = 20_000
        internal val MUST_CONTAIN = setOf("DasErste.de", "BBCOne.uk", "TRT1.tr")

        /**
         * `channels.json` (Feld für Feld gelesen, nie als Ganzes im Speicher) → Zeilen wie im mitgelieferten Katalog.
         * Geschlossene Sender und Sender ohne Land fallen weg. Wirft [IOException], wenn das Ergebnis nicht plausibel ist.
         */
        fun convert(input: Reader, out: Writer, check: Check = Check()): Int {
            out.write("# iptv-org/api channels.json – gemeinfrei (Unlicense). Spalten: id, land, namen (;)\n")
            var count = 0
            val seen = HashSet<String>()
            JsonReader(input).use { json ->
                json.beginArray()
                while (json.hasNext()) {
                    val channel = readChannel(json) ?: continue
                    out.write(channel.line())
                    out.write("\n")
                    count++
                    if (channel.id in check.mustContain) seen += channel.id
                }
                json.endArray()
            }
            if (count < check.minChannels) throw IOException("Nur $count Sender – unvollständig")
            if (seen.size < check.mustContain.size) throw IOException("Bekannte Sender fehlen")
            return count
        }

        private class Channel(val id: String, val country: String, val names: List<String>) {
            fun line(): String {
                val clean = names.map { it.replace('\t', ' ').replace(';', ',').trim() }.filter(String::isNotEmpty).distinct()
                return "$id\t$country\t${clean.joinToString(";")}"
            }
        }

        private fun readChannel(json: JsonReader): Channel? {
            var id: String? = null
            var name: String? = null
            var country: String? = null
            var closed = false
            val alt = ArrayList<String>()
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "id" -> id = json.nextStringOrNull()
                    "name" -> name = json.nextStringOrNull()
                    "country" -> country = json.nextStringOrNull()
                    "closed" -> closed = json.nextStringOrNull() != null
                    "alt_names" -> json.readStrings(alt)
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (closed) return null
            val fields = listOf(id, name, country)
            if (fields.any { it.isNullOrBlank() }) return null
            // Die Prüfung oben lief über die abgeleitete Liste "fields", nicht über id/name/country selbst -
            // Kotlin kann daraus nicht auf deren Nicht-Null-Status schließen (kein Smart-Cast über die Kopie).
            return Channel(id!!.trim(), country!!.trim().uppercase(), listOf(name!!) + alt)
        }

        private fun JsonReader.nextStringOrNull(): String? = if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

        private fun JsonReader.readStrings(into: MutableList<String>) {
            if (peek() != JsonToken.BEGIN_ARRAY) {
                skipValue()
                return
            }
            beginArray()
            while (hasNext()) nextStringOrNull()?.let(into::add)
            endArray()
        }
    }
}
