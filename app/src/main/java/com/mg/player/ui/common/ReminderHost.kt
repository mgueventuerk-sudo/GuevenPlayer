package com.mg.player.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mg.player.core.di.AppContainer
import com.mg.player.core.util.TimeUtils
import com.mg.player.data.reminder.LaunchRequest
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.Reminder
import com.mg.player.ui.navigation.Navigator
import com.mg.player.ui.navigation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Fällige Erinnerung bei offener App: Hinweis „Gleich: …“ mit „Jetzt umschalten“ (verschwindet nach einer Minute).
 * Außerdem: Wiedergabe-Wünsche von außen (Benachrichtigung) starten – mit Kindersicherung.
 */
@Composable
fun ReminderHost(container: AppContainer, navigator: Navigator, gate: ParentalGateState) {
    var current by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(container) { container.reminders.due.collect { current = it } }
    LaunchedEffect(navigator) {
        LaunchRequest.requests.collect { request ->
            LaunchRequest.consumed()
            openGuarded(container, gate, navigator, request)
        }
    }
    val reminder = current ?: return
    LaunchedEffect(reminder) {
        delay(60_000)
        current = null
    }
    ConfirmDialog(
        title = "Gleich: ${reminder.title}",
        message = "${reminder.channelName} · ${TimeUtils.formatClock(reminder.startMs)} Uhr",
        confirmText = "Jetzt umschalten",
        dismissText = "Später",
        onConfirm = {
            current = null
            LaunchRequest.play(reminder.channelId)
        },
        onDismiss = { current = null },
    )
}

/**
 * Sender aus einer gesperrten Gruppe nur mit PIN (wie beim Öffnen der Gruppe). Mit App-Sperre erst nach der PIN – wie
 * der Start mit dem letzten Sender in AppRoot. Bis 3.60 startete „Jetzt umschalten“ aus der Benachrichtigung bzw. die
 * Übergabe vom Handy den Player sofort, und der Ton lief hinter der Sperre.
 */
private suspend fun openGuarded(container: AppContainer, gate: ParentalGateState, navigator: Navigator, request: PlayRequest) {
    container.appLock.locked.first { !it }
    val open = { navigator.push(Screen.Player(request)) }
    val channelId = (request as? PlayRequest.Live)?.channelId ?: return open()
    val channel = container.liveRepository.channel(channelId) ?: return
    val locked = container.parentalControl.hasPin.value && !container.parentalControl.unlocked.value &&
        container.categoryRepository.isLocked(channel.categoryId)
    if (locked) gate.runWithPin(open) else open()
}

/**
 * Benachrichtigungen brauchen ab Android 13 eine Erlaubnis. Beim ersten Setzen einer Erinnerung fragen –
 * nicht beim App-Start (dort wüsste niemand, wofür).
 */
@Composable
fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        {
            val missing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (missing) runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}
