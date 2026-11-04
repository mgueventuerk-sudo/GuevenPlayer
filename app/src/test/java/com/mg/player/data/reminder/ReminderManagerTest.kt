package com.mg.player.data.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.Reminder
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Erinnerungen: setzen, löschen, Wecker, Hinweis in der App oder Benachrichtigung. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var now = 1_800_000_000_000L
    private val manager = ReminderManager(context) { now }
    private val tatort = Reminder("1:L:104", "Das Erste", "Tatort", now + 3_600_000)

    @Test
    fun toggleSetsAlarmAndRemoves() {
        assertTrue(manager.toggle(tatort).startsWith("Erinnerung gesetzt"))
        assertTrue(manager.isSet(tatort.channelId, tatort.startMs))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertTrue(alarms.any { it.triggerAtTime == tatort.startMs - ReminderManager.LEAD_MS })
        // Neu geladen (z. B. nach Neustart): noch da.
        assertTrue(ReminderManager(context) { now }.isSet(tatort.channelId, tatort.startMs))
        assertTrue(manager.toggle(tatort).startsWith("Erinnerung an"))
        assertFalse(manager.isSet(tatort.channelId, tatort.startMs))
    }

    @Test
    fun pastProgramsCannotBeReminded() {
        assertEquals("Diese Sendung hat schon begonnen", manager.toggle(tatort.copy(startMs = now - 1)))
    }

    @Test
    fun firesInsideTheAppOrAsNotification() = runBlocking {
        manager.toggle(tatort)
        val due = async { withTimeout(5_000) { manager.due.first() } }
        yield()
        manager.fire(tatort.id, appVisible = true)
        assertEquals(tatort, due.await())
        assertFalse("nach dem Auslösen gelöscht", manager.isSet(tatort.channelId, tatort.startMs))

        val later = tatort.copy(startMs = now + 7_200_000)
        manager.toggle(later)
        manager.fire(later.id, appVisible = false)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(notifications.any { it.extras.getString("android.title") == "Gleich: Tatort" })
    }

    @Test
    fun launchRequestFromNotificationIntent() = runBlocking {
        val intent = LaunchRequest.playChannelIntent(context, "1:L:104")
        LaunchRequest.take(intent)
        assertEquals(PlayRequest.Live("1:L:104", Category.ALL), LaunchRequest.requests.first())
        LaunchRequest.consumed()
        // Einmalig: Der Intent ist danach leer.
        assertFalse(intent.hasExtra(LaunchRequest.EXTRA_CHANNEL))
        LaunchRequest.take(Intent())
    }
}
