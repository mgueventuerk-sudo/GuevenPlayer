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

/** Version 8 auf 9: alte Zugänge bekommen standardmäßig keine HTTP-Freigabe. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration8To9Test {

    @Test
    fun existingPlaylistsRemainAndHttpDefaultsToDenied() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-8-9.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE `playlists` (`id` INTEGER NOT NULL PRIMARY KEY, `name` TEXT NOT NULL)")
                            db.execSQL("INSERT INTO `playlists` (`id`, `name`) VALUES (1, 'Vorhanden')")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        val database = helper.writableDatabase
        try {
            Migrations.MIGRATION_8_9.migrate(database)
            database.query("SELECT name, allowInsecureHttp FROM playlists WHERE id = 1").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("Vorhanden", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
