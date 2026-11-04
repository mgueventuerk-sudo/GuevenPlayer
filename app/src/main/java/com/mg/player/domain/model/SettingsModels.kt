package com.mg.player.domain.model

import androidx.compose.runtime.Immutable

enum class PlayerEngineType(val label: String) {
    MEDIA3("ExoPlayer (Media3)"),
    LIBVLC("LibVLC"),
    NATIVE("System-Player"),
    EXTERNAL("Externer Player"),
}

enum class IntroSkipMode(val label: String) {
    BUTTON("Button einblenden"),
    AUTO("Vollautomatisch"),
    OFF("Aus"),
}

enum class BufferProfile(val label: String, val startBufferMs: Int) {
    FAST("Schnell (1 s)", 1_000),
    STANDARD("Standard (1,5 s)", 1_500),
    STABLE("Stabil (3 s)", 3_000),
}

enum class HwDecoding(val label: String) {
    AUTO("Automatisch"),
    ON("An"),
    OFF("Aus"),
}

/** Hell oder dunkel. Automatisch = tagsüber hell, abends und nachts dunkel (am Fernseher fehlt meist ein System-Schalter). */
enum class ThemeMode(val label: String) {
    AUTO("Automatisch"),
    DARK("Dunkel"),
    LIGHT("Hell"),
}

/** Akzentfarbe für Auswahl, Fortschritt und Markenzeichen. */
enum class AccentColor(val label: String) {
    BLUE("Blau"),
    VIOLET("Violett"),
    TEAL("Türkis"),
    ORANGE("Orange"),
    ROSE("Rosé"),
    BRAND("Gelb-Rot"),
}

@Immutable
data class AppSettings(
    /** Verbirgt ausschließlich die Guide-Oberfläche; EPG-Import und gespeicherte Programmdaten bleiben aktiv. */
    val hideGuide: Boolean = false,
    /** Automatische, geräteklassengerechte Wiedergabeparameter; manuelle Werte bleiben beim Ausschalten erhalten. */
    val automaticOptimization: Boolean = true,
    val liveEngine: PlayerEngineType = PlayerEngineType.MEDIA3,
    val vodEngine: PlayerEngineType = PlayerEngineType.MEDIA3,
    val introSkipMode: IntroSkipMode = IntroSkipMode.BUTTON,
    val introSkipSeconds: Int = 85,
    val autoPlayNext: Boolean = true,
    val nextEpisodeTriggerSeconds: Int = 30,
    val bufferProfile: BufferProfile = BufferProfile.STANDARD,
    val hwDecoding: HwDecoding = HwDecoding.AUTO,
    val autoFrameRate: Boolean = true,
    val startWithLastChannel: Boolean = false,
    val autoUpdateOnStart: Boolean = false,
    /**
     * Frühere gemeinsame EPG-Zeitverschiebung (bis 3.31). Seit 3.32 gilt sie je Zugang; ein Wert ≠ 0 wird beim Start
     * einmal auf alle Zugänge übertragen und dann auf 0 gesetzt (EpgShifts.adoptLegacy).
     */
    val epgShiftHours: Int = 0,
    val activePlaylistId: Long? = null,
    val lastChannelId: String? = null,
    val lastChannelCategoryId: String? = null,
    val parentalPinHash: String? = null,
    val tmdbApiKey: String = "",
    val tmdbEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val accent: AccentColor = AccentColor.BLUE,
    /**
     * Hardware-Tunneling im ExoPlayer: entlastet die CPU auf TV-Sticks. Standard aus – auf Fire-TV-Geräten
     * blieb damit bei vielen Sendern der Ton stumm.
     */
    val tunneling: Boolean = false,
    /** Dolby/DTS unverändert an TV bzw. AV-Receiver durchreichen (sonst dekodiert das Gerät den Ton selbst). */
    val audioPassthrough: Boolean = false,
    /**
     * Wiedergabe-Wächter: erkennt Hänger, Standbild, Ton ohne Bild, Bild ohne Ton usw. und steuert gestuft gegen.
     * Aus = bisherige Fehlerbehandlung (Rückweg bei Problemen).
     */
    val playbackSupervisor: Boolean = true,
    /** Zuletzt gewählte Seite der Suche: null = „Alle Playlists“, sonst die ID der Playlist. */
    val searchPagePlaylistId: Long? = null,
    /** Ligen, denen man in der Sport-Sektion folgt; null = Voreinstellung (deutsche Ligen und Europapokal). */
    val sportLeagues: Set<String>? = null,
    /** Eigener Sportmonks-Schlüssel (verschlüsselt gespeichert); leer = nur ESPN. */
    val sportmonksToken: String = "",
    /** Eigener Schlüssel für Bzzoiro Sports Data (sports.bzzoiro.com, verschlüsselt gespeichert); leer = nicht genutzt. */
    val bzzoiroToken: String = "",
    /** Von Hand zugeordnete Sender je Liga (Liga-Schlüssel → Sender-ID), wenn das EPG die Spiele nicht nennt. */
    val sportManualChannels: Map<String, String> = emptyMap(),
    /** Sport-Ergebnisse und Film-Infos (TMDB) aus dem Internet; aus = keine Anfragen an diese Dienste. */
    val onlineExtras: Boolean = true,
    /** Öffentliches Sport-Programm der Sender (epgshare01) laden, um die Sender der Spiele zu finden. */
    val sportGuide: Boolean = true,
    /** Verschlüsseltes DNS (DoH), solange das VPN nicht verbunden ist. */
    val dohFallback: Boolean = true,
    /** Ohne Verlauf: kein „Zuletzt gesehen“, kein Fortschritt, keine Aktivitäten im Protokoll. */
    val noHistory: Boolean = false,
    /** App-Sperre: PIN beim Öffnen, Inhalt in „Zuletzt verwendet“ verdeckt. */
    val appLock: Boolean = false,
    /** Größere Schrift für den Fernseher aus der Entfernung. */
    val largeText: Boolean = false,
    /** Ohne Glas-Verläufe und Hintergrundbilder: ruhiger und schneller auf schwachen TV-Sticks. */
    val reducedEffects: Boolean = false,
) {
    val tmdbActive: Boolean get() = onlineExtras && tmdbEnabled && tmdbApiKey.isNotBlank()

    companion object {
        val INTRO_SKIP_OPTIONS = listOf(60, 85, 90)
        val NEXT_TRIGGER_OPTIONS = listOf(30, 45, 60)
    }
}

/** Laufzeitwerte für automatische Leistungsanpassung; Sicherheitsoptionen werden davon nie verändert. */
fun AppSettings.forAutomaticOptimization(tier: com.mg.player.core.device.DeviceTier): AppSettings =
    if (!automaticOptimization) this else copy(
        liveEngine = PlayerEngineType.MEDIA3,
        vodEngine = PlayerEngineType.MEDIA3,
        bufferProfile = if (tier == com.mg.player.core.device.DeviceTier.LOW) BufferProfile.STANDARD else BufferProfile.STABLE,
        hwDecoding = HwDecoding.AUTO,
        tunneling = false,
        audioPassthrough = false,
        playbackSupervisor = true,
    )
