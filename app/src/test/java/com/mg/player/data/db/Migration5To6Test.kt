package com.mg.player.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.data.db.entity.FavoriteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Update von 3.1 (Datenbank 5) auf 3.2 (6): Die Migration muss genau das Schema der Entities ergeben (Room prüft das
 * beim Öffnen und bricht sonst ab) und darf vorhandene Daten nicht anfassen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration5To6Test {

    @Test
    fun upgradeKeepsDataAndCreatesSportTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-5-6.db"
        context.deleteDatabase(name)
        // Aktuelles Schema anlegen und auf Version 5 zurückbauen (alles entfernen, was 5→6 hinzufügt).
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().apply {
            favoriteDao().insert(FavoriteEntity("MOVIE:1:M:5", "MOVIE", "1:M:5", 42L))
            openHelper.writableDatabase.apply {
                execSQL("DROP TABLE sport_event_channels")
                execSQL("DROP TABLE sport_events")
                execSQL("DROP INDEX index_epg_programs_startMs")
                Migration6To7Test.downgradeCategoryPrefs(this)
                version = 5
            }
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*Migrations.ALL).build()
        try {
            assertTrue(db.favoriteDao().exists("MOVIE", "1:M:5"))
            assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version)
            assertTrue(db.sportDao().allEventIds().isEmpty())
            db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'index_epg_programs_startMs'").use {
                assertEquals(1, it.count)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
