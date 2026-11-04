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

/** Version 12 auf 13 (3.32): EPG-Zeitverschiebung je Zugang; bestehende Zugänge starten mit 0 (der alte Wert kommt per EpgShifts). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration12To13Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun existingPlaylistsKeepDataAndStartWithoutShift() {
        val name = "migration-12-13.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
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
            Migrations.MIGRATION_12_13.migrate(db)
            Migrations.MIGRATION_12_13.migrate(db) // zweimal: Spalte schon da → kein Fehler
            db.query("SELECT name, lastUpdatedMs, epgShiftHours FROM playlists").use {
                it.moveToFirst()
                assertEquals("Mein Zugang", it.getString(0))
                assertEquals(1_780_000_000_000L, it.getLong(1))
                assertEquals(0, it.getInt(2))
            }
            db.execSQL("UPDATE playlists SET epgShiftHours = -2")
            db.query("SELECT epgShiftHours FROM playlists").use { it.moveToFirst(); assertEquals(-2, it.getInt(0)) }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
