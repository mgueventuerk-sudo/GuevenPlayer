package com.mg.player.data.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fall aus der Prüfung: Eine defekte Sicherung löschte Favoriten und Fortschritt, bevor der Fehler auffiel. */
class BackupDataTest {

    private fun backup(playlists: JSONArray = JSONArray().put(xtream(7))): String = JSONObject()
        .put("format", BackupData.FORMAT)
        .put("playlists", playlists)
        .put("favorites", JSONArray().put(JSONObject().put("type", "MOVIE").put("itemId", "7:M:42").put("addedAt", 5)))
        .put("progress", JSONArray().put(JSONObject().put("key", "movie:7:M:42").put("position", 1000).put("duration", 5000)))
        .put("categoryPrefs", JSONArray().put(JSONObject().put("categoryId", "7:L:3").put("playlistId", 7).put("hidden", true)))
        .put(
            "recordings",
            JSONArray().put(
                JSONObject().put("channelId", "7:L:9").put("channelName", "Das Erste").put("title", "Tatort")
                    .put("startedAt", 1_000).put("plannedEnd", 2_000).put("programStart", 1_060),
            ),
        )
        .put(
            "reminders",
            JSONArray().put(JSONObject().put("channelId", "7:L:9").put("channelName", "Das Erste").put("title", "Tatort").put("startMs", 1_000)),
        )
        .toString()

    private fun xtream(id: Long) = JSONObject().put("id", id).put("name", "Test").put("type", "XTREAM")
        .put("serverUrl", "http://example.org").put("username", "u").put("password", "p")

    private fun expectFailure(json: String, part: String) {
        try {
            BackupData.parse(json)
            fail("Fehler erwartet")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(part))
        }
    }

    @Test
    fun remapsAllReferencesToNewPlaylistIds() {
        val data = BackupData.parse(backup())
        val idMap = mapOf(7L to 31L)
        assertEquals("31:M:42", data.favoriteEntities(idMap).single().itemId)
        assertEquals("MOVIE:31:M:42", data.favoriteEntities(idMap).single().key)
        assertEquals("movie:31:M:42", data.progressEntities(idMap).single().key)
        val pref = data.prefEntities(idMap).single()
        assertEquals("31:L:3", pref.categoryId)
        assertEquals(31L, pref.playlistId)
        assertTrue(pref.hidden)
        val recording = data.recordingEntities(idMap).single()
        assertEquals("31:L:9", recording.channelId)
        assertEquals("Tatort", recording.title)
        assertTrue(recording.isScheduled)
        val reminder = data.reminderEntities(idMap).single()
        assertEquals("31:L:9", reminder.channelId)
        assertEquals("Tatort", reminder.title)
    }

    @Test
    fun rejectsCorruptFile() = expectFailure("{\"format\": \"mg-player-ba", "beschädigt")

    @Test
    fun rejectsForeignFile() = expectFailure("{\"format\": \"etwas-anderes\"}", "keine Sicherung")

    @Test
    fun rejectsBackupWithoutAccounts() = expectFailure(backup(JSONArray()), "keine Zugänge")

    @Test
    fun rejectsIncompleteAccount() = expectFailure(backup(JSONArray().put(JSONObject().put("id", 1).put("name", "Kaputt").put("type", "M3U"))), "unvollständig")
}
