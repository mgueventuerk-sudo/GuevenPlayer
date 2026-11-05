package com.mg.player.domain.model

import androidx.compose.runtime.Immutable

enum class PlayerEngineType(val label: String) {
    MEDIA3("ExoPlayer (Media3)"),
    LIBVLC("LibVLC"),
    NATIVE("System-Player"),
    EXTERNAL("Externer Player"),
}

/** Reihenfolge innerhalb der Gruppen (Sender, Filme, Serien), ab 3.44. */
enum class GroupSort(val label: String) {
    /** „Alle Zugänge“ alphabetisch (dort mischen sich mehrere Anbieter), einzelne Zugänge wie beim Anbieter. */
    AUTO("Automatisch"),
    PROVIDER("Wie beim Anbieter"),
    ALPHABETICAL("A–Z"),
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

/**
 * Live-Puffer (ab 3.51): Abstand zum Live-Rand, den der Player nach dem Start langsam aufbaut (kaum hörbar 5 %
 * langsamer, bis er erreicht ist). Kurze Aussetzer des Anbieters fängt er ab, bevor sie als Hänger sichtbar werden.
 * Preis: Das Bild läuft so viele Sekunden hinter dem Live-Signal.
 */
enum class LiveCushion(val label: String, val ms: Long) {
    OFF("Aus", 0),
    S4("4 Sekunden", CUSHION_SHORT_MS),
    S8("8 Sekunden", CUSHION_LONG_MS),
}

private const val CUSHION_SHORT_MS = 4_000L
private const val CUSHION_LONG_MS = 8_000L

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
    val liveCushion: LiveCushion = LiveCushion.S4,
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
    /** GitHub-Zugriffsschlüssel (nur Lesen, nur das Player-Repo) für App-Updates; verschlüsselt, nie im Backup. */
    val updateToken: String = "",
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
    /** Zuletzt gesuchte Begriffe (neueste zuerst, nur auf dem Gerät), siehe Suche. */
    val recentSearches: List<String> = emptyList(),
    /** Reihenfolge in den Gruppen, siehe [GroupSort]. */
    val groupSort: GroupSort = GroupSort.AUTO,
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
    /** Abgeschaltete Quellen der Sender-Zuordnung (Namen von MatchSource), siehe Einstellungen → Sport. */
    val sportSourcesOff: Set<String> = emptySet(),
    /** Dieses Gerät sucht im Hintergrund Beweise (Plan „Spiel-Finder“ N/O: nur auf einem Prüfgerät, z. B. Fire TV Cube). */
    val sportProofDevice: Boolean = false,
    /** Code dieses Prüfgeräts für die anderen Geräte im Heimnetz (8 Ziffern, beim Einschalten erzeugt). */
    val sportHubCode: String = "",
    /** Code des Prüfgeräts, eingetragen auf Stick/Handy (leer = keine Beweise vom Prüfgerät holen). */
    val sportHubJoinCode: String = "",
    /** Länder, deren Sender für Sport zählen (ISO, z. B. „DE“); Senderlisten von Bzzoiro je Land. */
    val sportCountries: Set<String> = DEFAULT_SPORT_COUNTRIES,
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

/** Voreinstellung der Sport-Länder (Deutschland, Österreich, Schweiz, Türkei). */
val DEFAULT_SPORT_COUNTRIES: Set<String> = setOf("DE", "AT", "CH", "TR")
