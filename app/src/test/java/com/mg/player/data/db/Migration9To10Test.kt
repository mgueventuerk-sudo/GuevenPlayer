package com.mg.player.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.data.db.dao.EXCLUDE_RESTRICTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Version 9 auf 10 (3.24): nur neue Indizes. Im Labor 3.23 fand EXPLAIN QUERY PLAN volle Durchläufe über alle Sender
 * aller Zugänge (Gegenstücke, Sport-Zuordnung) und über alle Episoden (Aufräumen nach dem Import).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration9To10Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun migrationAddsIndicesAndKeepsRows() {
        val name = "migration-9-10.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE `channels` (`id` TEXT NOT NULL PRIMARY KEY, `normalizedName` TEXT NOT NULL)")
                            db.execSQL("CREATE TABLE `episodes` (`id` TEXT NOT NULL PRIMARY KEY, `playlistId` INTEGER NOT NULL)")
                            db.execSQL("INSERT INTO `channels` VALUES ('1:L:1', 'zdf')")
                            db.execSQL("INSERT INTO `episodes` VALUES ('1:E:1', 1)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase
        try {
            Migrations.MIGRATION_9_10.migrate(db)
            Migrations.MIGRATION_9_10.migrate(db) // zweimal: IF NOT EXISTS
            assertTrue("index_channels_normalizedName" in indices(db, "channels"))
            assertTrue("index_episodes_playlistId" in indices(db, "episodes"))
            db.query("SELECT COUNT(*) FROM channels").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM episodes").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    private fun indices(db: SupportSQLiteDatabase, table: String): List<String> = db.query("PRAGMA index_list(`$table`)").use { c ->
        val col = c.getColumnIndex("name")
        generateSequence { if (c.moveToNext()) c.getString(col) else null }.toList()
    }

    /** Wächter: Die 3 Queries aus dem Labor-Befund dürfen große Tabellen nicht mehr komplett durchlaufen. */
    @Test
    fun formerFullScansNowUseIndices() {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val db = room.openHelper.writableDatabase
        val queries = mapOf(
            "counterparts" to "SELECT * FROM channels c WHERE c.playlistId != 1 AND ((? != '' AND c.epgKey = lower(?) AND " +
                "c.epgChannelId = ?) OR (? != '' AND c.normalizedName = ?)) AND $EXCLUDE_RESTRICTED ORDER BY c.qualityRank LIMIT 20",
            "channelsByNormalizedName" to "SELECT c.id FROM channels c WHERE c.normalizedName IN (?, ?) AND $EXCLUDE_RESTRICTED",
            "deleteEpisodesForPlaylist" to "DELETE FROM episodes WHERE playlistId = ?",
            "deleteStaleEpisodes" to "DELETE FROM episodes WHERE playlistId = ? AND (seriesId NOT IN " +
                "(SELECT id FROM series WHERE playlistId = ?) OR (generation != 0 AND generation != ?))",
        )
        try {
            queries.forEach { (label, sql) ->
                val args = arrayOfNulls<Any>(sql.count { it == '?' }).also { it.fill("x") }
                val plan = db.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
                    generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndex("detail")) else null }.toList()
                }
                val fullScan = plan.any { (it.startsWith("SCAN c") || it.startsWith("SCAN channels") || it.startsWith("SCAN episodes")) && "INDEX" !in it }
                assertFalse("$label läuft wieder ohne Index: $plan", fullScan)
            }
        } finally {
            room.close()
        }
    }
}
