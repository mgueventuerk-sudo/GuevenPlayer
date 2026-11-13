package com.mg.player.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.player.supervisor.StartHint
import com.mg.player.player.supervisor.Symptom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Gedächtnis des Wiedergabe-Wächters: speichern, verfallen lassen, zählen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PrefsRecoveryMemoryTest {
    private var now = 1_000_000L
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("supervisor-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }
    private val memory = PrefsRecoveryMemory(prefs) { now }

    @Test
    fun remembersAndForgets() {
        val hint = StartHint(engine = PlayerEngineType.LIBVLC, safeAudio = true)
        memory.remember("ch:1:sky", hint)
        assertEquals(hint, PrefsRecoveryMemory(prefs) { now }.hintFor("ch:1:sky"))
        memory.forget("ch:1:sky")
        assertNull(memory.hintFor("ch:1:sky"))
    }

    @Test
    fun hintsExpireAfterTwoWeeks() {
        memory.remember("mo:5", StartHint(safeDecoder = true))
        now += 15L * 24 * 60 * 60 * 1000
        assertNull(memory.hintFor("mo:5"))
    }

    @Test
    fun countsSeenAndFixed() {
        memory.count(Symptom.VIDEO_WITHOUT_AUDIO, fixed = false)
        memory.count(Symptom.VIDEO_WITHOUT_AUDIO, fixed = false)
        memory.count(Symptom.VIDEO_WITHOUT_AUDIO, fixed = true)
        assertEquals(mapOf(Symptom.VIDEO_WITHOUT_AUDIO to (2 to 1)), memory.stats())
    }

    @Test
    fun keepsAtMostFiveHundredEntries() {
        (1..520).forEach {
            now += 1
            memory.remember("c$it", StartHint(safeAudio = true))
        }
        assertEquals(500, prefs.all.keys.count { it.startsWith("hint:") })
        assertNull(memory.hintFor("c1"))
    }

    @Test
    fun remembersPictureAndRadioAcrossRestartsAndPictureWins() {
        memory.markRadio("ch:radio")
        memory.markHadPicture("ch:tv")
        val reopened = PrefsRecoveryMemory(prefs) { now }
        assertEquals(true, reopened.isRadio("ch:radio"))
        assertEquals(false, reopened.hadPicture("ch:radio"))
        assertEquals(true, reopened.hadPicture("ch:tv"))
        // Zeigt ein als Radio gemerkter Sender später ein Bild, ist er keins mehr.
        reopened.markHadPicture("ch:radio")
        assertEquals(false, reopened.isRadio("ch:radio"))
        assertEquals(true, reopened.hadPicture("ch:radio"))
    }

    @Test
    fun pictureAndRadioFlagsExpireAndAreBounded() {
        memory.markRadio("ch:radio")
        memory.markHadPicture("ch:tv")
        now += 91L * 24 * 60 * 60 * 1000
        assertEquals(false, memory.isRadio("ch:radio"))
        assertEquals(false, memory.hadPicture("ch:tv"))
        (1..1_020).forEach {
            now += 1
            memory.markRadio("r$it")
        }
        assertEquals(1_000, prefs.all.keys.count { it.startsWith("radio:") })
        assertEquals(false, memory.isRadio("r1"))
        assertEquals(true, memory.isRadio("r1020"))
    }

    @Test
    fun pictureFlagIsNotRewrittenMoreThanOncePerDay() {
        memory.markHadPicture("ch:tv")
        val first = prefs.getLong("pic:ch:tv", 0L)
        now += 60_000
        memory.markHadPicture("ch:tv")
        assertEquals(first, prefs.getLong("pic:ch:tv", 0L))
        now += 25L * 60 * 60 * 1000
        memory.markHadPicture("ch:tv")
        assertEquals(true, prefs.getLong("pic:ch:tv", 0L) > first)
    }
}
