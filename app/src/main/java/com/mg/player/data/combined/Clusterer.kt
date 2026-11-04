package com.mg.player.data.combined

import com.mg.player.data.db.dao.ChannelSourceRow
import com.mg.player.data.db.dao.VodSourceRow

/** Ein zusammengeführter Eintrag: stabiler Schlüssel (daraus die ID) und seine Originale. */
data class Cluster<T>(val key: String, val members: List<T>)

/** Eigene Korrekturen: Paare von Vergleichsschlüsseln, die zusammengehören bzw. ausdrücklich nicht. */
class MatchRules(merge: Collection<Pair<String, String>> = emptyList(), split: Collection<Pair<String, String>> = emptyList()) {
    private val merges = merge.map { sorted(it) }
    private val splits = split.map { sorted(it) }.toHashSet()

    fun merges(): List<Pair<String, String>> = merges

    fun forbids(a: Collection<String>, b: Collection<String>): Boolean = splits.isNotEmpty() && a.any { x -> b.any { y -> sorted(x to y) in splits } }

    private fun sorted(p: Pair<String, String>) = if (p.first <= p.second) p else p.second to p.first
}

/** Was die automatische Zuordnung getan hat (für Protokoll und Einstellungen). */
class MatchStats {
    /** Verbindungen, die nicht zustande kamen, weil etwas dagegen sprach (anderer Sender laut Katalog, anderes Land, anderes Programm). */
    var refused: Int = 0

    /** Quellen, die der Senderkatalog eindeutig einem echten Sender zuordnen konnte. */
    var identified: Int = 0

    /** Sender im verwendeten Katalog (0 = keiner geladen). */
    var catalogSize: Int = 0

    /** Alle Quellen (Sender aller Zugänge) – Bezugsgröße für [identified]. */
    var sources: Int = 0

    /** Einträge mit Quellen aus mindestens zwei Zugängen … */
    var merged: Int = 0

    /** … davon durch gleiches laufendes Programm bestätigt (beide Seiten mit EPG). */
    var confirmed: Int = 0
}

/**
 * Union-Find mit Schutz: Zwei Gruppen werden nur verbunden, wenn nichts dagegen spricht –
 * verschiedene Katalog-Sender bzw. TMDB-IDs ([identity]), verschiedene Länder ([country]) oder Programme ohne einen
 * einzigen gemeinsamen Sendungstitel ([titles], sortierte Hashwerte). So kann auch eine Kette A–B–C nie zwei
 * widersprüchliche Sender zusammenziehen: Die Merkmale gelten für die ganze Gruppe.
 */
internal class GuardedUnion(
    n: Int,
    identity: List<String?>,
    country: List<String?>,
    titles: List<IntArray?>,
    names: List<String?> = emptyList(),
    /** Katalog-ID kommt aus dem Namen (sicher) statt nur aus der EPG-ID des Anbieters (oft falsch kopiert). */
    strongIdentity: List<Boolean> = emptyList(),
) {
    private val parent = IntArray(n) { it }
    private val rootIdentity = identity.toTypedArray()
    private val rootStrong = BooleanArray(n) { strongIdentity.getOrElse(it) { true } }
    private val rootNames: Array<Set<String>?> = Array(n) { i -> names.getOrNull(i)?.let(::setOf) }
    private val rootCountry = country.map { it?.takeIf(String::isNotEmpty) }.toTypedArray()
    private val rootTitles = titles.map { it?.takeIf(IntArray::isNotEmpty) }.toTypedArray()
    var refused = 0
        private set

    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }

    /** [force]: eigene „Zusammenlegen“-Regel – gilt auch gegen die Schutzregeln. */
    fun union(a: Int, b: Int, force: Boolean = false) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        if (!force && conflict(ra, rb)) {
            refused++
            return
        }
        val keep = minOf(ra, rb)
        val drop = maxOf(ra, rb)
        parent[drop] = keep
        rootIdentity[keep] = rootIdentity[keep] ?: rootIdentity[drop]
        rootCountry[keep] = rootCountry[keep] ?: rootCountry[drop]
        rootTitles[keep] = mergeSorted(rootTitles[keep], rootTitles[drop])
        rootNames[keep] = NameRules.merge(rootNames[keep], rootNames[drop])
        rootStrong[keep] = rootStrong[keep] && rootStrong[drop]
    }

    /** Verbindung verweigert, ohne [union] aufzurufen (z. B. EPG-ID passt, Namen nicht) – zählt mit. */
    fun refuse() {
        refused++
    }

    private fun conflict(a: Int, b: Int): Boolean {
        val ia = rootIdentity[a]
        val ib = rootIdentity[b]
        val known = ia != null && ib != null
        // Katalog sagt aus beiden Namen „derselbe Sender“ (Zweitnamen wie Chérie 25 = RMC Life): gilt.
        val catalogSame = known && ia == ib && rootStrong[a] && rootStrong[b]
        if (catalogSame) return false
        return (known && ia != ib) || NameRules.clash(rootNames[a], rootNames[b]) || otherCountry(a, b) || otherProgramme(a, b)
    }

    private fun otherCountry(a: Int, b: Int): Boolean {
        val ca = rootCountry[a]
        val cb = rootCountry[b]
        return ca != null && cb != null && ca != cb
    }

    private fun otherProgramme(a: Int, b: Int): Boolean {
        val ta = rootTitles[a]
        val tb = rootTitles[b]
        return ta != null && tb != null && !intersects(ta, tb)
    }

    private fun intersects(a: IntArray, b: IntArray): Boolean {
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> return true
                a[i] < b[j] -> i++
                else -> j++
            }
        }
        return false
    }

    private fun mergeSorted(a: IntArray?, b: IntArray?): IntArray? = when {
        a == null -> b
        b == null -> a
        else -> (a + b).distinct().sorted().toIntArray()
    }
}

/**
 * Sender über alle Zugänge vollautomatisch zusammenführen (sicherste Merkmale zuerst):
 * 1. derselbe echte Sender laut Senderkatalog ([ChannelCatalog]: Name, Zweitname oder EPG-ID), 2. gleiche EPG-ID,
 * 3. gleicher Name im selben Land (Synonyme berücksichtigt), 4. Name ohne Land, wenn es ihn nur in genau einem Land gibt,
 * 5. gleicher Programm-Fingerabdruck (nur eindeutige), 6. eigene „Zusammenlegen“-Regeln.
 * Jede Verbindung läuft durch [GuardedUnion]: Widersprechen Katalog, Land oder laufendes Programm, bleibt es getrennt.
 */
object ChannelClusterer {

    internal fun cluster(
        rows: List<ChannelSourceRow>,
        rules: MatchRules = MatchRules(),
        prints: EpgPrints = EpgPrints(),
        catalog: ChannelCatalog = ChannelCatalog.EMPTY,
        stats: MatchStats? = null,
    ): List<Cluster<ChannelSourceRow>> {
        val epg = rows.map { MatchKeys.epgKeys(it.epgChannelId) }
        val name = rows.map { MatchKeys.channelName(it.groupKey, it.name) }
        val (identity, strong) = identities(rows, name, catalog)
        stats?.let {
            it.identified = identity.count { id -> id != null }
            it.sources = rows.size
        }
        val keys = rows.indices.map { i -> epg[i].map { "e:$it" } + listOfNotNull(name[i]?.let { "n:$it" }) }
        val bare = name.map { it?.let(MatchKeys::withoutCountry) }
        val uf = GuardedUnion(rows.size, identity, name.map { it?.let(MatchKeys::countryOf) }, rows.map { prints.titles[it.id] }, bare, strong)
        fun sameStrongIdentity(a: Int, b: Int) = strong[a] && strong[b] && identity[a] == identity[b]
        fun link(a: Int, b: Int) {
            // Varianten desselben Zugangs (ZDF HD/FHD) trennt keine Regel – die betrifft nur verschiedene Zugänge.
            if (rows[a].playlistId == rows[b].playlistId || !rules.forbids(keys[a], keys[b])) uf.union(a, b)
        }
        linkByKey(identity, uf::find, ::link)
        // Gleiche EPG-ID allein reicht nicht: Anbieter kopieren sie falsch. Die Namen müssen dazu passen.
        linkByKeys(epg, uf::find) { a, b -> if (NameRules.compatible(bare[a], bare[b]) || sameStrongIdentity(a, b)) link(a, b) else uf.refuse() }
        linkByKey(name, uf::find, ::link)
        linkBareNames(name, ::link)
        linkFingerprints(rows, prints.link, ::link)
        linkRules(keys, rules) { a, b -> uf.union(a, b, force = true) }
        stats?.let { it.refused = uf.refused }
        val clusters = collect(rows, uf::find, { idx -> idx.flatMap { keys[it] }.distinct().sorted() }) { idx -> "id:${rows[idx.first()].id}" }
        stats?.let { countConfirmed(clusters, prints, it) }
        return clusters
    }

    /**
     * Katalog-ID je Quelle. Der Name zählt vor der EPG-ID: Anbieter kopieren EPG-IDs gern falsch (ZDFneo mit „ZDF.de“).
     * Zweiter Wert: aus dem Namen erkannt (sicher) statt nur aus der EPG-ID.
     */
    private fun identities(rows: List<ChannelSourceRow>, name: List<String?>, catalog: ChannelCatalog): Pair<List<String?>, List<Boolean>> {
        val byName = name.map { catalog.byNameKey(it) }
        val identity = rows.indices.map { i ->
            byName[i] ?: catalog.byEpgId(rows[i].epgChannelId, name[i]?.let(MatchKeys::withoutCountry), rows[i].name)
        }
        return identity to byName.map { it != null }
    }

    /**
     * Selbstkontrolle: Wie viele Einträge mit Quellen aus mehreren Zugängen das laufende Programm bestätigt (mindestens
     * eine gemeinsame Sendung). Prüfwert fürs Protokoll – unabhängig von Namen, Katalog und EPG-ID.
     */
    private fun countConfirmed(clusters: List<Cluster<ChannelSourceRow>>, prints: EpgPrints, stats: MatchStats) {
        for (c in clusters) {
            if (c.members.map { it.playlistId }.distinct().size < 2) continue
            stats.merged++
            val perAccount = c.members.groupBy { it.playlistId }.values.mapNotNull { m ->
                m.mapNotNull { prints.titles[it.id] }.flatMap { it.toList() }.toSet().ifEmpty { null }
            }
            if (perAccount.size >= 2 && perAccount.drop(1).all { set -> set.any(perAccount.first()::contains) }) stats.confirmed++
        }
    }


    /** Name ohne Land („:zdf“) zu dem einen Land, in dem es ihn gibt („DE:zdf“). In mehreren Ländern: unsicher. */
    private fun linkBareNames(name: List<String?>, link: (Int, Int) -> Unit) {
        val countriesByBare = name.filterNotNull().groupBy(MatchKeys::withoutCountry) { MatchKeys.countryOf(it) }
            .mapValues { (_, c) -> c.filter(String::isNotEmpty).toSet() }
        val firstWithKey = HashMap<String, Int>()
        name.forEachIndexed { i, k -> if (k != null) firstWithKey.putIfAbsent(k, i) }
        name.forEachIndexed { i, k ->
            if (k == null || MatchKeys.countryOf(k).isNotEmpty()) return@forEachIndexed
            val only = countriesByBare[MatchKeys.withoutCountry(k)]?.singleOrNull() ?: return@forEachIndexed
            firstWithKey["$only:${MatchKeys.withoutCountry(k)}"]?.let { link(i, it) }
        }
    }

    /**
     * Programm-Fingerabdruck: Laufen auf zwei Sendern verschiedener Zugänge dieselben nächsten Sendungen, ist es derselbe
     * Sender – aber nur, wenn der Abdruck in jedem Zugang genau zu einem Sender gehört (Regionalfenster wie WDR Köln und
     * WDR Aachen haben oft dasselbe Programm und bleiben so getrennt).
     */
    private fun linkFingerprints(rows: List<ChannelSourceRow>, fingerprints: Map<String, String>, link: (Int, Int) -> Unit) {
        if (fingerprints.isEmpty()) return
        val byPrint = HashMap<String, MutableList<Int>>()
        rows.forEachIndexed { i, r -> fingerprints[r.id]?.let { byPrint.getOrPut(it) { ArrayList() } += i } }
        for (indices in byPrint.values) {
            val perPlaylist = indices.groupBy { rows[it].playlistId }
            if (perPlaylist.size < 2) continue
            val unique = perPlaylist.values.all { group -> group.map { rows[it].groupKey }.distinct().size == 1 }
            val countries = indices.mapNotNull { MatchKeys.channelName(rows[it].groupKey)?.let(MatchKeys::countryOf) }.filter(String::isNotEmpty).toSet()
            if (unique && countries.size <= 1) indices.drop(1).forEach { link(indices.first(), it) }
        }
    }
}

/**
 * Filme und Serien zusammenführen: gleiche TMDB-ID, sonst gleicher bereinigter Titel + Jahr. Gehören zu einem Titel
 * zwei verschiedene TMDB-IDs (Remake), wird über den Titel nicht verbunden. Serien ohne Jahr verbinden sich mit dem
 * einen Jahr, das es zu dem Titel gibt. Was keinen Schlüssel hat, bleibt ein eigener Eintrag.
 */
object VodClusterer {

    fun cluster(rows: List<VodSourceRow>, isSeries: Boolean, rules: MatchRules = MatchRules()): List<Cluster<VodSourceRow>> {
        val parts = rows.map { MatchKeys.title(it.name, it.year, it.tmdbId, yearRequired = !isSeries) }
        val keys = parts.map { p -> listOfNotNull(p?.tmdb, p?.title) }
        // Zwei verschiedene TMDB-IDs sind nie dasselbe Werk – auch nicht über eine Kette gleicher Titel.
        val uf = GuardedUnion(rows.size, parts.map { it?.tmdb }, rows.map { null }, rows.map { null })
        fun link(a: Int, b: Int) {
            // Varianten desselben Zugangs (ZDF HD/FHD) trennt keine Regel – die betrifft nur verschiedene Zugänge.
            if (rows[a].playlistId == rows[b].playlistId || !rules.forbids(keys[a], keys[b])) uf.union(a, b)
        }
        linkByKey(parts.map { it?.tmdb }, uf::find, ::link)
        val tmdbPerTitle = HashMap<String, MutableSet<String>>()
        parts.forEach { p -> if (p?.tmdb != null) tmdbPerTitle.getOrPut(p.title) { HashSet() } += p.tmdb }
        // Titel nur verbinden, wenn er nicht zu zwei verschiedenen Werken gehört.
        linkByKey(parts.map { p -> p?.title?.takeIf { (tmdbPerTitle[it]?.size ?: 0) <= 1 } }, uf::find, ::link)
        if (isSeries) linkSeriesWithoutYear(parts, ::link)
        linkRules(keys, rules) { a, b -> uf.union(a, b, force = true) }
        return collect(rows, uf::find, { idx ->
            val ks = idx.flatMap { keys[it] }.distinct().sorted()
            ks.filter { it.startsWith("tmdb:") } + ks.filterNot { it.startsWith("tmdb:") }
        }) { idx -> "id:${rows[idx.first()].id}" }
    }
}

/** Serie ohne Jahr („DE: Dark“) zu der einen Fassung mit Jahr („Dark (2017)“), sofern es nur eine gibt. */
private fun linkSeriesWithoutYear(parts: List<MatchKeys.TitleParts?>, link: (Int, Int) -> Unit) {
    val yearsPerTitle = HashMap<String, MutableSet<String>>()
    parts.forEach { p -> if (p != null && p.title != p.titleOnly) yearsPerTitle.getOrPut(p.titleOnly) { HashSet() } += p.title }
    val firstWithTitle = HashMap<String, Int>()
    parts.forEachIndexed { i, p -> if (p != null) firstWithTitle.putIfAbsent(p.title, i) }
    parts.forEachIndexed { i, p ->
        if (p == null || p.title != p.titleOnly) return@forEachIndexed
        val only = yearsPerTitle[p.titleOnly]?.singleOrNull() ?: return@forEachIndexed
        firstWithTitle[only]?.let { link(i, it) }
    }
}

/**
 * Alle mit gleichem Schlüssel verbinden – reihenfolgeunabhängig: Je Schlüssel wird gegen **jede** bisherige Gruppe
 * versucht, nicht nur gegen den ersten Treffer. Sonst entschied die Reihenfolge der Liste: Lehnte der Schutz die
 * Verbindung zum ersten Treffer ab (anderer Sender gleichen Namens), blieben zwei passende Quellen getrennt.
 */
private fun linkByKeys(keys: List<List<String>>, find: (Int) -> Int, link: (Int, Int) -> Unit) {
    val groups = HashMap<String, MutableList<Int>>()
    keys.forEachIndexed { i, ks -> ks.forEach { k -> linkInto(groups.getOrPut(k) { ArrayList(2) }, i, find, link) } }
}

private fun linkByKey(keys: List<String?>, find: (Int) -> Int, link: (Int, Int) -> Unit) {
    val groups = HashMap<String, MutableList<Int>>()
    keys.forEachIndexed { i, k -> if (k != null) linkInto(groups.getOrPut(k) { ArrayList(2) }, i, find, link) }
}

/** [reps] = je eine Quelle jeder getrennten Gruppe mit diesem Schlüssel. */
private fun linkInto(reps: MutableList<Int>, i: Int, find: (Int) -> Int, link: (Int, Int) -> Unit) {
    for (r in reps.toList()) if (find(r) != find(i)) link(r, i)
    val roots = HashSet<Int>()
    reps.removeAll { !roots.add(find(it)) }
    if (roots.add(find(i))) reps += i
}

private fun linkRules(keys: List<List<String>>, rules: MatchRules, link: (Int, Int) -> Unit) {
    val merges = rules.merges()
    if (merges.isEmpty()) return
    val byKey = HashMap<String, Int>()
    keys.forEachIndexed { i, ks -> ks.forEach { byKey.putIfAbsent(it, i) } }
    for ((a, b) in merges) {
        val i = byKey[a]
        val j = byKey[b]
        if (i != null && j != null) link(i, j)
    }
}

/** Gruppen einsammeln; Reihenfolge wie die Eingabe (erster Treffer zuerst). */
/**
 * Gruppen einsammeln und jeder einen **eindeutigen** Schlüssel geben (daraus wird die ID in „Alle Zugänge“).
 *
 * Fehler bis 3.36: Der Schlüssel war der kleinste Vergleichsschlüssel der Gruppe. Hatte der Anbieter TRT 2 die EPG-ID
 * von TRT 1 gegeben, trennte der Schutz die beiden Gruppen zwar richtig – beide bekamen aber den Schlüssel „e:trt1.tr“
 * und damit dieselbe ID. In der Datenbank lagen dann TRT 1 und TRT 2 (bzw. ProSieben und MAXX) wieder in einem Eintrag.
 * Jetzt: erster Kandidat, der **nur** zu dieser Gruppe gehört; sonst Kandidat + erste Quelle; immer eindeutig.
 */
private fun <T> collect(rows: List<T>, find: (Int) -> Int, candidates: (List<Int>) -> List<String>, fallback: (List<Int>) -> String): List<Cluster<T>> {
    val groups = LinkedHashMap<Int, MutableList<Int>>()
    rows.indices.forEach { i -> groups.getOrPut(find(i)) { ArrayList() } += i }
    val lists = groups.values.toList()
    val keyLists = lists.map(candidates)
    val owners = HashMap<String, Int>()
    keyLists.forEach { ks -> ks.forEach { owners[it] = (owners[it] ?: 0) + 1 } }
    val used = HashSet<String>()
    return lists.mapIndexed { n, idx ->
        val ks = keyLists[n]
        val preferred = ks.firstOrNull { owners[it] == 1 } ?: ks.firstOrNull()?.let { "$it#${fallback(idx)}" } ?: fallback(idx)
        var key = preferred
        var suffix = 1
        while (!used.add(key)) key = "$preferred#${suffix++}"
        Cluster(key, idx.map { rows[it] })
    }
}
