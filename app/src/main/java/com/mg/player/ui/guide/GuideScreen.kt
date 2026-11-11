package com.mg.player.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.CategoryName
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Recording
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.ContextAction
import com.mg.player.ui.common.LocalContextMenu
import com.mg.player.ui.common.LocalParentalGate
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.rememberNotificationPermission
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import kotlinx.coroutines.delay

/** TV-Guide: klassisches EPG-Raster mit Live, Archiv (Catch-up) und Vorschau. */
@Composable
fun GuideScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { GuideViewModel(it) }
    val navigator = LocalNavigator.current
    val gate = LocalParentalGate.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategoryId.collectAsStateWithLifecycle()
    val windowStart by vm.windowStart.collectAsStateWithLifecycle()
    val epgVersion by vm.epgVersion.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val recordingKeys by vm.recordingKeys.collectAsStateWithLifecycle()
    val reminderKeys by vm.reminderKeys.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermission()
    var info by remember { mutableStateOf<String?>(null) }
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val contextMenu = LocalContextMenu.current

    fun openProgram(channel: Channel, program: EpgProgram?) {
        vm.resolveAction(channel, program) { action ->
            when (action) {
                is GuideAction.Play -> navigator.push(Screen.Player(action.request))

                is GuideAction.Info -> info = action.message

                is GuideAction.Remind -> {
                    askNotifications()
                    info = vm.toggleReminder(action.reminder)
                }
            }
        }
    }

    LaunchedEffect(info) {
        if (info != null) {
            delay(2_500)
            info = null
        }
    }

    Column(modifier.fillMaxSize().padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding)) {
        Row(verticalAlignment = Alignment.Top) {
            GuideFocusInfo(focus, nowMs, Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("1 Std", onClick = { vm.shiftWindow(-1) }, icon = AppIcons.ChevronLeft, compact = true)
                ActionButton("Jetzt", onClick = vm::resetWindow, icon = AppIcons.Clock, compact = true)
                ActionButton("1 Std", onClick = { vm.shiftWindow(1) }, icon = AppIcons.ChevronRight, compact = true)
            }
        }
        LazyRow(
            modifier = Modifier.padding(vertical = 8.dp).focusRestorer(FocusRequester.Default),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
        ) {
            items(categories, key = { it.id }, contentType = { "chip" }) { category ->
                ChoiceChip(
                    text = CategoryName.display(category.name),
                    selected = category.id == selected,
                    onClick = { gate.runIfAllowed(category) { vm.selectCategory(category) } },
                )
            }
        }
        info?.let { Text(it, color = AppColors.AccentText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val timelineWidth = maxWidth - GUIDE_CHANNEL_WIDTH - 10.dp
            Column {
                TimeHeader(windowStart, timelineWidth)
                if (channels.itemCount == 0) {
                    // Beim Laden ein Gerüst des Rasters statt „Keine Sender“ – die Seite springt danach nicht.
                    if (channels.loadState.refresh is androidx.paging.LoadState.Loading) {
                        GuideSkeleton(timelineWidth)
                    } else {
                        CenteredMessage("Keine Sender in dieser Kategorie", icon = AppIcons.Guide)
                    }
                    return@Column
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().focusRestorer(FocusRequester.Default),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(
                        count = channels.itemCount,
                        key = channels.itemKey { it.id },
                        contentType = channels.itemContentType { "guide-row" },
                    ) { index ->
                        val channel = channels[index] ?: return@items
                        LaunchedEffect(channel.id, windowStart, epgVersion) { vm.loadPrograms(channel) }
                        val programs by vm.programsFor(channel.id)
                        // Ohne remember wären das pro Rekomposition neue Lambda-Instanzen - GuideRow könnte dann
                        // nie überspringen, selbst wenn sich für diese Zeile nichts änderte (z. B. bei einem
                        // epgVersion-Sprung, der diesen ganzen Block neu durchläuft). onLongClick hängt zusätzlich
                        // von recordingKeys ab, sonst zeigte das Kontextmenü nach einer Änderung einen alten Stand.
                        val onFocusChannel = remember(channel) { { program: EpgProgram? -> vm.onFocus(channel, program) } }
                        val onClickChannel = remember(channel) { { program: EpgProgram? -> openProgram(channel, program) } }
                        val onLongClickChannel = remember(channel, recordingKeys) {
                            { program: EpgProgram? ->
                                val recording = program != null && Recording.programKey(channel.id, program.startMs) in recordingKeys
                                contextMenu.show(
                                    title = program?.title ?: channel.name,
                                    subtitle = channel.name.takeIf { program != null },
                                    actions = listOf(
                                        ContextAction("Ansehen", AppIcons.Play) { openProgram(channel, program) },
                                        ContextAction(if (recording) "Aufnahme entfernen" else "Aufnehmen", AppIcons.Record, destructive = recording) {
                                            vm.toggleRecording(channel, program) { message -> info = message }
                                        },
                                    ),
                                )
                            }
                        }
                        GuideRow(
                            channel = channel,
                            number = channel.listNumber(index),
                            programs = programs,
                            windowStart = windowStart,
                            timelineWidth = timelineWidth,
                            nowMs = nowMs,
                            onFocus = onFocusChannel,
                            onClick = onClickChannel,
                            onShift = vm::shiftWindow,
                            // Einheitliches Kontextmenü: Ansehen/Erinnern und Aufnehmen an einer Stelle.
                            onLongClick = onLongClickChannel,
                            recordingKeys = recordingKeys,
                            reminderKeys = reminderKeys,
                        )
                    }
                }
            }
        }
    }
}
