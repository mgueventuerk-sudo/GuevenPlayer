package com.mg.player.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Version 10 auf 11 (3.26): Intervall der automatischen Aktualisierung je Zugang; bestehende Zugänge bleiben „aus“. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration10To11Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun existingPlaylistsKeepDataAndStartWithAutoRefreshOff() {
        val name = "migration-10-11.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(10) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                                    "`lastUpdatedMs` INTEGER NOT NULL)",
                            )
                            db.execSQL("INSERT INTO `playlists` (`name`, `lastUpdatedMs`) VALUES ('Mein Zugang', 1780000000000)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase
        try {
            Migrations.MIGRATION_10_11.migrate(db)
            Migrations.MIGRATION_10_11.migrate(db) // zweimal: Spalte schon da → kein Fehler
            db.query("SELECT name, lastUpdatedMs, autoRefreshDays FROM playlists").use {
                it.moveToFirst()
                assertEquals("Mein Zugang", it.getString(0))
                assertEquals(1_780_000_000_000L, it.getLong(1))
                assertEquals(0, it.getInt(2))
            }
            db.execSQL("UPDATE playlists SET autoRefreshDays = 3")
            db.query("SELECT autoRefreshDays FROM playlists").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
