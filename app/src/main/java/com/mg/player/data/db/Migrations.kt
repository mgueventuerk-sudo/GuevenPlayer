package com.mg.player.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mg.player.data.importer.ChannelGrouping

/** Datenbank-Updates ohne Datenverlust: Zugänge, Favoriten und Fortschritt bleiben erhalten. */
internal object Migrations {

    /** Version 3: Sender-Gruppierung (gleicher Sender in mehreren Qualitäten unter einer Kachel). */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE channels ADD COLUMN groupKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN qualityRank INTEGER NOT NULL DEFAULT 50")
            // Vorhandene Sender gleich gruppieren, damit nicht erst eine Aktualisierung nötig ist.
            val update = db.compileStatement("UPDATE channels SET groupKey = ?, qualityRank = ? WHERE id = ?")
            db.query(
                "SELECT c.id, c.name, cat.name FROM channels c LEFT JOIN categories cat ON cat.id = c.categoryId",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val categoryName = if (cursor.isNull(2)) null else cursor.getString(2)
                    val grouping = ChannelGrouping.of(cursor.getString(1), categoryName?.let { com.mg.player.core.util.CountryDetector.detect(it) })
                    update.bindString(1, grouping.key)
                    update.bindLong(2, grouping.rank.toLong())
                    update.bindString(3, cursor.getString(0))
                    update.executeUpdateDelete()
                    update.clearBindings()
                }
            }
        }
    }

    /**
     * Version 4: EPG je Zugang, Index für Sender-Varianten, Volltext-Suche.
     * Das alte, zugangsübergreifende EPG wird verworfen und beim nächsten Start neu geladen (nur sichtbare
     * Gruppen) – eine Zuordnung von Millionen Zeilen hier würde den ersten Start minutenlang blockieren.
     * Der Suchindex wird bei der ersten Suche bzw. der nächsten Aktualisierung aufgebaut.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `epg_programs`")
            db.execSQL("DROP TABLE IF EXISTS `epg_staging`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`playlistId` INTEGER NOT NULL, `channelKey` TEXT NOT NULL, `startMs` INTEGER NOT NULL, " +
                    "`endMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_programs_playlistId_channelKey_startMs` " +
                    "ON `epg_programs` (`playlistId`, `channelKey`, `startMs`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_staging` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`playlistId` INTEGER NOT NULL, `channelKey` TEXT NOT NULL, `startMs` INTEGER NOT NULL, " +
                    "`endMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_staging_playlistId` ON `epg_staging` (`playlistId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_playlistId_groupKey` ON `channels` (`playlistId`, `groupKey`)")
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `search_index` USING FTS4(`name` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, `playlistId` TEXT NOT NULL, tokenize=unicode61, notindexed=`itemId`, " +
                    "notindexed=`type`, notindexed=`playlistId`)",
            )
        }
    }

    /**
     * Version 5: vorberechnete Sender-Gruppierung (channel_flags). Gefüllt wird sie beim ersten Öffnen einer
     * Senderliste bzw. nach der nächsten Aktualisierung (ChannelGroupIndex.ensure) – nicht hier, damit der erste
     * Start nach dem Update nicht sekundenlang blockiert.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `channel_flags` (`channelId` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, " +
                    "`categoryId` TEXT NOT NULL, `sortIndex` INTEGER NOT NULL, `primaryAll` INTEGER NOT NULL, " +
                    "`variantsAll` INTEGER NOT NULL, `primaryCat` INTEGER NOT NULL, `variantsCat` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`channelId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_channel_flags_playlistId_primaryAll_sortIndex` " +
                    "ON `channel_flags` (`playlistId`, `primaryAll`, `sortIndex`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_channel_flags_playlistId_categoryId_primaryCat_sortIndex` " +
                    "ON `channel_flags` (`playlistId`, `categoryId`, `primaryCat`, `sortIndex`)",
            )
        }
    }

    /** Version 3.2: Live-Sport (Events + Senderzuordnung) und Index für den EPG-Abgleich nach Startzeit. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sport_events` (`id` TEXT NOT NULL, `sport` TEXT NOT NULL, `league` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `homeTeam` TEXT, `awayTeam` TEXT, `startMs` INTEGER NOT NULL, `endMs` INTEGER, " +
                    "`status` TEXT NOT NULL, `stations` TEXT NOT NULL, `fetchedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sport_events_startMs` ON `sport_events` (`startMs`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sport_event_channels` (`eventId` TEXT NOT NULL, `channelId` TEXT NOT NULL, " +
                    "`playlistId` INTEGER NOT NULL, `score` INTEGER NOT NULL, PRIMARY KEY(`eventId`, `channelId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sport_event_channels_channelId` ON `sport_event_channels` (`channelId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_startMs` ON `epg_programs` (`startMs`)")
        }
    }

    /** Version 3.3: eigene Reihenfolge der Kategorien. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `category_prefs` ADD COLUMN `position` INTEGER")
        }
    }

    /**
     * Version 3.13: EPG mit Generation statt Zwischentabelle. Vorhandenes EPG bleibt sichtbar (Generation 0).
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val hasColumn = db.query("PRAGMA table_info(`epg_programs`)").use { c ->
                val name = c.getColumnIndex("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == "generation" }
            }
            if (!hasColumn) db.execSQL("ALTER TABLE `epg_programs` ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS `epg_active` (`playlistId` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`playlistId`))")
            db.execSQL("DROP TABLE IF EXISTS `epg_staging`")
        }
    }

    /** Version 9: HTTP muss je IPTV-Zugang ausdrücklich freigegeben werden; bestehende Zugänge bleiben gesperrt. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val hasColumn = db.query("PRAGMA table_info(`playlists`)").use { cursor ->
                val name = cursor.getColumnIndex("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(name) else null }.any { it == "allowInsecureHttp" }
            }
            if (!hasColumn) db.execSQL("ALTER TABLE `playlists` ADD COLUMN `allowInsecureHttp` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Version 10 (3.24): Indizes gegen volle Durchläufe, die EXPLAIN QUERY PLAN im Labor 3.23 fand
     * (Gegenstücke/Sport über normalizedName, Episoden-Aufräumen über playlistId). Nur Indizes, keine Datenänderung.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_normalizedName` ON `channels` (`normalizedName`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_playlistId` ON `episodes` (`playlistId`)")
        }
    }

    /**
     * Version 11 (3.26): Intervall der automatischen Komplett-Aktualisierung je Zugang (Tage, 0 = aus).
     * Bestehende Zugänge bleiben aus, bis man es einstellt.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Nur hinzufügen, wenn sie fehlt (Datenbanken, die schon mit dem neuen Tabellenaufbau angelegt wurden).
            val exists = db.query("PRAGMA table_info(`playlists`)").use { c ->
                val name = c.getColumnIndex("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == "autoRefreshDays" }
            }
            if (!exists) db.execSQL("ALTER TABLE `playlists` ADD COLUMN `autoRefreshDays` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Version 12 (3.28): „Alle Zugänge“ – Zuordnungstabelle, eigene Korrekturen, EPG-Quelle je Sender.
     * Nur neue Tabellen und eine Spalte mit Standardwert; bestehende Daten bleiben unberührt.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val hasColumn = db.query("PRAGMA table_info(`channels`)").use { c ->
                val name = c.getColumnIndex("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == "epgPlaylistId" }
            }
            if (!hasColumn) db.execSQL("ALTER TABLE `channels` ADD COLUMN `epgPlaylistId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `combined_links` (`combinedId` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
                    "`playlistId` INTEGER NOT NULL, `type` TEXT NOT NULL, `qualityRank` INTEGER NOT NULL, `language` TEXT, " +
                    "`durationSec` INTEGER, PRIMARY KEY(`combinedId`, `itemId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_combined_links_itemId` ON `combined_links` (`itemId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_combined_links_type` ON `combined_links` (`type`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `combined_rules` (`type` TEXT NOT NULL, `keyA` TEXT NOT NULL, `keyB` TEXT NOT NULL, " +
                    "`merge` INTEGER NOT NULL, PRIMARY KEY(`type`, `keyA`, `keyB`))",
            )
        }
    }

    /**
     * Version 13 (3.32): EPG-Zeitverschiebung je Zugang statt für alle gemeinsam. Der bisherige gemeinsame Wert wird
     * beim ersten Start von der App auf alle Zugänge übertragen ([com.mg.player.data.settings.EpgShifts.adoptLegacy]).
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val exists = db.query("PRAGMA table_info(`playlists`)").use { c ->
                val name = c.getColumnIndex("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == "epgShiftHours" }
            }
            if (!exists) db.execSQL("ALTER TABLE `playlists` ADD COLUMN `epgShiftHours` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Alle Migrationen in Reihenfolge – lückenlos von 2 bis [AppDatabase.VERSION] (prüft MigrationChainTest). */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
        MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
    )
}
