package com.mg.player.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.data.db.dao.ACTIVE_EPG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 3.12 (Datenbank 7) auf 3.13 (8): EPG bekommt eine Generation, die Zwischentabelle entfällt, vorhandenes EPG bleibt
 * sichtbar. Room prüft beim Öffnen das Ergebnis gegen das erwartete Schema.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration7To8Test {

    @Test
    fun existingEpgStaysVisible() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-7-8.db"
        context.deleteDatabase(name)
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().apply {
            openHelper.writableDatabase.apply {
                // Stand von Version 7 herstellen: epg_programs ohne Generation, Zwischentabelle, keine epg_active.
                execSQL("DROP TABLE epg_active")
                execSQL("DROP TABLE epg_programs")
                execSQL(
                    "CREATE TABLE `epg_programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, " +
                        "`channelKey` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
                )
                execSQL("CREATE INDEX `index_epg_programs_playlistId_channelKey_startMs` ON `epg_programs` (`playlistId`, `channelKey`, `startMs`)")
                execSQL("CREATE INDEX `index_epg_programs_startMs` ON `epg_programs` (`startMs`)")
                execSQL(
                    "CREATE TABLE `epg_staging` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, " +
                        "`channelKey` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
                )
                execSQL("INSERT INTO epg_programs (playlistId, channelKey, startMs, endMs, title, description) VALUES (1, 'zdf.de', 1000, 2000, 'heute', NULL)")
                version = 7
            }
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*Migrations.ALL).build()
        try {
            val sql = db.openHelper.readableDatabase
            assertEquals(AppDatabase.VERSION, sql.version)
            sql.query("SELECT COUNT(*) FROM epg_programs p WHERE $ACTIVE_EPG").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            sql.query("SELECT name FROM sqlite_master WHERE name = 'epg_staging'").use { assertEquals(0, it.count) }
            assertTrue(db.epgDao().hasPrograms(1))
            assertEquals(1, db.epgDao().programs(1, "zdf.de", 0, 5000).size)
        } finally {
            db.close()
        }
    }
}
