package com.mg.player.player.supervisor

import com.mg.player.domain.model.PlayerEngineType

/** Was bei einem Inhalt zuletzt geholfen hat – der nächste Start beginnt gleich damit. */
data class StartHint(val engine: PlayerEngineType? = null, val safeDecoder: Boolean = false, val safeAudio: Boolean = false) {
    val isEmpty: Boolean get() = engine == null && !safeDecoder && !safeAudio
}

/** Gedächtnis des Wächters je Inhalt (Ersatzvarianten merkt sich schon StreamMemory). */
interface RecoveryMemory {
    fun hintFor(contentKey: String): StartHint?

    fun remember(contentKey: String, hint: StartHint)

    fun forget(contentKey: String)

    /** Zählt Befunde und Erfolge je Symptom (lokale Auswertung, keine Zugangsdaten). */
    fun count(symptom: Symptom, fixed: Boolean) {}

    /** Zähler für die Anzeige: Symptom -> (erkannt, behoben). */
    fun stats(): Map<Symptom, Pair<Int, Int>> = emptyMap()
}

/** Für Tests und als Rückfall ohne Speicher. */
class InMemoryRecoveryMemory : RecoveryMemory {
    private val hints = HashMap<String, StartHint>()
    val counts = HashMap<String, Int>()

    override fun hintFor(contentKey: String): StartHint? = hints[contentKey]

    override fun remember(contentKey: String, hint: StartHint) {
        hints[contentKey] = hint
    }

    override fun forget(contentKey: String) {
        hints.remove(contentKey)
    }

    override fun count(symptom: Symptom, fixed: Boolean) {
        val key = symptom.code + if (fixed) ".ok" else ".seen"
        counts[key] = (counts[key] ?: 0) + 1
    }
}

/**
 * Schutzschalter je Anbieter: Scheitern mehrere verschiedene Inhalte desselben Anbieters kurz hintereinander an
 * Netz oder Anmeldung, liegt das Problem beim Anbieter. Dann wird einmal gemeldet, statt bei jedem Sender alle Stufen
 * zu probieren und das Verbindungslimit weiter zu belasten.
 */
class ProviderBreaker(private val windowMs: Long = 120_000, private val threshold: Int = 3) {
    private val failures = HashMap<String, MutableList<Pair<Long, String>>>()

    @Synchronized
    fun recordFailure(provider: String?, content: String, now: Long) {
        if (provider == null) return
        val list = failures.getOrPut(provider) { mutableListOf() }
        list.removeAll { now - it.first > windowMs || it.second == content }
        list += now to content
    }

    @Synchronized
    fun recordSuccess(provider: String?) {
        if (provider != null) failures.remove(provider)
    }

    @Synchronized
    fun isOpen(provider: String?, now: Long): Boolean {
        val list = failures[provider ?: return false] ?: return false
        list.removeAll { now - it.first > windowMs }
        return list.size >= threshold
    }

    companion object {
        /** Symptome, die auf eine Störung beim Anbieter hindeuten können. */
        val PROVIDER_SYMPTOMS = setOf(Symptom.AUTH, Symptom.NETWORK, Symptom.RATE_LIMITED, Symptom.START_NO_DATA)
    }
}
