package com.mg.player.player.engine

/**
 * Momentaufnahme der Wiedergabe, einheitlich für alle Engines. `null` heißt: Diese Engine liefert den Wert nicht –
 * die zugehörige Erkennung ist dann aus, statt zu raten.
 */
data class HealthSnapshot(
    val status: PlaybackStatus,
    val isPlaying: Boolean,
    val positionMs: Long,
    /** Seit dem letzten Start geladene Bytes. */
    val bytesLoaded: Long? = null,
    /** Seit dem letzten Start angezeigte Videobilder. */
    val renderedFrames: Long? = null,
    /** Zähler der ausgegebenen Tonblöcke seit dem letzten Start (steigt, solange Ton läuft). */
    val audioUnits: Long? = null,
    val hasVideoTrack: Boolean? = null,
    val hasAudioTrack: Boolean? = null,
    /** Bisherige Ton-Ausgabe-/Decoderfehler (steigend). */
    val audioErrors: Int = 0,
    /** Bisherige Video-Decoderfehler (steigend). */
    val videoErrors: Int = 0,
    val droppedFrames: Long? = null,
    /** Abstand Ton zu Bild; positiv = Ton voraus. */
    val avOffsetMs: Long? = null,
    /** Abstand zum Live-Rand. */
    val liveOffsetMs: Long? = null,
    /** Vom System-Player gemeldete Ereignisse „Video track lagging“ (steigend). */
    val videoLagEvents: Int? = null,
    /**
     * Wurde seit dem letzten Start mindestens ein Videobild gezeigt? Funktioniert auch dort, wo der Bilderzähler fehlt
     * (Tunneling bei Media3). null = Engine meldet das nicht.
     */
    val pictureShown: Boolean? = null,
)
