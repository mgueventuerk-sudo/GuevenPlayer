package com.mg.player.ui.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.runCatchingCancellable
import com.mg.player.data.reminder.LaunchRequest
import com.mg.player.domain.model.MatchedEvent
import com.mg.player.domain.model.Reminder
import com.mg.player.domain.sport.formatCountdown
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.common.rememberNotificationPermission
import com.mg.player.ui.common.requestFocusWhenReady
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/**
 * Kommendes Spiel: Countdown, Sender und was dort gerade läuft. Der Fokus liegt auf „Erinnerung setzen“ –
 * „Sender jetzt ansehen“ startet den Sender sofort (dort läuft dann noch das aktuelle Programm). „Zurück“ schließt.
 */
@Composable
fun SportEventDialog(item: MatchedEvent, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val toast = LocalToast.current
    val askPermission = rememberNotificationPermission()
    val reminders by container.reminders.items.collectAsStateWithLifecycle()
    val reminderSet = reminders.any { it.channelId == item.channelId && it.startMs == item.event.startMs }
    val nowOnChannel by produceState<String?>(null, item.channelId) {
        val channel = container.liveRepository.channel(item.channelId) ?: return@produceState
        val now = System.currentTimeMillis()
        value = runCatchingCancellable { container.epgRepository.programs(channel, now, now + 1).firstOrNull()?.title }.getOrNull()
    }
    val message = remember(item, nowOnChannel) {
        buildString {
            append("${item.event.league} · ${kickoff(item.event.startMs)} Uhr\n")
            append("Beginnt ${formatCountdown(item.event.startMs, System.currentTimeMillis())} auf ${item.channelName}.")
            // Ehrlich bleiben: Ein von Hand gewählter Ersatzsender (oder seine Reihe) ist nicht belegt.
            if (item.guessed) append(" Sender vermutet – keine Sendeliste und kein Programm nennen das Spiel.")
            nowOnChannel?.let { append(" Dort läuft gerade „$it“.") }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val firstRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstRequester.requestFocusWhenReady() }
        Box(Modifier.fillMaxSize().background(AppColors.Scrim), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 560.dp)
                    .glass(AppShapes.Panel, fill = AppColors.Panel)
                    .padding(horizontal = if (isTouch) 24.dp else 40.dp, vertical = 28.dp)
                    .focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(item.event.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        if (reminderSet) "Erinnerung löschen" else "Erinnerung setzen",
                        onClick = {
                            if (!reminderSet) askPermission()
                            toast.show(container.reminders.toggle(Reminder(item.channelId, item.channelName, item.event.title, item.event.startMs)))
                            onDismiss()
                        },
                        icon = AppIcons.Clock,
                        primary = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstRequester),
                    )
                    ActionButton(
                        "Sender jetzt ansehen",
                        onClick = {
                            onDismiss()
                            LaunchRequest.play(item.channelId)
                        },
                        icon = AppIcons.Play,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ActionButton("Abbrechen", onClick = onDismiss, icon = AppIcons.Close, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
