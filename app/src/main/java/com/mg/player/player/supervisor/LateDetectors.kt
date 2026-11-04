package com.mg.player.player.supervisor

import com.mg.player.player.engine.HealthSnapshot
import kotlin.math.abs

private const val PERCENT = 100

/** Hilfsklasse: Bedingung muss [durationMs] am Stück gelten, dann genau einmal melden. */
private class Sustained {
    private var since = -1L
    private var fired = false

    /** Liefert die Dauer, sobald die Bedingung lange genug gilt (einmalig), sonst null. */
    fun update(active: Boolean, now: Long, durationMs: Long): Long? {
        if (!active) {
            reset()
            return null
        }
        if (since < 0) since = now
        if (fired || now - since < durationMs) return null
        fired = true
        return now - since
    }

    fun reset() {
        since = -1L
        fired = false
    }
}

/** S7: Ton und Bild laufen auseinander (nur, wenn die Engine den Versatz meldet). */
class DesyncDetector : Detector {
    private val sustained = Sustained()

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        val offset = s.avOffsetMs
        val active = offset != null && s.isPlaying && abs(offset) > ctx.config.desyncThreshold
        val duration = sustained.update(active, now, ctx.config.desyncDuration) ?: return null
        return Finding(Symptom.AV_DESYNC, "Versatz $offset ms seit $duration ms")
    }

    override fun reset() = sustained.reset()
}

/** S8: Ruckeln – zu viele verworfene Bilder im Messfenster. Nur ein Hinweis je Inhalt. */
class JudderDetector : Detector {
    private val window = ArrayDeque<Triple<Long, Long, Long>>()
    private var fired = false

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        val frames = s.renderedFrames.takeIf { !fired && s.isPlaying } ?: return null
        val dropped = s.droppedFrames ?: return null
        window.addLast(Triple(now, frames, dropped))
        while (window.size > 1 && now - window.first().first > ctx.config.judderWindow) window.removeFirst()
        val ratio = lossRatio(now, ctx) ?: return null
        fired = true
        return Finding(Symptom.JUDDER, "${(ratio * PERCENT).toInt()} % verworfene Bilder")
    }

    /** Anteil verworfener Bilder im Fenster, falls genug Daten da sind und er über der Grenze liegt. */
    private fun lossRatio(now: Long, ctx: DetectContext): Double? {
        val (startAt, startFrames, startDropped) = window.first()
        val (_, frames, dropped) = window.last()
        val lost = dropped - startDropped
        val total = frames - startFrames + lost
        val enoughData = now - startAt >= ctx.config.judderWindow / 2 && total >= ctx.config.judderMinFrames && lost > 0
        return (lost.toDouble() / total).takeIf { enoughData && it >= ctx.config.judderRatio }
    }

    override fun reset() {
        window.clear()
    }

    fun resetSession() {
        reset()
        fired = false
    }
}

/** S9: Live-Wiedergabe fällt immer weiter hinter den Live-Rand zurück (Liste steht, Player hängt hinterher). */
class LiveDriftDetector : Detector {
    private val sustained = Sustained()

    override fun check(s: HealthSnapshot, now: Long, ctx: DetectContext): Finding? {
        val offset = s.liveOffsetMs ?: 0L
        val active = ctx.session.isLive && s.isPlaying && offset >= ctx.config.liveDriftThreshold
        sustained.update(active, now, ctx.config.liveDriftDuration) ?: return null
        return Finding(Symptom.LIVE_DRIFT, "${offset / MS_PER_S} s hinter dem Live-Rand")
    }

    override fun reset() = sustained.reset()

    private companion object {
        const val MS_PER_S = 1_000L
    }
}

/** Alle Detektoren des Wächters. Die Start-Erkennung beginnt erst bei onPrepared neu, die übrigen nach jeder Maßnahme. */
internal class DetectorSet {
    private val start = StartDetector()
    private val av = AvPresenceDetector()
    private val judder = JudderDetector()
    private val followUp: List<Detector> = listOf(StallDetector(), FrozenPictureDetector(), av, DesyncDetector(), judder, LiveDriftDetector())

    /** Neuer Inhalt: auch die „einmal je Inhalt“-Hinweise wieder erlauben. */
    fun resetSession() {
        av.resetSession()
        judder.resetSession()
    }

    fun resetAll() {
        start.reset()
        resetFollowUp()
    }

    fun resetFollowUp() = followUp.forEach(Detector::reset)

    /** Wichtigster Befund: informative (Radio, stumm, Ruckeln) nie vor echten Störungen. */
    fun check(health: HealthSnapshot, now: Long, ctx: DetectContext): Finding? = buildList {
        start.check(health, now, ctx)?.let(::add)
        followUp.forEach { detector -> detector.check(health, now, ctx)?.let(::add) }
    }.minByOrNull { if (it.symptom.informational) 1 else 0 }
}
