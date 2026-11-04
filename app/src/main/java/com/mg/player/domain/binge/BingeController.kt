package com.mg.player.domain.binge

import androidx.compose.runtime.Immutable
import com.mg.player.domain.model.IntroSkipMode

data class BingeConfig(
    val isEpisode: Boolean,
    val hasNextEpisode: Boolean,
    val introMode: IntroSkipMode,
    val introSkipSeconds: Int,
    val autoPlayNext: Boolean,
    val nextTriggerSeconds: Int,
)

@Immutable
data class BingeState(
    val skipIntroVisible: Boolean = false,
    val skipIntroLabel: String = "",
    val nextVisible: Boolean = false,
    val nextSecondsLeft: Int = 0,
    val nextProgress: Float = 0f,
)

sealed interface BingeAction {
    data class SeekTo(val positionMs: Long) : BingeAction
    data object PlayNext : BingeAction
}

data class SkipResult(val seekToMs: Long, val learnedWindow: IntroWindow?)

/**
 * Netflix-artige Komfortlogik für eine laufende Episode – reines Kotlin ohne Android-Abhängigkeit.
 * Wird vom Player regelmäßig mit Position/Dauer "getickt" und liefert UI-Zustand plus Aktionen.
 */
class BingeController(private val config: BingeConfig) {

    /** Bekanntes Intro (Kapitel oder Serien-Profil); null = Fallback-Zeitfenster. */
    var knownWindow: IntroWindow? = null
    var outroStartMs: Long? = null

    private var introShownAtMs: Long? = null
    private var introDismissed = false
    private var autoSkipped = false
    private var countdownStartMs: Long? = null
    private var nextCancelled = false
    private var nextFired = false

    private val introEnabled get() = config.isEpisode && config.introMode != IntroSkipMode.OFF
    private val nextEnabled get() = config.isEpisode && config.autoPlayNext && config.hasNextEpisode

    fun tick(positionMs: Long, durationMs: Long, nowMs: Long): Pair<BingeState, BingeAction?> {
        var action: BingeAction? = null
        var state = BingeState()

        if (introEnabled) {
            val window = knownWindow ?: IntroResolver.fallback()
            val inWindow = positionMs >= window.startMs && positionMs < window.endMs - 2_000
            if (positionMs < window.startMs - 5_000) {
                introDismissed = false
                autoSkipped = false
                introShownAtMs = null
            }
            val canAutoSkip = config.introMode == IntroSkipMode.AUTO && window.source != IntroSource.FALLBACK
            if (inWindow && canAutoSkip && !autoSkipped) {
                autoSkipped = true
                action = BingeAction.SeekTo(window.endMs)
            } else if (inWindow && !introDismissed) {
                val shownAt = introShownAtMs ?: nowMs.also { introShownAtMs = it }
                if (nowMs - shownAt < INTRO_BUTTON_VISIBLE_MS) {
                    state = state.copy(skipIntroVisible = true, skipIntroLabel = introLabel(window))
                } else {
                    introDismissed = true
                }
            }
        }

        if (nextEnabled && durationMs > MIN_DURATION_FOR_NEXT_MS && !nextFired) {
            val trigger = minOf(durationMs - config.nextTriggerSeconds * 1_000L, outroStartMs ?: Long.MAX_VALUE)
            if (positionMs < trigger - 5_000) {
                countdownStartMs = null
                nextCancelled = false
            } else if (positionMs >= trigger && !nextCancelled) {
                val start = countdownStartMs ?: nowMs.also { countdownStartMs = it }
                val elapsed = nowMs - start
                if (elapsed >= COUNTDOWN_MS) {
                    nextFired = true
                    action = BingeAction.PlayNext
                } else {
                    val secondsLeft = ((COUNTDOWN_MS - elapsed + 999) / 1_000).toInt()
                    state = state.copy(nextVisible = true, nextSecondsLeft = secondsLeft, nextProgress = elapsed.toFloat() / COUNTDOWN_MS)
                }
            }
        }
        return state to action
    }

    /**
     * Nutzer drückt "Intro überspringen". Beim Fallback wird das übersprungene Fenster
     * als Serien-Profil gelernt (Stufe 2) und künftig automatisch angewandt.
     */
    fun onSkipIntro(positionMs: Long): SkipResult {
        introDismissed = true
        val known = knownWindow
        return if (known != null) {
            SkipResult(known.endMs, if (known.source == IntroSource.CHAPTER) known else null)
        } else {
            val target = positionMs + config.introSkipSeconds * 1_000L
            val learned = IntroWindow(positionMs, target, IntroSource.PROFILE)
            knownWindow = learned
            SkipResult(target, learned)
        }
    }

    fun dismissIntro() {
        introDismissed = true
    }

    fun onCancelNext() {
        nextCancelled = true
        countdownStartMs = null
    }

    fun onPlayNextNow(): BingeAction {
        nextFired = true
        return BingeAction.PlayNext
    }

    /** Am Stream-Ende direkt weiter, sofern nicht abgebrochen. */
    fun shouldPlayNextOnEnd(): Boolean = nextEnabled && !nextCancelled

    private fun introLabel(window: IntroWindow): String =
        if (window.source == IntroSource.FALLBACK) "Intro überspringen (+${config.introSkipSeconds}s)" else "Intro überspringen"

    private companion object {
        const val INTRO_BUTTON_VISIBLE_MS = 8_000L
        const val COUNTDOWN_MS = 10_000L
        const val MIN_DURATION_FOR_NEXT_MS = 120_000L
    }
}
