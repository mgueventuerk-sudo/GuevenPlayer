package com.mg.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.EpgProgram
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.ProgressBar
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.touchClick
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons
import kotlin.math.abs

/**
 * Runder Material-Knopf für die Bildmitte (Play/Pause, Spulen): mit Fokus hell gefüllt, das Icon animiert sich.
 * Die Beschriftung erscheint nur am fokussierten Knopf – die Bildmitte bleibt ruhig.
 */
@Composable
internal fun RoundControl(icon: AppIcon, label: String, size: Dp, modifier: Modifier = Modifier, showLabel: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(size).touchClick(interaction, onClick),
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
                focusedContainerColor = AppColors.FocusFill,
                focusedContentColor = AppColors.OnFocus,
                pressedContainerColor = AppColors.FocusFill,
                pressedContentColor = AppColors.OnFocus,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.5f), elevation = 18.dp)),
        ) {
            AnimatedIcon(
                icon,
                tint = if (focused) AppColors.OnFocus else Color.White,
                active = focused,
                size = size * 0.46f,
                contentDescription = label,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (showLabel) {
            Text(
                label,
                modifier = Modifier.padding(top = 10.dp).alpha(if (focused) 1f else 0f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) AppColors.TextPrimary else AppColors.TextSecondary,
            )
        }
    }
}

@Composable
internal fun LiveInfo(now: EpgProgram?, next: EpgProgram?) {
    if (now == null) {
        Text("Keine Programminformationen", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        return
    }
    val nowMs = System.currentTimeMillis()
    Text(now.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(TimeUtils.formatClock(now.startMs), style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
        ProgressBar(
            (nowMs - now.startMs).toFloat() / (now.endMs - now.startMs).coerceAtLeast(1),
            Modifier
                .padding(horizontal = 12.dp)
                .width(420.dp),
            height = 4.dp,
        )
        Text(TimeUtils.formatClock(now.endMs), style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
    }
    if (!now.description.isNullOrBlank()) {
        Text(
            now.description,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.7f),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (next != null) {
        Text(
            "Danach: ${TimeUtils.formatClock(next.startMs)}  ${next.title}",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextMuted,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SeekInfo(
    position: State<PlaybackPosition>,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
    /** Am Fernseher: Zeitleiste anwählbar, Links/Rechts verschiebt die Markierung, OK springt. */
    scrubber: Scrubber? = null,
    onScrubCommit: (Long) -> Unit = {},
) {
    // Erst hier gelesen: Die Positions-Updates zeichnen nur diese Zeile neu, nicht das ganze Overlay.
    val (positionMs, durationMs) = position.value
    // Am Handy zieht man den Balken: Während des Ziehens zeigt die Zeitleiste die Zielstelle.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val target = scrubber?.targetMs
    val fraction = dragFraction ?: if (durationMs > 0) (target ?: positionMs).toFloat() / durationMs else 0f
    val shownMs = dragFraction?.let { (it * durationMs).toLong() } ?: target ?: positionMs
    if (scrubber != null && target != null) ScrubLabel(target, target - scrubber.startMs, scrubber.stepMs)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(TimeUtils.formatDuration(shownMs), style = MaterialTheme.typography.labelLarge)
        val bar = Modifier.weight(1f).padding(horizontal = 16.dp)
        if (onSeek != null && durationMs > 0) {
            val seek by rememberUpdatedState(onSeek)
            Box(
                bar
                    .height(32.dp)
                    .semantics { contentDescription = "Zeitleiste" }
                    .pointerInput(Unit) { detectTapGestures { seek(seekFraction(it.x, size.width.toFloat())) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragFraction = seekFraction(it.x, size.width.toFloat()) },
                            onDragEnd = {
                                dragFraction?.let(seek)
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragFraction = seekFraction(change.position.x, size.width.toFloat())
                            },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                ProgressBar(fraction, Modifier.fillMaxWidth(), height = if (dragFraction != null) 8.dp else 5.dp)
            }
        } else if (scrubber != null && durationMs > 0) {
            ScrubBar(fraction, scrubber, position, onScrubCommit, bar.then(modifier))
        } else {
            ProgressBar(fraction, bar, height = 5.dp)
        }
        Text(
            if (durationMs > 0) "-" + TimeUtils.formatDuration(durationMs - shownMs) else "--:--",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ScrubLabel(targetMs: Long, deltaMs: Long, stepMs: Long) {
    val sign = if (deltaMs < 0) "−" else "+"
    val step = if (stepMs % 60_000 == 0L) "${stepMs / 60_000} min" else "${stepMs / 1_000} s"
    Text(
        "Ziel ${TimeUtils.formatDuration(targetMs)} ($sign${TimeUtils.formatDuration(abs(deltaMs))}) · Schritt $step · OK springt",
        modifier = Modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ScrubBar(fraction: Float, scrubber: Scrubber, position: State<PlaybackPosition>, onCommit: (Long) -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(32.dp)
            .onFocusChanged { state ->
                // Fokus verlässt die Zeitleiste (hoch/runter): Markierung verwerfen, kein Sprung.
                if (focused && !state.isFocused) scrubber.cancel()
                focused = state.isFocused
            }
            .onKeyEvent { event -> scrubKey(event, scrubber, position.value, onCommit) }
            .focusable()
            .semantics { contentDescription = "Zeitleiste" },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Apple-Stil: dünne Leiste, im Fokus dicker und mit Knopf an der Zielstelle – ohne Rahmen.
        ProgressBar(fraction, Modifier.fillMaxWidth(), height = if (focused) 8.dp else 4.dp)
        if (focused) {
            Box(
                Modifier
                    .align(BiasAlignment(fraction.coerceIn(0f, 1f) * 2f - 1f, 0f))
                    .size(18.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

/** Tasten auf der fokussierten Zeitleiste: Links/Rechts verschieben, OK springt genau einmal, Zurück bricht ab. */
internal fun scrubKey(event: KeyEvent, scrubber: Scrubber, position: PlaybackPosition, onCommit: (Long) -> Unit): Boolean {
    val down = event.type == KeyEventType.KeyDown
    return when (event.key) {
        Key.DirectionLeft, Key.DirectionRight -> {
            if (down) {
                val dir = if (event.key == Key.DirectionRight) 1 else -1
                scrubber.press(dir, event.nativeKeyEvent.repeatCount > 0, position.positionMs, position.durationMs)
            }
            true
        }

        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            if (!down) scrubber.commit()?.let(onCommit)
            true
        }

        Key.Back -> if (scrubber.active) {
            if (!down) scrubber.cancel()
            true
        } else {
            false
        }

        else -> false
    }
}

/** Stelle auf der Zeitleiste (0…1) für eine Fingerposition. */
fun seekFraction(x: Float, width: Float): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

/** Zustand als kleiner Punkt mit Wort neben dem Titel (LIVE, REC) – ohne eigenen Kasten. */
@Composable
internal fun StatusDot(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f))
    }
}

/** Rote "● REC"-Markierung, solange der aktuelle Sender aufgenommen wird (ohne Overlay oben rechts im Bild). */
@Composable
fun RecBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .glass(RoundedCornerShape(50), fill = Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedIcon(AppIcons.Record, tint = AppColors.Live, active = true, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text("REC", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
internal fun OverlayButton(
    icon: AppIcon,
    label: String,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Nicht verfügbar: ausgegraut, aber an seinem Platz und anwählbar – die Reihe verschiebt sich nie.
    ActionButton(
        label,
        onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
        icon = icon,
        compact = true,
        iconTint = iconTint,
        quiet = true,
    )
}

internal const val DISABLED_ALPHA = 0.4f
