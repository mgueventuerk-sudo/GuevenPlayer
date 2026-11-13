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
 * [imagesAhead]: wie viele Einträge hinter dem letzten sichtbaren vorgeladen werden. [warmMemory]: auch in den
 * Arbeitsspeicher (sonst nur Datei-Cache). [keepBehind]: so viele Einträge vor dem ersten sichtbaren bleiben vorgeladen,
 * falls man zurückblättert – was weiter weg liegt und nie gezeigt wurde, wird freigegeben.
 */
data class PreloadBudget(val level: PreloadLevel, val imagesAhead: Int, val warmMemory: Boolean = false, val keepBehind: Int = 0) {
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
)

/**
 * Entscheidung „darf vorgeladen werden, und wie viel?“. Rein (ohne Android), damit sie testbar ist. Die Grenzwerte sind
 * Startwerte, **nicht am Gerät gemessen** – das Fehlerprotokoll nennt bei jedem Vorladen Stufe, freien Speicher und Heap,
 * damit sie sich mit echten Geräten nachstellen lassen.
 */
object PreloadPolicyRules {
    /** Reserve über der Android-Schwelle, unter der gar nicht mehr vorgeladen wird. */
    const val MIN_RESERVE_MB = 200L

    /** Reserve, ab der auch die große Stufe erlaubt ist. */
    const val FULL_RESERVE_MB = 500L

    /** Freier Heap, unter dem nicht mehr vorgeladen wird, bzw. ab dem die große Stufe erlaubt ist. */
    const val MIN_HEAP_FREE_MB = 48L
    const val FULL_HEAP_FREE_MB = 96L

    private val LIGHT_AHEAD = mapOf(DeviceTier.LOW to 12, DeviceTier.NORMAL to 18, DeviceTier.HIGH to 24)
    private val FULL_AHEAD = mapOf(DeviceTier.NORMAL to 30, DeviceTier.HIGH to 60)

    fun decide(tier: DeviceTier, memory: MemoryReading, enabled: Boolean): PreloadBudget {
        val warned = memory.lowMemory || memory.trimmedRecently
        val tight = memory.availMb < memory.thresholdMb + MIN_RESERVE_MB || memory.heapFreeMb < MIN_HEAP_FREE_MB
        if (!enabled || warned || tight) return PreloadBudget.OFF
        val roomy = memory.availMb >= memory.thresholdMb + FULL_RESERVE_MB && memory.heapFreeMb >= FULL_HEAP_FREE_MB
        val fullAhead = FULL_AHEAD[tier]
        return if (roomy && fullAhead != null) {
            PreloadBudget(PreloadLevel.FULL, fullAhead, warmMemory = true, keepBehind = fullAhead / 2)
        } else {
            PreloadBudget(PreloadLevel.LIGHT, LIGHT_AHEAD.getValue(tier))
        }
    }
}
