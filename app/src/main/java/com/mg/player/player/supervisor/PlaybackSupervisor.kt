package com.mg.player.player.supervisor

import com.mg.player.player.engine.EngineState
import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wiedergabe-Wächter: eine einzige Stelle, die über Gegenmaßnahmen entscheidet.
 *
 * - Eingänge: Zustände der Engine ([onEngineState]), Momentaufnahmen im Takt ([onTick]), Netz ([onNetwork]),
 *   Speicherdruck ([onDevicePressure]) und Eingriffe des Nutzers ([onUserInterrupt]).
 * - Je Symptom eine Leiter von sanft nach hart ([Ladders]), nicht anwendbare Stufen werden übersprungen.
 * - Budget: höchstens [SupervisorConfig.maxActionsPerSession] Maßnahmen je Inhalt und
 *   [SupervisorConfig.maxActionsPerMinute] je Minute; danach eine klare Meldung statt Endlosschleife.
 * - Nach einer Maßnahme Ruhezeit ([SupervisorConfig.cooldown]) für die Detektoren; Fehler der Engine zählen weiter.
 * - Schutzschalter je Anbieter ([ProviderBreaker]) und Gedächtnis je Inhalt ([RecoveryMemory]).
 *
 * Läuft komplett auf dem Main-Thread und ohne eigene Timer: Verzögerte Maßnahmen werden im Takt von [onTick]
 * fällig. Dadurch ist der Ablauf mit einer virtuellen Uhr vollständig testbar.
 */
class PlaybackSupervisor(
    private val actions: SupervisorActions,
    private val memory: RecoveryMemory = InMemoryRecoveryMemory(),
    private val breaker: ProviderBreaker = ProviderBreaker(),
    private val config: SupervisorConfig = SupervisorConfig(),
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private data class Pending(val dueAt: Long, val step: RecoveryStep, val symptom: Symptom, val counted: Boolean)

    private val mutableStatus = MutableStateFlow(SupervisorStatus())
    val status: StateFlow<SupervisorStatus> = mutableStatus.asStateFlow()

    private val detectors = DetectorSet()

    private var session: SessionInfo? = null
    private var preparedAt = 0L
    private val ladderPosition = HashMap<Symptom, Int>()
    private val budget = MinuteBudget(config.maxActionsPerMinute)
    private var actionsThisSession = 0
    private var cooldownUntil = 0L
    private var pending: Pending? = null
    private var online = true
    private var waitingForNetwork = false
    private var onlineSince = -1L
    private var gaveUp: Symptom? = null
    private var stableSince = -1L
    private var stableHandled = false
    private var activeFix = StartHint()
    private var lastProblem: Symptom? = null
    private var problemSince = -1L
    private var formatHandledForPrepare = false
    private val reportedOncePerSession = HashSet<Symptom>()

    /** Anzahl der Maßnahmen im aktuellen Inhalt (für Statuszeile und Tests). */
    val actionCount: Int get() = actionsThisSession

    /** Neuer Inhalt (anderer Sender, Film, Folge). [applied]: Einstellungen, mit denen er startet (aus dem Gedächtnis). */
    fun start(session: SessionInfo, applied: StartHint = StartHint()) {
        this.session = session
        activeFix = applied
        ladderPosition.clear()
        budget.clear()
        actionsThisSession = 0
        pending = null
        gaveUp = null
        waitingForNetwork = false
        stableHandled = false
        lastProblem = null
        problemSince = -1L
        reportedOncePerSession.clear()
        detectors.resetSession()
        publish(SupervisorStatus())
    }

    /** Variante oder Engine hat gewechselt (gleicher Inhalt). */
    fun updateSession(session: SessionInfo) {
        this.session = session
    }

    /** Hinweise aus dem Gedächtnis für diesen Inhalt. */
    fun hintFor(contentKey: String): StartHint? = memory.hintFor(contentKey)

    /** Jeder Start der Engine (auch nach Neu-Verbinden): Start-Erkennung neu beginnen. */
    fun onPrepared() {
        preparedAt = clock()
        stableSince = -1L
        formatHandledForPrepare = false
        detectors.resetAll()
    }

    fun onEngineState(state: EngineState) {
        val now = clock()
        when {
            state.status == PlaybackStatus.READY && state.isPlaying -> onPlaying(now)

            state.status == PlaybackStatus.ERROR -> {
                stableSince = -1L
                onFailure(
                    SupervisorRules.symptomOf(state),
                    SupervisorRules.describe(state),
                    state.httpCode,
                    retryAfterMs = state.retryAfterSec?.times(MS_PER_S),
                )
            }

            state.status == PlaybackStatus.ENDED && session?.isLive == true -> onFailure(Symptom.LIVE_ENDED, "Server hat den Stream beendet", null)

            state.status == PlaybackStatus.BUFFERING -> stableSince = -1L

            else -> Unit
        }
        if (state.formatUnsupported && state.status != PlaybackStatus.ERROR && !formatHandledForPrepare) {
            formatHandledForPrepare = true
            onFailure(Symptom.FORMAT, "Spur nicht abspielbar", null)
        }
    }

    /** Player-Takt: fällige Maßnahmen ausführen, Netz abwarten, Detektoren füttern. */
    fun onTick(health: HealthSnapshot?) {
        val now = clock()
        pending?.let { if (now >= it.dueAt) runPending(it) }
        if (networkBackFor(now)) {
            waitingForNetwork = false
            log("Wächter: Netz wieder da – verbinde neu")
            schedule(Pending(now + budget.waitMs(now), RecoveryStep.Reload(), Symptom.NETWORK, counted = false))
        }
        val current = session ?: return
        if (health == null || gaveUp != null || waitingForNetwork) return
        trackStability(health, now)
        if (pending == null && now >= cooldownUntil) detect(current, health, now)
    }

    private fun networkBackFor(now: Long): Boolean = waitingForNetwork && online && onlineSince >= 0 && now - onlineSince >= config.networkSettle

    private fun detect(current: SessionInfo, health: HealthSnapshot, now: Long) {
        val ctx = DetectContext(current, preparedAt, config)
        detectors.check(health, now, ctx)?.let { onFailure(it.symptom, it.evidence, null) }
    }

    /** Netzstatus; [changed] = echter Wechsel (online, WLAN/LAN, VPN), nicht nur Schwankung. */
    fun onNetwork(online: Boolean, changed: Boolean) {
        val now = clock()
        val wasOnline = this.online
        this.online = online
        if (!online) {
            if (pending != null || mutableStatus.value.reconnecting) enterNetworkWait()
            return
        }
        if (!wasOnline) onlineSince = now
        val failed = gaveUp
        if (changed && failed != null && Ladders.retryOnNetworkChange(failed)) {
            log("Wächter: Netzwechsel – neuer Versuch nach Aufgeben (${failed.code})")
            gaveUp = null
            ladderPosition.clear()
            actionsThisSession = 0
            pending = Pending(now + maxOf(config.networkSettle, budget.waitMs(now)), RecoveryStep.Reload(), Symptom.NETWORK, counted = false)
            publish(SupervisorStatus(reconnecting = true, text = "Netz geändert – verbinde neu"))
        }
    }

    /** Wenig Speicher oder Gerät zu heiß: einmal je Inhalt gegensteuern. */
    fun onDevicePressure() {
        if (reportedOncePerSession.add(Symptom.DEVICE_PRESSURE)) onFailure(Symptom.DEVICE_PRESSURE, "Speicherdruck/Temperatur", null)
    }

    /** Der Nutzer greift ein (Umschalten, Quelle/Engine von Hand, „Erneut“): laufende Maßnahmen verwerfen. */
    fun onUserInterrupt() {
        pending = null
        gaveUp = null
        waitingForNetwork = false
        ladderPosition.clear()
        actionsThisSession = 0
        budget.clear()
        publish(SupervisorStatus())
    }

    fun stop() {
        pending = null
        session = null
        waitingForNetwork = false
        publish(SupervisorStatus())
    }

    private fun onPlaying(now: Long) {
        if (stableSince < 0) stableSince = now
        breaker.recordSuccess(session?.providerKey)
        val problem = lastProblem
        if (problem != null && problemSince >= 0) {
            log("Wächter: ${problem.code} ${problem.label} behoben nach ${formatSeconds(now - problemSince)} s")
            memory.count(problem, fixed = true)
            lastProblem = null
            problemSince = -1L
        }
        if (isIdle() && mutableStatus.value != SupervisorStatus()) publish(SupervisorStatus())
    }

    private fun trackStability(health: HealthSnapshot, now: Long) {
        if (health.status != PlaybackStatus.READY || !health.isPlaying) return
        if (stableSince < 0) stableSince = now
        if (stableHandled || now - stableSince < config.stablePlayback) return
        stableHandled = true
        ladderPosition.clear()
        actionsThisSession = 0
        val key = session?.contentKey ?: return
        if (activeFix.isEmpty) memory.forget(key) else memory.remember(key, activeFix)
    }

    private fun isIdle() = pending == null && gaveUp == null && !waitingForNetwork

    private fun onFailure(symptom: Symptom, evidence: String, httpCode: Int?, retryAfterMs: Long? = null) {
        val current = session ?: return
        when {
            gaveUp != null -> Unit
            symptom.informational -> reportInfo(symptom, evidence)
            SupervisorRules.isNetworkSymptom(symptom) && !online -> enterNetworkWait()
            pending == null -> react(current, Failure(symptom, evidence, httpCode, retryAfterMs))
        }
    }

    /** Hinweis ohne Eingriff (Radio, stumm, Ruckeln) – einmal je Inhalt. */
    private fun reportInfo(symptom: Symptom, evidence: String) {
        if (!reportedOncePerSession.add(symptom)) return
        log("Wächter: ${symptom.code} ${symptom.label} ($evidence)")
        Ladders.forSymptom(symptom).firstOrNull { it is RecoveryStep.Hint }?.let { actions.execute(it, symptom) }
    }

    private data class Failure(val symptom: Symptom, val evidence: String, val httpCode: Int?, val retryAfterMs: Long?)

    private fun react(current: SessionInfo, failure: Failure) {
        val now = clock()
        val symptom = failure.symptom
        memory.count(symptom, fixed = false)
        if (lastProblem == null) {
            lastProblem = symptom
            problemSince = now
        }
        if (providerDown(current, symptom, now)) {
            giveUp(
                symptom,
                failure.httpCode,
                "Der Anbieter antwortet gerade bei mehreren Sendern nicht${SupervisorRules.httpSuffix(failure.httpCode)}. Bitte später erneut versuchen.",
            )
            return
        }
        val step = nextStep(symptom, current)
        val budgetLeft = actionsThisSession < config.maxActionsPerSession
        if (!budgetLeft) log("Wächter: Budget von ${config.maxActionsPerSession} Maßnahmen ausgeschöpft")
        if (step is RecoveryStep.GiveUp || !budgetLeft) {
            giveUp(symptom, failure.httpCode)
            return
        }
        scheduleStep(step, failure, now)
    }

    private fun providerDown(current: SessionInfo, symptom: Symptom, now: Long): Boolean {
        if (symptom !in ProviderBreaker.PROVIDER_SYMPTOMS) return false
        breaker.recordFailure(current.providerKey, current.contentKey, now)
        return breaker.isOpen(current.providerKey, now)
    }

    private fun scheduleStep(step: RecoveryStep, failure: Failure, now: Long) {
        val symptom = failure.symptom
        val retryAfterMs = failure.retryAfterMs
        val evidence = failure.evidence
        val budgetWait = if (step is RecoveryStep.Hint) 0L else budget.waitMs(now)
        val stepDelay = (step as? RecoveryStep.Reload)?.delayMs ?: 0L
        val wait = maxOf(stepDelay, budgetWait, retryAfterMs?.coerceAtMost(MAX_RETRY_AFTER) ?: 0L)
        val progress = "${actionsThisSession + 1}/${config.maxActionsPerSession}"
        log("Wächter: ${symptom.code} ${symptom.label} ($evidence) → ${step.label} ($progress)")
        val next = Pending(now + wait, step, symptom, counted = step !is RecoveryStep.Hint)
        publish(SupervisorStatus(reconnecting = true, text = "${symptom.label} – ${step.label} ($progress)"))
        schedule(next)
    }

    private fun schedule(item: Pending) {
        if (item.dueAt <= clock()) execute(item) else pending = item
    }


    private fun nextStep(symptom: Symptom, current: SessionInfo): RecoveryStep {
        val ladder = Ladders.forSymptom(symptom)
        var index = ladderPosition[symptom] ?: 0
        while (index < ladder.lastIndex && !SupervisorRules.applicable(ladder[index], current, activeFix)) index++
        ladderPosition[symptom] = index + 1
        return ladder[index.coerceAtMost(ladder.lastIndex)]
    }

    private fun runPending(item: Pending) {
        pending = null
        if (SupervisorRules.isNetworkSymptom(item.symptom) && !online) {
            enterNetworkWait()
            return
        }
        execute(item)
    }

    private fun execute(item: Pending) {
        val now = clock()
        val done = runCatching { actions.execute(item.step, item.symptom) }.getOrElse { e ->
            log("Wächter: Maßnahme ${item.step.label} fehlgeschlagen (${e.javaClass.simpleName})")
            false
        }
        if (!done) {
            // Hier nicht möglich (z. B. Quelle nicht auflösbar): sofort die nächste Stufe.
            onFailure(item.symptom, "Stufe nicht möglich: ${item.step.label}", null)
            return
        }
        if (item.step !is RecoveryStep.Hint) budget.record(now)
        if (item.counted) actionsThisSession++
        val quick = session?.let { it.isLive && it.canSwitchVariant } == true
        cooldownUntil = now + if (quick) config.cooldownWithAlternative else config.cooldown
        stableHandled = false
        activeFix = when (val step = item.step) {
            RecoveryStep.SafeDecoder -> activeFix.copy(safeDecoder = true)
            RecoveryStep.SafeAudio -> activeFix.copy(safeAudio = true)
            is RecoveryStep.SwitchEngine -> activeFix.copy(engine = step.target)
            else -> activeFix
        }
        // Bilder-/Tonzähler beginnen nach der Maßnahme neu; die Start-Erkennung setzt erst onPrepared zurück.
        detectors.resetFollowUp()
        if (item.step is RecoveryStep.Hint) publish(SupervisorStatus())
    }

    private fun giveUp(symptom: Symptom, httpCode: Int?, message: String = Ladders.failureMessage(symptom, httpCode)) {
        gaveUp = symptom
        pending = null
        log("Wächter: ${symptom.code} ${symptom.label} – aufgegeben nach $actionsThisSession Maßnahmen")
        publish(SupervisorStatus(failure = message, failedSymptom = symptom))
    }

    private fun enterNetworkWait() {
        pending = null
        waitingForNetwork = true
        onlineSince = -1L
        publish(SupervisorStatus(reconnecting = true, waitingForNetwork = true, text = "Warte auf Netzwerk"))
    }

    private fun publish(status: SupervisorStatus) {
        mutableStatus.value = status
    }

    private companion object {
        const val MS_PER_S = 1_000L
        const val MAX_RETRY_AFTER = 30_000L
    }
}
