package com.mg.player.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Version 11 auf 12 (3.28): „Alle Zugänge“ – neue Tabellen, EPG-Quelle je Sender (Standard 0 = eigener Zugang). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration11To12Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun addsTablesAndColumnKeepingChannels() {
        val name = "migration-11-12.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `channels` (`id` TEXT NOT NULL PRIMARY KEY, `playlistId` INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO `channels` VALUES ('1:L:1', 1)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        val db = helper.writableDatabase
        try {
            Migrations.MIGRATION_11_12.migrate(db)
            Migrations.MIGRATION_11_12.migrate(db) // zweimal: nichts doppelt
            db.query("SELECT epgPlaylistId FROM channels").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
            db.execSQL("INSERT INTO combined_links VALUES ('-2:L:a', '1:L:1', 1, 'LIVE', 30, 'DE', NULL)")
            db.execSQL("INSERT INTO combined_rules VALUES ('LIVE', 'n:DE:a', 'n:DE:b', 0)")
            db.query("SELECT COUNT(*) FROM combined_links").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            val indices = db.query("PRAGMA index_list(`combined_links`)").use { c ->
                val col = c.getColumnIndex("name")
                generateSequence { if (c.moveToNext()) c.getString(col) else null }.toList()
            }
            assertTrue("index_combined_links_itemId" in indices)
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
