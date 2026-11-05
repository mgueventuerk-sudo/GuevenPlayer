package com.mg.player.ui.guide

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.domain.model.Recording
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.glass
import com.mg.player.ui.live.ChannelLogo
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.StaticIcon

internal val GUIDE_ROW_HEIGHT = 58.dp
internal val GUIDE_CHANNEL_WIDTH = 190.dp

/** Zeitleiste über dem Raster (alle 30 Minuten eine Marke). */
@Composable
internal fun TimeHeader(windowStart: Long, timelineWidth: Dp) {
    val dpPerMs = timelineWidth / GuideViewModel.WINDOW_MS.toFloat()
    Row(Modifier.height(28.dp)) {
        Spacer(Modifier.width(GUIDE_CHANNEL_WIDTH + 6.dp))
        Box(Modifier.width(timelineWidth).fillMaxHeight()) {
            for (i in 0 until 6) {
                val t = windowStart + i * GuideViewModel.HOUR / 2
                Text(
                    TimeUtils.formatClock(t),
                    modifier = Modifier.offset(x = dpPerMs * (t - windowStart).toFloat()).padding(start = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

/** Eine Zeile: Sender links, Sendungen als Blöcke proportional zur Dauer. */
@Composable
internal fun GuideRow(
    channel: Channel,
    number: Int,
    programs: List<EpgProgram>,
    windowStart: Long,
    timelineWidth: Dp,
    nowMs: Long,
    onFocus: (EpgProgram?) -> Unit,
    onClick: (EpgProgram?) -> Unit,
    onShift: (Int) -> Unit,
    onLongClick: (EpgProgram?) -> Unit = {},
    recordingKeys: Set<String> = emptySet(),
    /** Sendungen mit Erinnerung (gleiche Schlüssel wie Aufnahmen: Sender@Startzeit). */
    reminderKeys: Set<String> = emptySet(),
) {
    val windowEnd = windowStart + GuideViewModel.WINDOW_MS
    val dpPerMs = timelineWidth / GuideViewModel.WINDOW_MS.toFloat()
    Row(Modifier.height(GUIDE_ROW_HEIGHT).padding(vertical = 2.dp)) {
        Row(
            Modifier
                .width(GUIDE_CHANNEL_WIDTH)
                .fillMaxHeight()
                .glass(RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(number.toString(), modifier = Modifier.width(30.dp), style = MaterialTheme.typography.labelMedium, color = AppColors.TextMuted)
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 44.dp, height = 30.dp))
            MarqueeText(
                channel.name,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(timelineWidth).fillMaxHeight()) {
            val visible = programs.filter { it.endMs > windowStart && it.startMs < windowEnd }
            if (visible.isEmpty()) {
                ProgramCell(
                    title = "Keine Programminfo – OK für Live",
                    time = null,
                    state = CellState.NOW,
                    modifier = Modifier.fillMaxSize(),
                    onFocus = { onFocus(null) },
                    onClick = { onClick(null) },
                    onLongClick = { onLongClick(null) },
                    onEdge = { direction -> onShift(direction) },
                    atStart = true,
                    atEnd = true,
                )
            } else {
                visible.forEach { program ->
                    key(program.startMs) {
                        val start = maxOf(program.startMs, windowStart)
                        val end = minOf(program.endMs, windowEnd)
                        val state = when {
                            program.startMs <= nowMs && program.endMs > nowMs -> CellState.NOW
                            program.endMs <= nowMs && channel.hasArchive -> CellState.ARCHIVE
                            program.endMs <= nowMs -> CellState.PAST
                            else -> CellState.FUTURE
                        }
                        ProgramCell(
                            title = program.title,
                            time = TimeUtils.formatClock(program.startMs),
                            state = state,
                            modifier = Modifier
                                .offset(x = dpPerMs * (start - windowStart).toFloat())
                                .width(dpPerMs * (end - start).toFloat())
                                .fillMaxHeight(),
                            onFocus = { onFocus(program) },
                            onClick = { onClick(program) },
                            onLongClick = { onLongClick(program) },
                            recording = Recording.programKey(channel.id, program.startMs) in recordingKeys,
                            reminder = Recording.programKey(channel.id, program.startMs) in reminderKeys,
                            onEdge = { direction -> onShift(direction) },
                            atStart = program.startMs <= windowStart,
                            atEnd = program.endMs >= windowEnd,
                        )
                    }
                }
            }
            if (nowMs in windowStart until windowEnd) {
                // Jetzt-Linie in Live-Rot.
                Box(
                    Modifier
                        .offset(x = dpPerMs * (nowMs - windowStart).toFloat())
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(AppColors.Live.copy(alpha = 0.85f)),
                )
            }
        }
    }
}

internal enum class CellState { NOW, ARCHIVE, PAST, FUTURE }

@Composable
private fun ProgramCell(
    title: String,
    time: String?,
    state: CellState,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onEdge: (Int) -> Unit,
    atStart: Boolean,
    atEnd: Boolean,
    onLongClick: () -> Unit = {},
    recording: Boolean = false,
    reminder: Boolean = false,
) {
    val container = when (state) {
        CellState.NOW -> AppColors.AccentSoft
        CellState.ARCHIVE -> AppColors.GlassRaised
        CellState.PAST -> AppColors.Glass.copy(alpha = AppColors.Glass.alpha * 0.6f)
        CellState.FUTURE -> AppColors.Glass
    }
    ListSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .padding(end = 2.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { event ->
                // Am Rand des Zeitfensters verschiebt Links/Rechts das Fenster um eine Stunde.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionRight && atEnd -> {
                        onEdge(1)
                        true
                    }

                    event.key == Key.DirectionLeft && atStart -> {
                        onEdge(-1)
                        true
                    }

                    else -> false
                }
            },
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1f,
        containerColor = container,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Roter Punkt: Sendung wird aufgenommen oder ist zur Aufnahme eingeplant.
                if (recording) {
                    StaticIcon(AppIcons.Record, tint = AppColors.Live, size = 11.dp, contentDescription = "Aufnahme")
                    Spacer(Modifier.width(5.dp))
                }
                // Uhr: Erinnerung gesetzt.
                if (reminder) {
                    StaticIcon(AppIcons.Clock, tint = AppColors.AccentText, size = 12.dp, contentDescription = "Erinnerung")
                    Spacer(Modifier.width(5.dp))
                }
                MarqueeText(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (state == CellState.NOW) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (state == CellState.PAST) RowColors.muted else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (time != null) {
                Text(
                    if (state == CellState.ARCHIVE) "$time  ·  Archiv" else time,
                    style = MaterialTheme.typography.labelSmall,
                    color = RowColors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Kopfbereich: Details zur fokussierten Sendung. */
@Composable
internal fun GuideFocusInfo(focus: GuideFocus?, nowMs: Long, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().height(96.dp)) {
        if (focus == null) {
            Text("TV-Guide", style = MaterialTheme.typography.headlineSmall)
            Text("Sendung auswählen: Live, Archiv oder Vorschau.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            return@Column
        }
        val program = focus.program
        Text(focus.channel.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.AccentText)
        MarqueeText(program?.title ?: "Keine Programminfo", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (program != null) {
            val status = when {
                program.startMs <= nowMs && program.endMs > nowMs -> "Läuft gerade – OK zum Ansehen, gedrückt halten zum Aufnehmen"
                program.endMs <= nowMs && focus.channel.hasArchive -> "Vorbei – OK spielt die Aufzeichnung aus dem Archiv"
                program.endMs <= nowMs -> "Vorbei"
                else -> "Demnächst – OK: Erinnerung, gedrückt halten: Aufnahme planen"
            }
            Text(
                "${TimeUtils.formatClock(program.startMs)} – ${TimeUtils.formatClock(program.endMs)}  ·  $status",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextSecondary,
            )
            if (!program.description.isNullOrBlank()) {
                Text(
                    program.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Lade-Gerüst des TV-Guides: Sender-Spalte und Sendungsblöcke als ruhig pulsierende Flächen, in den Maßen des echten
 * Rasters. Mit „Effekte reduzieren“ ohne Pulsieren.
 */
@Composable
internal fun GuideSkeleton(timelineWidth: Dp, rows: Int = 8) {
    val pulse = if (AppColors.reducedEffects) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "guideSkeleton")
        val value by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse",
        )
        value
    }
    // Wechselnde Blockbreiten wie bei echten Sendungen (Anteile der Zeitleiste).
    val pattern = listOf(listOf(0.35f, 0.4f, 0.25f), listOf(0.5f, 0.3f, 0.2f), listOf(0.25f, 0.45f, 0.3f))
    Column(Modifier.graphicsLayer { alpha = pulse }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(GUIDE_CHANNEL_WIDTH).height(52.dp).clip(AppShapes.Row).background(AppColors.Glass))
                Row(Modifier.width(timelineWidth), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pattern[row % pattern.size].forEach { share ->
                        Box(Modifier.weight(share).height(52.dp).clip(AppShapes.Small).background(AppColors.Glass))
                    }
                }
            }
        }
    }
}
