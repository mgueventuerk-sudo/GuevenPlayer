package com.mg.player.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.mg.player.core.PlayerApp
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.ui.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Wecker einer Erinnerung hat geklingelt. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderManager.ACTION_FIRE) return
        val id = intent.getStringExtra(ReminderManager.EXTRA_ID) ?: return
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        (context.applicationContext as PlayerApp).container.reminders.fire(id, visible)
    }
}

/**
 * Wiedergabe-Wünsche von außen (Benachrichtigung „Jetzt umschalten“, Erinnerungs-Hinweis): Die Oberfläche
 * startet sie mit Kindersicherung und Zugangs-Prüfung wie jeden anderen Start.
 */
object LaunchRequest {
    const val EXTRA_CHANNEL = "com.mg.player.extra.PLAY_CHANNEL"

    private val _requests = MutableSharedFlow<PlayRequest>(replay = 1, extraBufferCapacity = 2)
    val requests: SharedFlow<PlayRequest> = _requests

    fun playChannelIntent(context: Context, channelId: String): Intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_CHANNEL, channelId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Aus einem Activity-Intent übernehmen (einmalig – der Intent wird danach geleert). */
    fun take(intent: Intent?) {
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL) ?: return
        intent.removeExtra(EXTRA_CHANNEL)
        _requests.tryEmit(PlayRequest.Live(channelId, Category.ALL))
    }

    fun play(channelId: String) {
        _requests.tryEmit(PlayRequest.Live(channelId, Category.ALL))
    }

    /** Erledigt: nicht beim nächsten Abonnieren erneut ausliefern. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumed() = _requests.resetReplayCache()
}
