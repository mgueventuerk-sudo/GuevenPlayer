package com.mg.player.data.backup

import androidx.room.withTransaction
import com.mg.player.core.log.AppLog
import com.mg.player.core.security.SecretCipher
import com.mg.player.core.vpn.VpnImporter
import com.mg.player.data.db.AppDatabase
import com.mg.player.domain.repository.PlaylistRepository
import com.mg.player.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sicherung als JSON: Playlists (mit Zugangsdaten), VPN-Standorte, Einstellungen, Favoriten, Fortschritt,
 * gelernte Intro-Zeiten und Kategorie-Einstellungen. Inhalte (Sender, Filme) werden nach dem
 * Wiederherstellen einfach neu geladen.
 */
class BackupManager(
    private val db: AppDatabase,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    /** VPN-Standorte (nur in der verschlüsselten Sicherung). */
    private val vpn: VpnImporter? = null,
) {

    /** Sicherung als verschlüsseltes JSON (Kennwort mindestens [BackupCrypto.MIN_PASSPHRASE] Zeichen). */
    suspend fun export(passphrase: String): String = withContext(Dispatchers.Default) {
        BackupCrypto.encrypt(exportPlain(), passphrase)
    }

    private suspend fun exportPlain(): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("createdAt", System.currentTimeMillis())
        root.put(
            "settings",
            JSONObject().apply {
                put("liveEngine", s.liveEngine.name)
                put("vodEngine", s.vodEngine.name)
                put("introSkipMode", s.introSkipMode.name)
                put("introSkipSeconds", s.introSkipSeconds)
                put("autoPlayNext", s.autoPlayNext)
                put("nextEpisodeTriggerSeconds", s.nextEpisodeTriggerSeconds)
                put("bufferProfile", s.bufferProfile.name)
                put("liveCushion", s.liveCushion.name)
                put("hwDecoding", s.hwDecoding.name)
                put("autoFrameRate", s.autoFrameRate)
                put("tunneling_v2", s.tunneling)
                put("audioPassthrough", s.audioPassthrough)
                put("playbackSupervisor", s.playbackSupervisor)
                put("themeMode", s.themeMode.name)
                put("accent", s.accent.name)
                put("startWithLastChannel", s.startWithLastChannel)
                put("autoUpdateOnStart", s.autoUpdateOnStart)
                put("tmdbApiKey", s.tmdbApiKey)
                put("tmdbEnabled", s.tmdbEnabled)
                put("activePlaylistId", s.activePlaylistId ?: -1L)
                put("parentalPinHash", s.parentalPinHash ?: "")
                s.sportLeagues?.let { put("sportLeagues", JSONArray(it.sorted())) }
                put("sportManual", JSONObject(s.sportManualChannels))
                put("sportmonksToken", s.sportmonksToken)
                put("bzzoiroToken", s.bzzoiroToken)
                put("onlineExtras", s.onlineExtras)
                put("sportGuide", s.sportGuide)
                put("dohFallback", s.dohFallback)
                put("noHistory", s.noHistory)
                put("appLock", s.appLock)
                put("largeText", s.largeText)
                put("reducedEffects", s.reducedEffects)
                put("automaticOptimization", s.automaticOptimization)
            },
        )
        vpn?.let { root.put("vpn", it.backup()) }
        root.put(
            "playlists",
            JSONArray().apply {
                db.playlistDao().all().forEach { p ->
                    put(
                        JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("type", p.type)
                            put("serverUrl", p.serverUrl)
                            put("username", p.username)
                            put("password", SecretCipher.decrypt(p.password))
                            put("m3uUrl", SecretCipher.decrypt(p.m3uUrl))
                            put("epgUrl", p.customEpgUrl)
                            put("allowInsecureHttp", p.allowInsecureHttp)
                            put("epgShiftHours", p.epgShiftHours)
                        },
                    )
                }
            },
        )
        root.put(
            "favorites",
            JSONArray().apply {
                db.favoriteDao().all().forEach { put(JSONObject().put("type", it.type).put("itemId", it.itemId).put("addedAt", it.addedAtMs)) }
            },
        )
        root.put(
            "progress",
            JSONArray().apply {
                db.progressDao().all().forEach {
                    put(
                        JSONObject().put("key", it.key).put("position", it.positionMs).put("duration", it.durationMs)
                            .put("watched", it.watched).put("updatedAt", it.updatedAtMs).put("seriesId", it.seriesId ?: ""),
                    )
                }
            },
        )
        root.put(
            "introProfiles",
            JSONArray().apply {
                db.introProfileDao().all().forEach { put(JSONObject().put("seriesId", it.seriesId).put("start", it.introStartMs).put("end", it.introEndMs)) }
            },
        )
        root.put(
            "categoryPrefs",
            JSONArray().apply {
                db.categoryPrefDao().all().forEach {
                    put(
                        JSONObject().put("categoryId", it.categoryId).put("playlistId", it.playlistId)
                            .put("hidden", it.hidden).put("locked", it.locked).put("pinned", it.pinned)
                            .apply { it.position?.let { p -> put("position", p) } },
                    )
                }
            },
        )
        root.toString(2)
    }

    /**
     * Ersetzt alle Playlists und persönlichen Daten. Liefert die neue aktive Playlist-ID.
     * Verschlüsselte Sicherungen brauchen das Kennwort; ältere, unverschlüsselte werden weiter angenommen.
     *
     * Ablauf: erst die komplette Datei lesen und prüfen, dann alles in EINER Datenbank-Transaktion schreiben.
     * Bricht irgendetwas ab, bleibt der bisherige Stand vollständig erhalten (früher waren die Favoriten
     * schon gelöscht, bevor ein defekter Eintrag auffiel).
     */
    suspend fun restore(text: String, passphrase: String?): Long? = withContext(Dispatchers.IO) {
        val encryptedBackup = BackupCrypto.isEncrypted(text)
        val json = if (encryptedBackup) {
            require(!passphrase.isNullOrEmpty()) { "Diese Sicherung ist verschlüsselt – bitte das Kennwort eingeben." }
            BackupCrypto.decrypt(text, passphrase)
        } else {
            text
        }
        val backup = BackupData.parse(json)

        val oldPlaylistIds = db.playlistDao().all().map { it.id }
        val idMap = HashMap<Long, Long>()
        db.withTransaction {
            db.favoriteDao().deleteAll()
            db.progressDao().deleteAll()
            db.introProfileDao().deleteAll()
            db.categoryPrefDao().deleteAll()
            backup.playlists.forEach { (oldId, input) ->
                // Nur kennwortgeschützte Sicherungen dürfen eine frühere bewusste HTTP-Freigabe wiederherstellen.
                idMap[oldId] = playlists.addPlaylist(input.copy(allowInsecureHttp = encryptedBackup && input.allowInsecureHttp))
            }
            // EPG-Zeitverschiebung je Zugang; Sicherungen bis 3.31 kennen nur den gemeinsamen Wert.
            val legacyShift = backup.settings.optInt("epgShiftHours", 0)
            idMap.forEach { (oldId, newId) ->
                val hours = backup.epgShiftHours[oldId] ?: legacyShift
                if (hours != 0) playlists.setEpgShiftHours(newId, hours)
            }
            backup.favoriteEntities(idMap).forEach { db.favoriteDao().insert(it) }
            backup.progressEntities(idMap).forEach { db.progressDao().upsert(it) }
            backup.introEntities(idMap).forEach { db.introProfileDao().upsert(it) }
            db.categoryPrefDao().upsertAll(backup.prefEntities(idMap))
        }
        val s = backup.settings
        val backupPin = s.optString("parentalPinHash").takeIf { it.isNotEmpty() }
        val active = idMap[s.optLong("activePlaylistId", -1)] ?: idMap.values.firstOrNull()
        settings.update { current ->
            current.copy(
                liveEngine = enumOr(s.optString("liveEngine"), current.liveEngine),
                vodEngine = enumOr(s.optString("vodEngine"), current.vodEngine),
                introSkipMode = enumOr(s.optString("introSkipMode"), current.introSkipMode),
                introSkipSeconds = s.optInt("introSkipSeconds", current.introSkipSeconds),
                autoPlayNext = s.optBoolean("autoPlayNext", current.autoPlayNext),
                nextEpisodeTriggerSeconds = s.optInt("nextEpisodeTriggerSeconds", current.nextEpisodeTriggerSeconds),
                bufferProfile = enumOr(s.optString("bufferProfile"), current.bufferProfile),
                liveCushion = enumOr(s.optString("liveCushion"), current.liveCushion),
                hwDecoding = enumOr(s.optString("hwDecoding"), current.hwDecoding),
                autoFrameRate = s.optBoolean("autoFrameRate", current.autoFrameRate),
                tunneling = s.optBoolean("tunneling_v2", current.tunneling),
                audioPassthrough = s.optBoolean("audioPassthrough", current.audioPassthrough),
                playbackSupervisor = s.optBoolean("playbackSupervisor", current.playbackSupervisor),
                themeMode = enumOr(s.optString("themeMode"), current.themeMode),
                accent = enumOr(s.optString("accent"), current.accent),
                startWithLastChannel = s.optBoolean("startWithLastChannel", current.startWithLastChannel),
                autoUpdateOnStart = s.optBoolean("autoUpdateOnStart", current.autoUpdateOnStart),
                tmdbApiKey = s.optString("tmdbApiKey", current.tmdbApiKey),
                tmdbEnabled = s.optBoolean("tmdbEnabled", current.tmdbEnabled),
                // Sicherung ohne PIN: die vorhandene PIN bleibt – sonst hebt eine hochgeladene Datei die Kindersicherung auf.
                parentalPinHash = backupPin ?: current.parentalPinHash,
                sportLeagues = s.optJSONArray("sportLeagues")?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }
                    ?: current.sportLeagues,
                sportManualChannels = s.optJSONObject("sportManual")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
                    ?: current.sportManualChannels,
                sportmonksToken = s.optString("sportmonksToken", current.sportmonksToken),
                bzzoiroToken = s.optString("bzzoiroToken", current.bzzoiroToken),
                onlineExtras = s.optBoolean("onlineExtras", current.onlineExtras),
                sportGuide = s.optBoolean("sportGuide", current.sportGuide),
                dohFallback = s.optBoolean("dohFallback", current.dohFallback),
                noHistory = s.optBoolean("noHistory", current.noHistory),
                // App-Sperre nur übernehmen, wenn auch eine PIN mitkommt – sonst sperrt man sich aus.
                appLock = if (backupPin != null) s.optBoolean("appLock", false) else current.appLock,
                largeText = s.optBoolean("largeText", current.largeText),
                reducedEffects = s.optBoolean("reducedEffects", current.reducedEffects),
                automaticOptimization = s.optBoolean("automaticOptimization", current.automaticOptimization),
                activePlaylistId = active,
                lastChannelId = null,
                lastChannelCategoryId = null,
            )
        }
        vpn?.let { importer -> JSONObject(json).optJSONObject("vpn")?.let(importer::restore) }
        // Alte Zugänge samt Inhalten erst danach löschen: Das Aufräumen großer Tabellen gehört nicht in die
        // Transaktion, und bis hierhin gibt es nie einen Moment ganz ohne Zugang.
        oldPlaylistIds.forEach { playlists.deletePlaylist(it) }
        AppLog.log("Backup", "Sicherung wiederhergestellt (${idMap.size} Playlists)")
        active
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String, default: T): T = enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        const val FORMAT = BackupData.FORMAT
    }
}
