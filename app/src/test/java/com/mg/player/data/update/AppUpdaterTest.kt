package com.mg.player.data.update

import com.mg.player.core.network.HttpStatusException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Releases aus dem privaten Repo lesen – und nur eine Release-APK von GitHub akzeptieren (dorthin geht der Schlüssel). */
class AppUpdaterTest {

    private fun release(tag: String, vararg assets: Pair<String, String>, body: String = "") = JSONObject(
        """{"tag_name":"$tag","name":"Player 3.52.261106.1200","body":${JSONObject.quote(body)},"assets":[""" +
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
    fun readsAndCleansReleaseNotes() {
        val body = """
            ## What's Changed
            * Farbsystem: Markenfarbe als Standard by @mgueventuerk-sudo in https://github.com/owner/repo/pull/11
            * Bewegung: Ambient-Loop-Konstante by @mgueventuerk-sudo in https://github.com/owner/repo/pull/11

            **Full Changelog**: https://github.com/owner/repo/compare/build-119...build-120
        """.trimIndent()
        val update = AppUpdater.parseRelease(release("build-120", "Player-30.apk" to "$api/2", body = body))!!
        assertEquals("• Farbsystem: Markenfarbe als Standard\n• Bewegung: Ambient-Loop-Konstante", update.notes)
    }

    @Test
    fun hasNoNotesWithoutAUsableChangelog() {
        assertNull(AppUpdater.parseRelease(release("build-120", "Player-30.apk" to "$api/2", body = ""))!!.notes)
        // Vollautomatisches Release ohne Pull-Request-Zeilen (nur Metadaten) - nichts Lesbares übrig.
        assertNull(AppUpdater.cleanChangelog("Build 120 · ccr-67aaff33-ogct3s · 7a5e667"))
    }

    @Test
    fun capsTheNumberOfChangelogLines() {
        val body = (1..14).joinToString("\n") { "* Punkt $it by @user in https://github.com/owner/repo/pull/$it" }
        val notes = AppUpdater.cleanChangelog(body)!!
        assertEquals(10, notes.lines().size)
    }

    /** release-notes.md: Überschriften und Punkte in Alltagssprache, Kommentare bleiben weg. */
    @Test
    fun readsPlainReleaseNotesWithSections() {
        val body = """
            <!-- Vor jedem Release anpassen -->

            ## Neu
            - Einstellung „Sprache“

            ## Behoben
            - Serien wechseln nicht mehr die Sprache
            - App-Sperre gilt auch beim Start von außen

            ## Leer

            **Full Changelog**: https://github.com/owner/repo/compare/build-1...build-2
        """.trimIndent()
        assertEquals(
            "Neu:\n• Einstellung „Sprache“\nBehoben:\n• Serien wechseln nicht mehr die Sprache\n• App-Sperre gilt auch beim Start von außen",
            AppUpdater.cleanChangelog(body),
        )
    }

    @Test
    fun skipsNewContributorsAndAHeadingCutOffAtTheEnd() {
        val body = "## What's Changed\n* Fix by @a in https://x/pull/1\n## New Contributors\n* @a made their first contribution in https://x/pull/1"
        assertEquals("• Fix", AppUpdater.cleanChangelog(body))
        // Zehnte Zeile wäre die Überschrift „Neu:“ ohne ihren Punkt – sie fällt weg.
        val many = "## Behoben\n" + (1..8).joinToString("\n") { "- Punkt $it" } + "\n## Neu\n- später"
        assertEquals(9, AppUpdater.cleanChangelog(many)!!.lines().size)
        assertEquals("• Punkt 8", AppUpdater.cleanChangelog(many)!!.lines().last())
        // Deutscher Satz auf „in …“ bleibt ganz; Punkt mit Doppelpunkt ist keine Überschrift.
        assertEquals("• Ton läuft wieder in Ordnung\n• Neu:", AppUpdater.cleanChangelog("- Ton läuft wieder in Ordnung\n- Neu:"))
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
