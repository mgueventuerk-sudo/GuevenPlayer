package com.mg.player.player.supervisor

import com.mg.player.player.engine.HealthSnapshot
import com.mg.player.player.engine.PlaybackStatus

/**
 * Misst, ob Position, Bild und Ton wirklich vorankommen. READY allein reicht nicht: Bei einem Standbild bleibt der Status
 * READY. Rein (keine Uhr, keine Android-Abhängigkeit) – die Zeit kommt als Parameter.
 */
internal class StableProgress(private val config: SupervisorConfig) {
    private var positionAt = -1L
    private var frameAt = -1L
    private var audioAt = -1L
    private var lastPosition: Long? = null
    private var lastFrames: Long? = null
    private var lastAudio: Long? = null

    fun reset() {
        positionAt = -1L
        frameAt = -1L
        audioAt = -1L
        lastPosition = null
        lastFrames = null
        lastAudio = null
    }

    /**
     * true = alles, was zu erwarten ist, läuft gerade. [needsPicture]: Der Sender soll ein Bild haben – dann zählt „keine
     * Videospur“ bzw. „noch kein Bild gezeigt“ nicht als stabil. Ohne diese Prüfung galt „keine Videospur“ nach 3 s als
     * „behoben“ (Ton und Position liefen ja): Der Wächter zählte einen Erfolg, und nach dem Aufgeben schaltete er sich nach
     * 30 s wieder scharf und verband alle 45 s von Neuem (3.58).
     */
    fun check(health: HealthSnapshot, session: SessionInfo, needsPicture: Boolean, now: Long): Boolean {
        if (health.status != PlaybackStatus.READY || !health.isPlaying) {
            reset()
            return false
        }
        val positionLimit = when {
            session.isLive && session.canSwitchVariant -> config.stallWithAlternativeMax
            session.isLive -> config.stallLive
            else -> config.stallVod
        }
        val position = positionProgress(health.positionMs, now, positionLimit)
        val video = !(needsPicture && pictureMissing(health)) && videoProgress(health, now)
        val audio = audioProgress(health, now)
        return position && video && audio
    }

    private fun pictureMissing(health: HealthSnapshot): Boolean {
        val noTrack = health.hasVideoTrack == false
        val noFirstFrame = health.hasVideoTrack == true && health.renderedFrames == null && health.pictureShown == false
        return noTrack || noFirstFrame
    }

    private fun positionProgress(positionMs: Long, now: Long, limitMs: Long): Boolean {
        if (lastPosition != null && positionMs != lastPosition) positionAt = now
        lastPosition = positionMs
        return positionAt >= 0 && now - positionAt <= limitMs
    }

    private fun videoProgress(health: HealthSnapshot, now: Long): Boolean {
        val frames = health.renderedFrames?.takeIf { health.hasVideoTrack != false } ?: return true
        if (lastFrames != null && frames != lastFrames) frameAt = now
        lastFrames = frames
        return frameAt >= 0 && now - frameAt <= config.frozenPicture
    }

    private fun audioProgress(health: HealthSnapshot, now: Long): Boolean {
        val audio = health.audioUnits?.takeIf { health.hasAudioTrack != false } ?: return true
        if (lastAudio != null && audio != lastAudio) audioAt = now
        lastAudio = audio
        return audioAt >= 0 && now - audioAt <= config.frozenPicture
    }
}

/**
 * Was der Wächter über „muss dieser Sender ein Bild haben?“ weiß – aus dem Gedächtnis, dem Namen und dem, was in dieser
 * Sitzung passiert ist. Entscheidet, ob „keine Videospur“ ein Radiosender oder eine Störung ist ([PictureExpectation]).
 */
internal class PictureKnowledge(private val memory: RecoveryMemory) {
    var expectation = PictureExpectation.UNKNOWN
        private set

    /** Wie oft in dieser Sitzung nur vermutet („vielleicht Radio“) und trotzdem eingegriffen wurde. */
    var probes = 0
        private set
    private var noted = false

    fun start(session: SessionInfo) {
        probes = 0
        noted = false
        expectation = when {
            memory.hadPicture(session.contentKey) -> PictureExpectation.TV
            session.radioByName || memory.isRadio(session.contentKey) -> PictureExpectation.RADIO
            else -> PictureExpectation.UNKNOWN
        }
    }

    fun probed() {
        probes++
    }

    fun resetProbes() {
        probes = 0
    }

    /** Der Sender soll ein Bild haben, wenn er schon einmal eins hatte oder ein Bild-Fehler behandelt wird. */
    fun needsPicture(activeProblem: Symptom?): Boolean = expectation == PictureExpectation.TV || activeProblem in NO_PICTURE_SYMPTOMS

    /** Stabiles Bild eines Live-Senders: merken – fehlt es später, ist das eine Störung (auch bei „Radio“ im Namen). */
    fun noteStablePicture(session: SessionInfo, health: HealthSnapshot) {
        val proven = health.hasVideoTrack == true && (health.renderedFrames != null || health.pictureShown == true)
        if (noted || !session.isLive || !proven) return
        noted = true
        memory.markHadPicture(session.contentKey)
        expectation = PictureExpectation.TV
    }

    fun rememberRadio(contentKey: String) {
        memory.markRadio(contentKey)
        expectation = PictureExpectation.RADIO
    }

    companion object {
        /** Bei diesen Symptomen gilt „Bild fehlt“: Erholung erst, wenn der Player wirklich ein Bild zeigt. */
        val NO_PICTURE_SYMPTOMS = setOf(Symptom.AUDIO_WITHOUT_VIDEO, Symptom.NO_VIDEO_TRACK)
    }
}
