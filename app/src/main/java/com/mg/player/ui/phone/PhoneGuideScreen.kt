package com.mg.player.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Recording
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.ProgressBar
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.rememberNotificationPermission
import com.mg.player.ui.guide.GuideAction
import com.mg.player.ui.guide.GuideViewModel
import com.mg.player.ui.live.ChannelLogo
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon
import kotlinx.coroutines.delay

/**
 * Zeitpunkte für "Was läuft um …" wie auf einem Zeitstrahl: volle Stunden davor (6 h fürs Archiv), "Jetzt"
 * (null) und die vollen Stunden danach (12 h).
 */
internal fun guideTimes(nowMs: Long): List<Long?> {
    val hour = GuideViewModel.HOUR
    val base = nowMs - nowMs % hour
    return (-6..-1).map { base + it * hour } + listOf<Long?>(null) + (1..12).map { base + it * hour }
}

/** Die Zeitleiste startet eine Stunde vor "Jetzt" – so sieht man, dass man auch zurückwischen kann. */
internal const val GUIDE_TIMES_FIRST_VISIBLE = 5

/** Die Sendung, die zum Zeitpunkt [atMs] läuft, und die danach. */
internal fun programsAt(programs: List<EpgProgram>, atMs: Long): Pair<EpgProgram?, EpgProgram?> {
    val sorted = programs.sortedBy { it.startMs }
    val index = sorted.indexOfFirst { it.startMs <= atMs && it.endMs > atMs }
    if (index < 0) return null to sorted.firstOrNull { it.startMs > atMs }
    return sorted[index] to sorted.getOrNull(index + 1)
}

/**
 * TV-Guide am Handy: statt des breiten Rasters eine Liste "Was läuft um …". Oben wählt man den Zeitpunkt
 * (jetzt, frühere Stunden fürs Archiv, spätere zum Vormerken), jede Zeile zeigt die Sendung zu dieser Zeit
 * und die danach. Antippen: ansehen (live oder aus dem Archiv), gedrückt halten: aufnehmen bzw. einplanen.
 */
@Composable
fun PhoneGuideScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { GuideViewModel(it) }
    val navigator = LocalNavigator.current
    val toast = LocalToast.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategoryId.collectAsStateWithLifecycle()
    val windowStart by vm.windowStart.collectAsStateWithLifecycle()
    val epgVersion by vm.epgVersion.collectAsStateWithLifecycle()
    val recordingKeys by vm.recordingKeys.collectAsStateWithLifecycle()
    val reminderKeys by vm.reminderKeys.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermission()
    val channels = vm.channels.collectAsLazyPagingItems()
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    // null = "Jetzt" (läuft mit der Uhr mit).
    val at = remember { mutableStateOf<Long?>(null) }
    val atMs = at.value ?: nowMs
    LaunchedEffect(at.value) {
        val target = at.value
        if (target == null) vm.resetWindow() else vm.setWindowStart(target - GuideViewModel.HOUR / 2)
    }

    Column(modifier) {
        CategoryChipRow(categories, selected, onSelect = vm::selectCategory)
        LazyRow(
            state = rememberLazyListState(initialFirstVisibleItemIndex = GUIDE_TIMES_FIRST_VISIBLE),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(guideTimes(nowMs), key = { it ?: 0L }) { time ->
                ChoiceChip(
                    text = if (time == null) "Jetzt" else TimeUtils.formatClock(time),
                    selected = at.value == time,
                    onClick = { at.value = time },
                    icon = if (time == null) AppIcons.Clock else null,
                )
            }
        }
        if (channels.itemCount == 0) {
            CenteredMessage("Keine Sender in dieser Kategorie", icon = AppIcons.Guide)
            return@Column
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(
                count = channels.itemCount,
                key = channels.itemKey { it.id },
                contentType = channels.itemContentType { "guide-row" },
            ) { index ->
                val channel = channels[index] ?: return@items
                LaunchedEffect(channel.id, windowStart, epgVersion) { vm.loadPrograms(channel) }
                val programs by vm.programsFor(channel.id)
                val (current, next) = programsAt(programs, atMs)
                PhoneGuideRow(
                    channel = channel,
                    number = channel.listNumber(index),
                    program = current,
                    next = next,
                    nowMs = nowMs,
                    recording = current != null && Recording.programKey(channel.id, current.startMs) in recordingKeys,
                    reminder = current != null && Recording.programKey(channel.id, current.startMs) in reminderKeys,
                    onClick = {
                        vm.resolveAction(channel, current) { action ->
                            when (action) {
                                is GuideAction.Play -> navigator.push(Screen.Player(action.request))

                                is GuideAction.Info -> toast.show(action.message.replace("OK gedrückt halten", "Gedrückt halten"))

                                is GuideAction.Remind -> {
                                    askNotifications()
                                    toast.show(vm.toggleReminder(action.reminder))
                                }
                            }
                        }
                    },
                    onLongClick = { vm.toggleRecording(channel, current) { toast.show(it) } },
                )
            }
        }
    }
}

@Composable
private fun PhoneGuideRow(
    channel: Channel,
    number: Int,
    program: EpgProgram?,
    next: EpgProgram?,
    nowMs: Long,
    recording: Boolean,
    reminder: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListSurface(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth(), containerColor = AppColors.Glass) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 60.dp, height = 40.dp))
                Spacer(Modifier.height(4.dp))
                Text("$number", style = MaterialTheme.typography.labelSmall, color = RowColors.muted)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (recording) {
                        StaticIcon(AppIcons.Record, tint = AppColors.Live, size = 11.dp, contentDescription = "Aufnahme")
                        Spacer(Modifier.width(5.dp))
                    }
                    if (reminder) {
                        StaticIcon(AppIcons.Clock, tint = AppColors.AccentText, size = 12.dp, contentDescription = "Erinnerung")
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        program?.title ?: "Keine Programminfo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Sendername immer zeigen – nicht jedes Logo ist eindeutig (oder überhaupt vorhanden).
                val status = listOfNotNull(
                    channel.name,
                    program?.let { "${TimeUtils.formatClock(it.startMs)} – ${TimeUtils.formatClock(it.endMs)}" },
                    when {
                        program == null -> null
                        program.startMs <= nowMs && program.endMs > nowMs -> "läuft"
                        program.endMs <= nowMs && channel.hasArchive -> "Archiv"
                        else -> null
                    },
                ).joinToString("  ·  ")
                Text(status, style = MaterialTheme.typography.labelMedium, color = RowColors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (program != null && program.startMs <= nowMs && program.endMs > nowMs) {
                    val focused = LocalSurfaceFocused.current
                    ProgressBar(
                        (nowMs - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1),
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        height = 3.dp,
                        color = if (focused) AppColors.OnFocus else null,
                    )
                }
                if (next != null) {
                    Text(
                        "Danach ${TimeUtils.formatClock(next.startMs)}  ${next.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = RowColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
