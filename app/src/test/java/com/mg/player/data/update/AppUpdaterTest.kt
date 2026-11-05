package com.mg.player.data.update

import com.mg.player.core.network.HttpStatusException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Releases aus dem privaten Repo lesen – und nur eine Release-APK von GitHub akzeptieren (dorthin geht der Schlüssel). */
class AppUpdaterTest {

    private fun release(tag: String, vararg assets: Pair<String, String>) = JSONObject(
        """{"tag_name":"$tag","name":"Player 3.52.261106.1200","assets":[""" +
            assets.joinToString(",") { (name, url) -> """{"name":"$name","url":"$url","size":52428800}""" } + "]}",
    )

    private val api = "https://api.github.com/repos/mgueventuerk-sudo/Player/releases/assets"

    @Test
    fun readsVersionAndReleaseApk() {
        val update = AppUpdater.parseRelease(release("build-456789", "Player-30-debug.apk" to "$api/1", "Player-30.apk" to "$api/2"))!!
        assertEquals(456789, update.versionCode)
        assertEquals("3.52.261106.1200", update.versionName)
        assertEquals("$api/2", update.assetUrl)
        assertEquals(52428800L, update.sizeBytes)
    }

    @Test
    fun ignoresForeignTagsDebugOnlyAndForeignHosts() {
        assertNull(AppUpdater.parseRelease(release("v1.0", "Player.apk" to "$api/2")))
        assertNull(AppUpdater.parseRelease(release("build-5", "Player-30-debug.apk" to "$api/1")))
        // Schlüssel nie an eine fremde Adresse aus der Antwort.
        assertNull(AppUpdater.parseRelease(release("build-5", "Player.apk" to "https://evil.example/Player.apk")))
    }

    @Test
    fun problemsAreReadable() {
        assertTrue(AppUpdater.problem(HttpStatusException(401, null)).contains("ungültig"))
        assertTrue(AppUpdater.problem(HttpStatusException(404, null)).contains("Kein Release"))
        assertEquals("keine Verbindung", AppUpdater.problem(java.io.IOException()))
    }
}
