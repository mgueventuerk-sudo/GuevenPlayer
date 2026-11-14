package com.mg.player.core.device

/** Wie viel im Voraus geladen werden darf. */
enum class PreloadLevel {
    /** Nichts: Speicher knapp, kürzlich Speicherwarnung, oder in den Einstellungen aus. */
    OFF,

    /** Nur Bilder in den Datei-Cache (kostet keinen Arbeitsspeicher), wenige Einträge voraus. */
    LIGHT,

    /** Mehr Einträge voraus, und die Bilder liegen schon fertig im Arbeitsspeicher; Nicht-Genutztes wird wieder freigegeben. */
    FULL,
}

/**
 * [imagesAhead]: wie viele Einträge hinter dem letzten sichtbaren **fertig im Arbeitsspeicher** vorgeladen werden
 * (nur mit [warmMemory]). [diskAhead]: wie viele insgesamt in den **Datei-Cache im internen Speicher** vorgeladen werden –
 * das kostet keinen Arbeitsspeicher, nur Platz und Datenmenge, und darf deshalb weiter reichen. [keepBehind]: so viele
 * Einträge vor dem ersten sichtbaren bleiben im Speicher vorgeladen, falls man zurückblättert – was weiter weg liegt und nie
 * gezeigt wurde, wird freigegeben.
 */
data class PreloadBudget(
    val level: PreloadLevel,
    val imagesAhead: Int,
    val warmMemory: Boolean = false,
    val keepBehind: Int = 0,
    val diskAhead: Int = imagesAhead,
    /**
     * Genug Arbeitsspeicher für **einen zusätzlichen vorgeladenen Stream** (zweiter Decoder und Puffer neben dem laufenden):
     * nie auf schwachen Geräten, sonst nur bei Android-Schwelle + 200 MB und 48 MB Heap, ohne Speichernot und ohne Warnung.
     */
    val streamsOk: Boolean = false,
) {
    val active: Boolean get() = level != PreloadLevel.OFF

    companion object {
        val OFF = PreloadBudget(PreloadLevel.OFF, 0)
    }
}

/** Was Android über den Speicher sagt (alles in MB). */
data class MemoryReading(
    /** Frei verfügbarer Systemspeicher (`ActivityManager.MemoryInfo.availMem`). */
    val availMb: Long,
    /** Ab hier beginnt Android, Apps zu beenden (`threshold`). */
    val thresholdMb: Long,
    val lowMemory: Boolean,
    /** Noch nicht belegter Teil des App-Heaps (`maxMemory - (totalMemory - freeMemory)`). */
    val heapFreeMb: Long,
    /** Speicherwarnung von Android in den letzten Minuten. */
    val trimmedRecently: Boolean,
    /** Freier Platz im internen Speicher, dort wo der Bild-Cache liegt. */
    val storageFreeMb: Long = Long.MAX_VALUE,
)

/**
 * Entscheidung „darf vorgeladen werden, und wie viel?“. Rein (ohne Android), damit sie testbar ist. Die Grenzwerte sind
 * Startwerte, **nicht am Gerät gemessen** – das Fehlerprotokoll nennt bei jedem Vorladen Stufe, freien Speicher und Heap,
 * damit sie sich mit echten Geräten nachstellen lassen.
 */
object PreloadPolicyRules {
    /** Reserve über der Android-Schwelle, ab der auch der Arbeitsspeicher selbst mitmacht. */
    const val MIN_RESERVE_MB = 200L

    /** Reserve, ab der auch die große Stufe (Arbeitsspeicher) erlaubt ist. */
    const val FULL_RESERVE_MB = 500L

    /** Freier Heap, ab dem der Arbeitsspeicher mitmacht, bzw. ab dem die große Stufe erlaubt ist. */
    const val MIN_HEAP_FREE_MB = 48L
    const val FULL_HEAP_FREE_MB = 96L

    /** Selbst für das Vorladen auf die Festplatte (kleine Bilder dekodieren, kurz) muss noch etwas Heap frei sein. */
    const val MIN_HEAP_DISK_MB = 24L

    /** Unter diesem freien Platz im internen Speicher wird gar nicht vorgeladen; darüber wächst die Reichweite. */
    const val MIN_STORAGE_MB = 500L
    const val ROOMY_STORAGE_MB = 1_024L
    const val PLENTY_STORAGE_MB = 3_072L

    private val LIGHT_AHEAD = mapOf(DeviceTier.LOW to 12, DeviceTier.NORMAL to 18, DeviceTier.HIGH to 24)
    private val FULL_AHEAD = mapOf(DeviceTier.NORMAL to 30, DeviceTier.HIGH to 60)

    /** Wie weit die Festplatte vorladen darf: je mehr Platz, desto weiter. Schwache Geräte decken bei 30 (Rechenzeit). */
    private fun storageAhead(tier: DeviceTier, storageFreeMb: Long): Int {
        val ahead = when {
            storageFreeMb >= PLENTY_STORAGE_MB -> DISK_AHEAD_PLENTY
            storageFreeMb >= ROOMY_STORAGE_MB -> DISK_AHEAD_ROOMY
            else -> DISK_AHEAD_TIGHT
        }
        return if (tier == DeviceTier.LOW) minOf(ahead, DISK_AHEAD_TIGHT) else ahead
    }

    private const val DISK_AHEAD_PLENTY = 120
    private const val DISK_AHEAD_ROOMY = 60
    private const val DISK_AHEAD_TIGHT = 30

    /**
     * Nur interner Speicher gewählt: Arbeitsspeicher bleibt tabu, die Platte reicht so weit, wie der Platz es erlaubt
     * (Heap nur für das kurze Dekodieren kleiner Bilder nötig).
     */
    private fun diskOnlyBudget(memory: MemoryReading, disk: Int, lightAhead: Int): PreloadBudget {
        val reach = if (memory.heapFreeMb >= MIN_HEAP_FREE_MB) maxOf(disk, lightAhead) else lightAhead
        return PreloadBudget(PreloadLevel.LIGHT, lightAhead, diskAhead = reach)
    }

    /**
     * Zwei getrennte Fragen: Darf der **Arbeitsspeicher** mitmachen (nur bei viel freiem RAM und Heap)? Und darf die
     * **Festplatte** vorladen (bei genug freiem Platz, auch wenn der RAM knapp ist – dann ist sie der Ausweg)? Aus bleibt
     * nur, wenn Android Speichernot meldet, der Platz nicht reicht oder kaum Heap übrig ist.
     */
    fun decide(tier: DeviceTier, memory: MemoryReading, enabled: Boolean, diskOnly: Boolean = false): PreloadBudget {
        val warned = memory.lowMemory || memory.trimmedRecently
        val noRoom = memory.storageFreeMb < MIN_STORAGE_MB || memory.heapFreeMb < MIN_HEAP_DISK_MB
        if (!enabled || warned || noRoom) return PreloadBudget.OFF
        val ramFine = memory.availMb >= memory.thresholdMb + MIN_RESERVE_MB && memory.heapFreeMb >= MIN_HEAP_FREE_MB
        val roomy = ramFine && memory.availMb >= memory.thresholdMb + FULL_RESERVE_MB && memory.heapFreeMb >= FULL_HEAP_FREE_MB
        val disk = storageAhead(tier, memory.storageFreeMb)
        val lightAhead = LIGHT_AHEAD.getValue(tier)
        if (diskOnly) return diskOnlyBudget(memory, disk, lightAhead)
        val fullAhead = FULL_AHEAD[tier]
        val streams = ramFine && tier != DeviceTier.LOW
        return when {
            roomy && fullAhead != null -> PreloadBudget(
                PreloadLevel.FULL,
                fullAhead,
                warmMemory = true,
                keepBehind = fullAhead / 2,
                diskAhead = maxOf(disk, fullAhead),
                streamsOk = streams,
            )

            // RAM reicht nicht für die große Stufe: Die Festplatte lädt, so weit der Platz es erlaubt – bei knappem RAM nur wenig.
            ramFine -> PreloadBudget(PreloadLevel.LIGHT, lightAhead, diskAhead = maxOf(disk, lightAhead), streamsOk = streams)

            else -> PreloadBudget(PreloadLevel.LIGHT, lightAhead, diskAhead = lightAhead)
        }
    }
}
