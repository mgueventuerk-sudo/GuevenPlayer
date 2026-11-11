package com.mg.player.data.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Antworten des Anbieters sind fremd: übergroße Felder und Feldmengen dürfen Speicher und Datenbank nicht aufblähen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class JsonLimitsTest {

    private fun read(json: String): List<Map<String, String?>> = runBlocking {
        val items = ArrayList<Map<String, String?>>()
        jsonReader(json.byteInputStream()).use { it.forEachObject { item -> items += item } }
        items
    }

    @Test
    fun hugeValuesAreCut() {
        val plot = "x".repeat(MAX_FIELD_CHARS * 3)
        val item = read("""[{"name":"Film","plot":"$plot"}]""").single()
        assertEquals("Film", item["name"])
        assertEquals(MAX_FIELD_CHARS, item["plot"]!!.length)
    }

    @Test
    fun fieldCountIsLimitedAndTheRestIsSkipped() {
        val fields = (1..MAX_FIELDS * 2).joinToString(",") { "\"f$it\":\"$it\"" }
        val items = read("""[{$fields},{"name":"Zweiter"}]""")
        assertEquals(2, items.size)
        assertEquals(MAX_FIELDS, items[0].size)
        assertEquals("Zweiter", items[1]["name"])
    }

    @Test
    fun nestedAndMixedValuesStillWork() {
        val item = read("""{"1":{"name":"A","info":{"x":1},"backdrop_path":["","http://b"],"rating":4.5,"adult":false,"x":null}}""").single()
        assertEquals("A", item["name"])
        assertEquals("http://b", item["backdrop_path"])
        assertEquals("4.5", item["rating"])
        assertEquals("false", item["adult"])
        assertTrue("x" in item && item["x"] == null)
        assertTrue("info" !in item)
    }

    /**
     * Abbruch mitten im Lesen: XtreamImporter.importCategories sammelt nur in einer ArrayList (kein eigener
     * Suspend-Aufruf je Objekt) - ohne das yield() in forEachObject liefe das Lesen eines grossen Kategorien-
     * JSON nach cancel() trotzdem bis zum Ende durch. Deterministisch: feste Groesse, kein Zufall, keine Zeit.
     */
    @Test(timeout = 30_000)
    fun cancellingForEachObjectStopsItEarly() = runBlocking {
        val total = 20_000
        val json = (0 until total).joinToString(",", prefix = "[", postfix = "]") { i ->
            """{"category_id":"$i","category_name":"K$i"}"""
        }
        var seen = 0
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val work = scope.launch {
            jsonReader(json.byteInputStream()).use { reader ->
                reader.forEachObject {
                    seen++
                    if (seen == 1) job.cancel()
                }
            }
        }
        work.join()
        assertTrue("forEachObject war abgebrochen, lief aber bis zum Ende: $seen von $total", seen < total / 2)
        assertTrue("forEachObject hat gar nicht erst gestartet", seen >= 1)
    }
}
