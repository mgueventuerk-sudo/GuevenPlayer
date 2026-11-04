package com.mg.player.data.sport.guide

import com.mg.player.core.log.AppLog
import com.mg.player.data.parser.XmltvParser
import java.io.File
import java.io.InputStream
import java.lang.ref.SoftReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lädt das öffentliche Sport-Programm einmal am Tag (je Land eine Datei), filtert es schon beim Lesen auf Sport und
 * legt das Ergebnis klein im Cache ab. Die großen Dateien werden gestreamt, nie ganz in den Speicher geladen.
 * Schlägt der Abruf fehl, gilt der letzte Stand weiter (bis 3 Tage).
 */
class SportGuideStore(private val dir: File, private val open: (String) -> InputStream, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var cached: SoftReference<Pair<String, SportGuide>>? = null

    /** Programm für diese Länder (aus dem Cache, bei Bedarf neu geladen). */
    suspend fun guide(countries: List<String>): SportGuide = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = countries.flatMap { c -> GuideCatalog.filesFor(c).map { it to GuideCatalog.canonicalCountry(c) } }.distinct()
            if (files.isEmpty()) return@withLock SportGuide.EMPTY
            files.forEach { (file, country) -> if (isStale(file)) download(file, country) }
            val signature = files.joinToString { (file, _) -> "$file:${cacheFile(file).lastModified()}" }
            cached?.get()?.takeIf { it.first == signature }?.let { return@withLock it.second }
            val now = clock()
            val parts = files.mapNotNull { (file, _) -> cacheFile(file).takeIf { it.exists() && now - it.lastModified() < MAX_AGE_MS }?.let(::readCache) }
            SportGuide.merge(parts.ifEmpty { listOf(SportGuide.EMPTY) }).also { cached = SoftReference(signature to it) }
        }
    }

    /** Cache leeren (Schalter „Sport-Programm aus dem Internet“ aus). */
    fun clear() {
        cached = null
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun cacheFile(file: String) = File(dir, "$file.tsv.gz")

    private fun isStale(file: String): Boolean {
        val f = cacheFile(file)
        return !f.exists() || clock() - f.lastModified() > REFRESH_MS
    }

    private suspend fun download(file: String, country: String) {
        val now = clock()
        val from = now - 12 * HOUR
        val to = now + 8 * DAY
        try {
            val guide = open(GuideCatalog.urlFor(file)).use { parse(it, country, from, to) }
            dir.mkdirs()
            val tmp = File(dir, "$file.tmp")
            writeCache(tmp, guide)
            if (!tmp.renameTo(cacheFile(file))) tmp.delete()
            AppLog.log("Sport", "Sport-Programm $file: ${guide.channels.size} Sender, ${guide.programs.size} Sendungen")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Alter Stand bleibt; nächster Versuch frühestens in einer Stunde (Datei als „frisch genug“ markieren).
            AppLog.log("Sport", "Sport-Programm $file nicht geladen: ${e.javaClass.simpleName}")
            cacheFile(file).takeIf { it.exists() }?.setLastModified(now - REFRESH_MS + HOUR)
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        const val REFRESH_MS = 20 * HOUR
        const val MAX_AGE_MS = 3 * DAY
        private const val MAX_TEXT = 240

        /** XMLTV lesen und dabei filtern: Sportsender ganz, andere Sender nur Sportsendungen; nur [fromMs]…[toMs]. */
        suspend fun parse(input: InputStream, country: String, fromMs: Long, toMs: Long): SportGuide {
            val channels = HashMap<String, GuideChannel>()
            val sportIds = HashSet<String>()
            val names = HashMap<String, String>()
            val programs = ArrayList<GuideProgram>()
            XmltvParser.parse(
                input,
                object : XmltvParser.Callback {
                    override suspend fun onChannel(id: String, displayNames: List<String>) {
                        names[id] = displayNames.firstOrNull { it.isNotBlank() } ?: id
                        if (GuideCatalog.isSportChannel(id, displayNames)) sportIds += id
                    }

                    override suspend fun onProgramme(channelId: String, startMs: Long, endMs: Long, title: String, description: String?) {
                        if (endMs < fromMs || startMs > toMs) return
                        if (channelId !in sportIds && !GuideCatalog.isSportProgram(title)) return
                        channels.getOrPut(channelId) {
                            GuideChannel(channelId, names[channelId] ?: channelId, GuideCatalog.countryOfId(channelId) ?: country)
                        }
                        val text = clean(listOfNotNull(title, description?.take(MAX_TEXT)).joinToString(" "))
                        programs += GuideProgram(channelId, startMs, endMs, clean(title), text)
                    }
                },
            )
            return SportGuide(channels, programs)
        }

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        internal fun writeCache(file: File, guide: SportGuide) {
            GZIPOutputStream(file.outputStream().buffered()).bufferedWriter().use { w ->
                guide.channels.values.forEach { c -> w.write("C\t${c.id}\t${clean(c.name)}\t${c.country}\n") }
                guide.programs.forEach { p -> w.write("P\t${p.channelId}\t${p.startMs}\t${p.endMs}\t${p.title}\t${p.text}\n") }
            }
        }

        internal fun readCache(file: File): SportGuide? = runCatching {
            val channels = HashMap<String, GuideChannel>()
            val programs = ArrayList<GuideProgram>()
            GZIPInputStream(file.inputStream().buffered()).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val f = line.split('\t')
                    when {
                        f.size == 4 && f[0] == "C" -> channels[f[1]] = GuideChannel(f[1], f[2], f[3])

                        f.size == 6 && f[0] == "P" -> {
                            val start = f[2].toLongOrNull() ?: return@forEach
                            val end = f[3].toLongOrNull() ?: return@forEach
                            programs += GuideProgram(f[1], start, end, f[4], f[5])
                        }
                    }
                }
            }
            SportGuide(channels, programs)
        }.getOrNull()
    }
}
