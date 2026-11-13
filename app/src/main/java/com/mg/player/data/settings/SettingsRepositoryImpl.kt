package com.mg.player.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mg.player.core.log.AppLog
import com.mg.player.core.security.SecretCipher
import com.mg.player.domain.model.AppSettings
import com.mg.player.domain.repository.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Beschädigte Einstellungsdatei (z. B. Strom weg beim Schreiben): auf Standardwerte zurücksetzen. Ohne den Handler
 * warf jeder Zugriff eine CorruptionException – die App stürzte dann bei jedem Start ab.
 */
internal val SETTINGS_CORRUPTION_HANDLER = ReplaceFileCorruptionHandler {
    SettingsCorruption.mark()
    emptyPreferences()
}

/** Merkt sich, ob die Einstellungsdatei in diesem Lauf beschädigt war und zurückgesetzt wurde. */
object SettingsCorruption {
    private val happened = AtomicBoolean(false)

    fun mark() {
        happened.set(true)
        AppLog.log("Einstellungen", "Einstellungsdatei beschädigt – zurückgesetzt")
    }

    /** true genau einmal nach einem Zurücksetzen. */
    fun consume(): Boolean = happened.getAndSet(false)
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = SETTINGS_CORRUPTION_HANDLER,
)

class SettingsRepositoryImpl internal constructor(private val store: DataStore<Preferences>) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.settingsStore)

    private object Keys {
        val LIVE_ENGINE = stringPreferencesKey("live_engine")

        // v2: Filme & Serien starten standardmäßig mit ExoPlayer (schneller Start, LibVLC als automatischer Fallback).
        // Der neue Schlüssel setzt die frühere Voreinstellung LibVLC einmalig zurück.
        val VOD_ENGINE = stringPreferencesKey("vod_engine_v2")
        val INTRO_MODE = stringPreferencesKey("intro_mode")
        val INTRO_SECONDS = intPreferencesKey("intro_seconds")
        val AUTO_NEXT = booleanPreferencesKey("auto_next")
        val NEXT_TRIGGER = intPreferencesKey("next_trigger")
        val BUFFER = stringPreferencesKey("buffer")
        val LIVE_CUSHION = stringPreferencesKey("live_cushion")
        val HW = stringPreferencesKey("hw_decoding")
        val AFR = booleanPreferencesKey("auto_frame_rate")
        val START_LAST = booleanPreferencesKey("start_last_channel")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val EPG_SHIFT = intPreferencesKey("epg_shift")
        val ACTIVE_PLAYLIST = longPreferencesKey("active_playlist")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val LAST_CHANNEL_CATEGORY = stringPreferencesKey("last_channel_category")
        val PIN_HASH = stringPreferencesKey("parental_pin_hash")
        val TMDB_KEY = stringPreferencesKey("tmdb_api_key")
        val UPDATE_TOKEN = stringPreferencesKey("update_token")
        val TMDB_ENABLED = booleanPreferencesKey("tmdb_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")

        // v2: Standard ist jetzt "aus" – der alte Wert (früher immer mitgespeichert) wird nicht übernommen.
        val TUNNELING = booleanPreferencesKey("tunneling_v2")
        val SUPERVISOR = booleanPreferencesKey("playback_supervisor")
        val PASSTHROUGH = booleanPreferencesKey("audio_passthrough")
        val SEARCH_PAGE = longPreferencesKey("search_page_playlist")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val GROUP_SORT = stringPreferencesKey("group_sort")
        val SPORT_LEAGUES = stringSetPreferencesKey("sport_leagues")
        val SPORTMONKS_TOKEN = stringPreferencesKey("sportmonks_token")
        val BZZOIRO_TOKEN = stringPreferencesKey("bzzoiro_token")
        val SPORT_MANUAL = stringSetPreferencesKey("sport_manual")
        val ONLINE_EXTRAS = booleanPreferencesKey("online_extras")
        val SPORT_GUIDE = booleanPreferencesKey("sport_guide")
        val SPORT_SOURCES_OFF = stringSetPreferencesKey("sport_sources_off")
        val SPORT_COUNTRIES = stringSetPreferencesKey("sport_countries")
        val SPORT_PROOF_DEVICE = booleanPreferencesKey("sport_proof_device")
        val SPORT_HUB_CODE = stringPreferencesKey("sport_hub_code")
        val SPORT_HUB_JOIN = stringPreferencesKey("sport_hub_join")
        val DOH = booleanPreferencesKey("doh_fallback")
        val NO_HISTORY = booleanPreferencesKey("no_history")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val TV_HANDOFF = booleanPreferencesKey("tv_handoff")
        val COLD_STARTS = intPreferencesKey("cold_starts")
        val LARGE_TEXT = booleanPreferencesKey("large_text")
        val REDUCED_EFFECTS = booleanPreferencesKey("reduced_effects")
        val PRELOAD = booleanPreferencesKey("preload")
        val PRELOAD_DISK_ONLY = booleanPreferencesKey("preloadDiskOnly")
        val AUTOMATIC_OPTIMIZATION = booleanPreferencesKey("automatic_optimization")
        val HIDE_GUIDE = booleanPreferencesKey("hide_guide")
        val AUTO_BACKUP_FOLDER = stringPreferencesKey("auto_backup_folder")
        val AUTO_BACKUP_PASSPHRASE = stringPreferencesKey("auto_backup_passphrase")
    }

    override val settings: Flow<AppSettings> = flow {
        migratePlaintextTmdbKey()
        migratePlaintextSportHubSecrets()
        emitAll(store.data.map { it.toSettings() })
    }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            hideGuide = this[Keys.HIDE_GUIDE] ?: defaults.hideGuide,
            liveEngine = enumOr(this[Keys.LIVE_ENGINE], defaults.liveEngine),
            vodEngine = enumOr(this[Keys.VOD_ENGINE], defaults.vodEngine),
            introSkipMode = enumOr(this[Keys.INTRO_MODE], defaults.introSkipMode),
            introSkipSeconds = this[Keys.INTRO_SECONDS] ?: defaults.introSkipSeconds,
            autoPlayNext = this[Keys.AUTO_NEXT] ?: defaults.autoPlayNext,
            nextEpisodeTriggerSeconds = this[Keys.NEXT_TRIGGER] ?: defaults.nextEpisodeTriggerSeconds,
            bufferProfile = enumOr(this[Keys.BUFFER], defaults.bufferProfile),
            liveCushion = enumOr(this[Keys.LIVE_CUSHION], defaults.liveCushion),
            hwDecoding = enumOr(this[Keys.HW], defaults.hwDecoding),
            autoFrameRate = this[Keys.AFR] ?: defaults.autoFrameRate,
            startWithLastChannel = this[Keys.START_LAST] ?: defaults.startWithLastChannel,
            autoUpdateOnStart = this[Keys.AUTO_UPDATE] ?: defaults.autoUpdateOnStart,
            epgShiftHours = this[Keys.EPG_SHIFT] ?: defaults.epgShiftHours,
            activePlaylistId = this[Keys.ACTIVE_PLAYLIST],
            lastChannelId = this[Keys.LAST_CHANNEL],
            lastChannelCategoryId = this[Keys.LAST_CHANNEL_CATEGORY],
            parentalPinHash = this[Keys.PIN_HASH],
            updateToken = this[Keys.UPDATE_TOKEN]?.let(SecretCipher::decrypt).orEmpty(),
            tmdbApiKey = this[Keys.TMDB_KEY]?.let(SecretCipher::decrypt).orEmpty().ifBlank { defaults.tmdbApiKey },
            tmdbEnabled = this[Keys.TMDB_ENABLED] ?: defaults.tmdbEnabled,
            themeMode = enumOr(this[Keys.THEME_MODE], defaults.themeMode),
            accent = enumOr(this[Keys.ACCENT], defaults.accent),
            tunneling = this[Keys.TUNNELING] ?: defaults.tunneling,
            audioPassthrough = this[Keys.PASSTHROUGH] ?: defaults.audioPassthrough,
            playbackSupervisor = this[Keys.SUPERVISOR] ?: defaults.playbackSupervisor,
            searchPagePlaylistId = this[Keys.SEARCH_PAGE],
            recentSearches = this[Keys.RECENT_SEARCHES]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            groupSort = enumOr(this[Keys.GROUP_SORT], defaults.groupSort),
            sportLeagues = this[Keys.SPORT_LEAGUES],
            sportmonksToken = this[Keys.SPORTMONKS_TOKEN]?.let(SecretCipher::decrypt).orEmpty(),
            bzzoiroToken = this[Keys.BZZOIRO_TOKEN]?.let(SecretCipher::decrypt).orEmpty(),
            sportManualChannels = this[Keys.SPORT_MANUAL].orEmpty().mapNotNull { entry ->
                entry.split('\t').takeIf { it.size == 2 }?.let { (league, channel) -> league to channel }
            }.toMap(),
            onlineExtras = this[Keys.ONLINE_EXTRAS] ?: defaults.onlineExtras,
            sportGuide = this[Keys.SPORT_GUIDE] ?: defaults.sportGuide,
            sportSourcesOff = this[Keys.SPORT_SOURCES_OFF] ?: defaults.sportSourcesOff,
            sportProofDevice = this[Keys.SPORT_PROOF_DEVICE] ?: defaults.sportProofDevice,
            sportHubCode = this[Keys.SPORT_HUB_CODE]?.let(SecretCipher::decrypt) ?: defaults.sportHubCode,
            sportHubJoinCode = this[Keys.SPORT_HUB_JOIN]?.let(SecretCipher::decrypt) ?: defaults.sportHubJoinCode,
            sportCountries = this[Keys.SPORT_COUNTRIES]?.takeIf { it.isNotEmpty() } ?: defaults.sportCountries,
            dohFallback = this[Keys.DOH] ?: defaults.dohFallback,
            noHistory = this[Keys.NO_HISTORY] ?: defaults.noHistory,
            appLock = this[Keys.APP_LOCK] ?: defaults.appLock,
            tvHandoff = this[Keys.TV_HANDOFF] ?: defaults.tvHandoff,
            coldStarts = this[Keys.COLD_STARTS] ?: defaults.coldStarts,
            largeText = this[Keys.LARGE_TEXT] ?: defaults.largeText,
            reducedEffects = this[Keys.REDUCED_EFFECTS] ?: defaults.reducedEffects,
            preload = this[Keys.PRELOAD] ?: defaults.preload,
            preloadDiskOnly = this[Keys.PRELOAD_DISK_ONLY] ?: defaults.preloadDiskOnly,
            automaticOptimization = this[Keys.AUTOMATIC_OPTIMIZATION] ?: defaults.automaticOptimization,
            autoBackupFolderUri = this[Keys.AUTO_BACKUP_FOLDER],
            autoBackupPassphrase = this[Keys.AUTO_BACKUP_PASSPHRASE]?.let(SecretCipher::decrypt).orEmpty(),
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.HIDE_GUIDE] = s.hideGuide
        this[Keys.LIVE_ENGINE] = s.liveEngine.name
        this[Keys.VOD_ENGINE] = s.vodEngine.name
        this[Keys.INTRO_MODE] = s.introSkipMode.name
        this[Keys.INTRO_SECONDS] = s.introSkipSeconds
        this[Keys.AUTO_NEXT] = s.autoPlayNext
        this[Keys.NEXT_TRIGGER] = s.nextEpisodeTriggerSeconds
        this[Keys.BUFFER] = s.bufferProfile.name
        this[Keys.LIVE_CUSHION] = s.liveCushion.name
        this[Keys.HW] = s.hwDecoding.name
        this[Keys.AFR] = s.autoFrameRate
        this[Keys.START_LAST] = s.startWithLastChannel
        this[Keys.AUTO_UPDATE] = s.autoUpdateOnStart
        this[Keys.EPG_SHIFT] = s.epgShiftHours
        s.activePlaylistId?.let { this[Keys.ACTIVE_PLAYLIST] = it } ?: remove(Keys.ACTIVE_PLAYLIST)
        s.lastChannelId?.let { this[Keys.LAST_CHANNEL] = it } ?: remove(Keys.LAST_CHANNEL)
        s.lastChannelCategoryId?.let { this[Keys.LAST_CHANNEL_CATEGORY] = it } ?: remove(Keys.LAST_CHANNEL_CATEGORY)
        s.parentalPinHash?.let { this[Keys.PIN_HASH] = it } ?: remove(Keys.PIN_HASH)
        this[Keys.AUTOMATIC_OPTIMIZATION] = s.automaticOptimization
        this[Keys.TMDB_ENABLED] = s.tmdbEnabled
        this[Keys.THEME_MODE] = s.themeMode.name
        this[Keys.ACCENT] = s.accent.name
        this[Keys.TUNNELING] = s.tunneling
        this[Keys.PASSTHROUGH] = s.audioPassthrough
        this[Keys.SUPERVISOR] = s.playbackSupervisor
        s.searchPagePlaylistId?.let { this[Keys.SEARCH_PAGE] = it } ?: remove(Keys.SEARCH_PAGE)
        if (s.recentSearches.isEmpty()) remove(Keys.RECENT_SEARCHES) else this[Keys.RECENT_SEARCHES] = s.recentSearches.joinToString("\n")
        this[Keys.GROUP_SORT] = s.groupSort.name
        s.sportLeagues?.let { this[Keys.SPORT_LEAGUES] = it } ?: remove(Keys.SPORT_LEAGUES)
        this[Keys.ONLINE_EXTRAS] = s.onlineExtras
        this[Keys.SPORT_GUIDE] = s.sportGuide
        this[Keys.SPORT_SOURCES_OFF] = s.sportSourcesOff
        this[Keys.SPORT_PROOF_DEVICE] = s.sportProofDevice
        this[Keys.SPORT_COUNTRIES] = s.sportCountries
        this[Keys.DOH] = s.dohFallback
        this[Keys.NO_HISTORY] = s.noHistory
        this[Keys.APP_LOCK] = s.appLock
        this[Keys.TV_HANDOFF] = s.tvHandoff
        this[Keys.COLD_STARTS] = s.coldStarts
        this[Keys.LARGE_TEXT] = s.largeText
        this[Keys.REDUCED_EFFECTS] = s.reducedEffects
        this[Keys.PRELOAD] = s.preload
        this[Keys.PRELOAD_DISK_ONLY] = s.preloadDiskOnly
        this[Keys.SPORT_MANUAL] = s.sportManualChannels.mapTo(HashSet()) { (league, channel) -> "$league\t$channel" }
        putOrRemove(Keys.AUTO_BACKUP_FOLDER, s.autoBackupFolderUri)
        // Schlüssel nur verschlüsselt speichern; geht das nicht, lieber gar nicht (nie Klartext).
        putSecret(Keys.SPORTMONKS_TOKEN, s.sportmonksToken)
        putSecret(Keys.BZZOIRO_TOKEN, s.bzzoiroToken)
        putSecret(Keys.TMDB_KEY, s.tmdbApiKey)
        putSecret(Keys.UPDATE_TOKEN, s.updateToken)
        putSecret(Keys.AUTO_BACKUP_PASSPHRASE, s.autoBackupPassphrase)
        // Pairing-Geheimnis für Spiel-Finder-Austausch und TV-Handoff (HubAuth): wie die Zugriffsschlüssel oben
        // verschlüsselt speichern, nicht im Klartext der DataStore-Datei – wer es lesen kann, gibt sich im
        // Heimnetz als Prüfgerät oder Fernseher aus.
        putSecret(Keys.SPORT_HUB_CODE, s.sportHubCode)
        putSecret(Keys.SPORT_HUB_JOIN, s.sportHubJoinCode)
    }

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        value?.let { this[key] = it } ?: remove(key)
    }

    private fun MutablePreferences.putSecret(key: Preferences.Key<String>, value: String) {
        val token = value.trim()
        if (token.isEmpty()) {
            remove(key)
        } else if (this[key]?.let(SecretCipher::decrypt) != token) {
            runCatching { SecretCipher.encrypt(token) }.onSuccess { this[key] = it }.onFailure { remove(key) }
        }
    }

    private suspend fun migratePlaintextTmdbKey() {
        store.edit { prefs ->
            val stored = prefs[Keys.TMDB_KEY]?.takeIf { it.isNotBlank() } ?: return@edit
            if (stored.startsWith("enc1:") || stored.startsWith("enc0:")) return@edit
            runCatching { SecretCipher.encrypt(stored) }
                .onSuccess { prefs[Keys.TMDB_KEY] = it }
                .onFailure { prefs.remove(Keys.TMDB_KEY) }
        }
    }

    /** Vor diesem Fix im Klartext gespeichert (Pairing-Geheimnis für Spiel-Finder/TV-Handoff): einmalig nachrüsten. */
    private suspend fun migratePlaintextSportHubSecrets() {
        store.edit { prefs ->
            migratePlaintextSecretIfNeeded(prefs, Keys.SPORT_HUB_CODE)
            migratePlaintextSecretIfNeeded(prefs, Keys.SPORT_HUB_JOIN)
        }
    }

    private fun migratePlaintextSecretIfNeeded(prefs: MutablePreferences, key: Preferences.Key<String>) {
        val stored = prefs[key]?.takeIf { it.isNotBlank() && !it.startsWith("enc1:") && !it.startsWith("enc0:") } ?: return
        runCatching { SecretCipher.encrypt(stored) }
            .onSuccess { prefs[key] = it }
            .onFailure { prefs.remove(key) }
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T = value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default
}
