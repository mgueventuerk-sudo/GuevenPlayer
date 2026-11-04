package com.mg.player.data.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParserException
import org.robolectric.annotation.Config

/** Deterministic malformed-input stress tests; seeds are fixed so a failure is reproducible. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ParserFuzzTest {

    @Test(timeout = 30_000)
    fun randomizedM3uNoiseStaysBoundedAndDoesNotCrash() = runBlocking {
        val random = Random(0x4d3355L)
        val fragments = listOf(
            "#EXTM3U url-tvg=\"http://epg.invalid/guide.xml\"",
            "#EXTINF:-1 tvg-id=\"x\" group-title=\"news\",Sender",
            "#EXTVLCOPT:http-user-agent=TV/1.0",
            "#EXTHTTP:{\"Referer\":\"https://portal.invalid/\"}",
            "#EXTGRP:Deutschland",
            "http://stream.invalid/live/1.ts?token=abc",
            "https://stream.invalid/movie/2.mkv",
            "#UNKNOWN:\u0000\ufffd",
            "not a playlist line",
        )

        repeat(600) { case ->
            val input = buildString {
                if (case % 3 == 0) append("#EXTM3U\r\n")
                repeat(random.nextInt(1, 70)) {
                    append(fragments[random.nextInt(fragments.size)])
                    if (random.nextInt(4) == 0) append(randomText(random, random.nextInt(1, 80)))
                    append(if (random.nextBoolean()) "\r\n" else "\n")
                }
                if (case % 23 == 0) append("#EXTVLCOPT:").append("h".repeat(M3uParser.MAX_LINE - 32)).append('\n')
            }
            var entries = 0
            try {
                M3uParser.parse(
                    input.byteInputStream(),
                    onHeaderEpg = { assertTrue("EPG URL exceeds line bound", it.length <= M3uParser.MAX_LINE) },
                    onEntry = {
                        entries++
                        assertTrue("entry URL exceeds line bound", it.url.length <= M3uParser.MAX_LINE)
                        assertTrue("entry header count is unbounded", it.headers.size <= 32)
                    },
                )
            } catch (error: IOException) {
                assertEquals("only the documented malformed-playlist error is allowed", M3uParser.NOT_M3U, error.message)
            }
            assertTrue("one input produced an implausible number of entries", entries <= 70)
        }
    }

    @Test(timeout = 30_000)
    fun malformedXmltvDocumentsRejectCleanlyAndCapStoredText() = runBlocking {
        val random = Random(0x584d4c5456L)
        repeat(350) { case ->
            val id = randomXmlText(random, if (case % 7 == 0) 400 else random.nextInt(1, 280)).xmlEscape()
            val title = randomXmlText(random, if (case % 5 == 0) 1_200 else random.nextInt(1, 900)).xmlEscape()
            val doc = when (case % 4) {
                0 -> "<tv><channel id=\"$id\"><display-name>$title</display-name></channel>" +
                    "<programme channel=\"$id\" start=\"20261101050000 +0000\" stop=\"20261101060000 +0000\">" +
                    "<title>$title</title><desc>$title</desc></programme></tv>"

                1 -> "<tv><channel id=\"$id\"><display-name>$title</display-name></channel><programme"
                2 -> "<!DOCTYPE tv [<!ENTITY ext SYSTEM \"file:///etc/passwd\">]>" +
                    "<tv><programme channel=\"x\" start=\"20261101050000 +0000\" stop=\"20261101060000 +0000\"><title>&ext;</title></programme></tv>"

                else -> "<tv><unknown><channel id=\"$id\"><display-name>$title</display-name></channel></unknown></tv>"
            }
            var channels = 0
            var programmes = 0
            val callback = object : XmltvParser.Callback {
                override suspend fun onChannel(id: String, displayNames: List<String>) {
                    channels++
                    assertTrue("channel id is not capped", id.length <= 256)
                    assertTrue("too many display names retained", displayNames.size <= 8)
                    assertTrue("display-name is not capped", displayNames.all { it.length <= 256 })
                }

                override suspend fun onProgramme(channelId: String, startMs: Long, endMs: Long, title: String, description: String?) {
                    programmes++
                    assertTrue("programme title is not capped", title.length <= 600)
                    assertTrue("programme description is not capped", (description?.length ?: 0) <= 600)
                    assertTrue("invalid duration escaped validation", endMs >= startMs)
                    assertTrue("external entity expanded local file content", !title.contains("root:"))
                }
            }
            var rejected = false
            try {
                XmltvParser.parse(doc.byteInputStream(), callback)
            } catch (error: XmlPullParserException) {
                // Rejecting malformed XML, DTDs, or unresolved entities is expected.
                rejected = true
                assertTrue("XML parser returned an empty error: $error", error.toString().isNotBlank())
            } catch (error: IOException) {
                // Input-level parser rejection is expected for malformed documents.
                rejected = true
                assertTrue("XML input returned an empty error: $error", error.toString().isNotBlank())
            }
            if (case % 4 == 1) assertTrue("truncated XML was accepted", rejected)
            assertTrue("input emitted too many callbacks", channels <= 1 && programmes <= 1)
        }
    }

    private fun randomText(random: Random, length: Int): String {
        val alphabet = "abcXYZ0123456789 <>:&;=!?[]{}()\t\u0000\ufffdÄö中🎬"
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomXmlText(random: Random, length: Int): String {
        val alphabet = "abcXYZ0123456789 <>:&;=!?[]{}() Äö中"
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun String.xmlEscape(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
