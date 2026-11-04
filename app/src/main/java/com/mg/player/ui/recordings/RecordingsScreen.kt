package com.mg.player.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.Recording
import com.mg.player.domain.model.RecordingStatus
import com.mg.player.domain.model.Reminder
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.ScreenTitle
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.edgePadding
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsViewModel(private val container: AppContainer) : ViewModel() {
    private val manager = container.recordingManager
    val reminders: StateFlow<List<Reminder>> = container.reminders.items
    val recordings: StateFlow<List<Recording>> = manager.recordings
    val liveBytes: StateFlow<Map<String, Long>> = manager.liveBytes

    private val _freeBytes = MutableStateFlow(0L)
    val freeBytes: StateFlow<Long> = _freeBytes.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _freeBytes.value = withContext(Dispatchers.IO) { manager.freeBytes() }
                delay(10_000)
            }
        }
    }

    fun stop(id: String) = manager.stop(id)

    fun removeReminder(id: String) = container.reminders.remove(id)

    fun delete(id: String) {
        viewModelScope.launch {
            manager.delete(id)
            _freeBytes.value = withContext(Dispatchers.IO) { manager.freeBytes() }
        }
    }
}

/** Alle Aufnahmen: abspielen, laufende stoppen, alte löschen. */
@Composable
fun RecordingsScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { RecordingsViewModel(it) }
    val navigator = LocalNavigator.current
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val liveBytes by vm.liveBytes.collectAsStateWithLifecycle()
    val freeBytes by vm.freeBytes.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    Column(modifier.fillMaxSize().padding(start = edgePadding, end = edgePadding, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Aufnahmen", Modifier.weight(1f))
            if (freeBytes > 0) {
                Row(
                    Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StaticIcon(AppIcons.Storage, tint = AppColors.TextSecondary, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Frei: ${TimeUtils.formatBytes(freeBytes)}", style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary)
                }
            }
        }
        Text(
            if (isTouch) {
                "Im Player „Aufnehmen“ antippen – oder im Guide eine Sendung gedrückt halten, um sie aufzunehmen " +
                    "oder einzuplanen. Aufnahmen laufen weiter, auch wenn du umschaltest."
            } else {
                "Im Live-TV OK drücken und „Aufnehmen“ wählen – oder im TV-Guide OK auf einer Sendung gedrückt halten, " +
                    "um sie aufzunehmen oder einzuplanen. Aufnahmen laufen weiter, auch wenn du umschaltest."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        AutoRecordRow(Modifier.padding(top = 12.dp))
        if (recordings.isEmpty() && reminders.isEmpty()) {
            CenteredMessage(
                "Noch keine Aufnahmen",
                subtitle = "Im Player über „Aufnehmen“ starten oder im TV-Guide OK auf einer Sendung gedrückt halten.",
                icon = AppIcons.Recordings,
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(FocusRequester.Default),
            contentPadding = PaddingValues(top = 18.dp, bottom = 32.dp, start = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Erinnerungen oben – sie liegen zeitlich vor den Aufnahmen.
            items(reminders, key = { "reminder-${it.id}" }, contentType = { "reminder" }) { reminder ->
                ReminderRow(
                    reminder = reminder,
                    onOpen = { navigator.push(Screen.Player(PlayRequest.Live(reminder.channelId, Category.ALL))) },
                    onDelete = { vm.removeReminder(reminder.id) },
                )
            }
            items(recordings, key = { it.id }, contentType = { "recording" }) { recording ->
                RecordingRow(
                    recording = recording,
                    bytes = liveBytes[recording.id] ?: recording.bytes,
                    nowMs = now,
                    onPlay = { navigator.push(Screen.Player(PlayRequest.Recording(recording.id))) },
                    onStop = { vm.stop(recording.id) },
                    onDelete = { vm.delete(recording.id) },
                )
            }
        }
    }
}

@Composable
private fun RecordingRow(recording: Recording, bytes: Long, nowMs: Long, onPlay: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember(recording.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ListSurface(onClick = { if (!recording.isScheduled) onPlay() }, modifier = Modifier.weight(1f), containerColor = AppColors.Glass) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                val focused = LocalSurfaceFocused.current
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (focused) AppColors.OnFocus.copy(alpha = 0.12f) else AppColors.GlassRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        recording.isActive -> AnimatedIcon(AppIcons.Record, tint = AppColors.Live, active = true, size = 20.dp)
                        recording.isScheduled -> AnimatedIcon(AppIcons.Calendar, tint = RowColors.accent, active = focused, size = 22.dp)
                        else -> AnimatedIcon(AppIcons.Play, tint = RowColors.accent, active = focused, size = 22.dp)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        recording.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            recording.channelName,
                            TimeUtils.formatDate(recording.startedAtMs),
                            TimeUtils.formatDuration(recording.durationMs(nowMs)),
                            TimeUtils.formatBytes(bytes).takeIf { !recording.isScheduled },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = RowColors.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(recording)
            }
        }
        Spacer(Modifier.width(if (isTouch) 8.dp else 12.dp))
        val label = when {
            recording.isActive -> "Stoppen"
            confirmDelete -> "Wirklich löschen?"
            recording.isScheduled -> "Planung löschen"
            else -> "Löschen"
        }
        val action = {
            if (recording.isActive) {
                onStop()
            } else if (confirmDelete) {
                onDelete()
            } else {
                confirmDelete = true
            }
        }
        val icon = if (recording.isActive) AppIcons.Stop else AppIcons.Trash
        if (isTouch) {
            // Am Handy nur das Symbol (rot bei der Rückfrage) – der Platz gehört dem Titel.
            IconActionButton(icon, label, onClick = action, iconTint = if (confirmDelete) AppColors.Danger else null)
        } else {
            ActionButton(label, onClick = action, icon = icon)
        }
    }
}

@Composable
private fun StatusPill(recording: Recording) {
    val (text, color) = when (recording.status) {
        RecordingStatus.SCHEDULED -> "Geplant" to AppColors.AccentText
        RecordingStatus.RECORDING -> "Läuft bis ${TimeUtils.formatClock(recording.plannedEndMs)}" to AppColors.Live
        RecordingStatus.FAILED -> (recording.error ?: "Fehlgeschlagen") to AppColors.Danger
        RecordingStatus.STOPPED -> (recording.error ?: "Gestoppt") to AppColors.TextMuted
        RecordingStatus.DONE -> return
    }
    Text(
        text,
        modifier = Modifier
            .padding(start = 12.dp)
            .background(if (LocalSurfaceFocused.current) AppColors.OnFocus.copy(alpha = 0.12f) else AppColors.GlassRaised, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (LocalSurfaceFocused.current) AppColors.OnFocus else color,
        maxLines = 1,
    )
}
