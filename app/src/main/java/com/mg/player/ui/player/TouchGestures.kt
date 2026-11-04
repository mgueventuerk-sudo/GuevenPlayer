package com.mg.player.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.findActivity
import com.mg.player.ui.common.glass
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Linke oder rechte Bildschirmhälfte – entscheidet, was eine Geste bewirkt. */
enum class TouchSide { LEFT, RIGHT }

fun sideOf(x: Float, width: Float): TouchSide = if (x < width / 2f) TouchSide.LEFT else TouchSide.RIGHT

/** Was ein Doppeltipp bewirkt. */
enum class DoubleTapAction { SEEK_BACK, SEEK_FORWARD, PREVIOUS_CHANNEL, NEXT_CHANNEL }

/** Filme/Serien: links 10 s zurück, rechts 10 s vor. Live: links ein Sender zurück, rechts weiter. */
fun doubleTapAction(side: TouchSide, isLive: Boolean): DoubleTapAction = when {
    isLive && side == TouchSide.LEFT -> DoubleTapAction.PREVIOUS_CHANNEL
    isLive -> DoubleTapAction.NEXT_CHANNEL
    side == TouchSide.LEFT -> DoubleTapAction.SEEK_BACK
    else -> DoubleTapAction.SEEK_FORWARD
}

/** Welche Größe ein senkrechtes Wischen verstellt: rechts die Lautstärke, links die Helligkeit. */
enum class SwipeLevel { VOLUME, BRIGHTNESS }

fun swipeLevelFor(side: TouchSide): SwipeLevel = if (side == TouchSide.RIGHT) SwipeLevel.VOLUME else SwipeLevel.BRIGHTNESS

/**
 * Neuer Wert (0…1) nach einem senkrechten Wischen: nach oben lauter/heller. Einmal über ~80 % der Bildhöhe
 * entspricht dem ganzen Bereich – so reicht ein Daumenwisch von leise bis laut.
 */
fun adjustLevel(current: Float, dragY: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current.coerceIn(0f, 1f)
    return (current - dragY / (heightPx * 0.8f)).coerceIn(0f, 1f)
}

/** Lautstärke 0…1 auf die Stufen des Geräts (z. B. 0…15) abbilden. */
fun volumeIndex(level: Float, maxIndex: Int): Int = (level.coerceIn(0f, 1f) * maxIndex).roundToInt().coerceIn(0, maxIndex)

/** Was die Gesten gerade anzeigen: Balken beim Wischen, kurzer Hinweis nach einem Doppeltipp. */
class TouchFeedback {
    var swipe by mutableStateOf<SwipeLevel?>(null)
    var level by mutableFloatStateOf(0f)
    var hint by mutableStateOf<DoubleTapAction?>(null)
    var hintTick by mutableIntStateOf(0)
}

/**
 * Gesten im Player am Handy: Tippen blendet die Steuerung ein/aus, Doppeltippen links/rechts spult bzw.
 * schaltet um, senkrechtes Wischen rechts ändert die Lautstärke, links die Helligkeit.
 */
@Composable
fun Modifier.playerTouchControls(
    enabled: Boolean,
    isLive: Boolean,
    feedback: TouchFeedback,
    onTap: () -> Unit,
    onAction: (DoubleTapAction) -> Unit,
): Modifier {
    val context = LocalContext.current
    val activity = context.findActivity()
    val levels = remember(context) { DeviceLevels(context) }
    val tap by rememberUpdatedState(onTap)
    val action by rememberUpdatedState(onAction)
    val live by rememberUpdatedState(isLive)
    DisposableEffect(enabled) { onDispose { if (enabled) levels.resetBrightness(activity) } }
    if (!enabled) return this
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { tap() },
                onDoubleTap = { offset ->
                    val result = doubleTapAction(sideOf(offset.x, size.width.toFloat()), live)
                    action(result)
                    feedback.hint = result
                    feedback.hintTick++
                },
            )
        }
        .pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { offset ->
                    val kind = swipeLevelFor(sideOf(offset.x, size.width.toFloat()))
                    feedback.level = if (kind == SwipeLevel.VOLUME) levels.volume() else levels.brightness(activity)
                    feedback.swipe = kind
                },
                onDragEnd = { feedback.swipe = null },
                onDragCancel = { feedback.swipe = null },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    val kind = feedback.swipe ?: return@detectVerticalDragGestures
                    feedback.level = adjustLevel(feedback.level, dragAmount, size.height.toFloat())
                    if (kind == SwipeLevel.VOLUME) levels.setVolume(feedback.level) else levels.setBrightness(activity, feedback.level)
                },
            )
        }
}

/** Zeigt Balken und Hinweise der Gesten an; der Hinweis verschwindet nach einem Moment. */
@Composable
fun BoxScope.TouchFeedbackLayer(feedback: TouchFeedback) {
    LaunchedEffect(feedback.hintTick) {
        if (feedback.hint != null) {
            delay(700)
            feedback.hint = null
        }
    }
    feedback.swipe?.let { LevelIndicator(it, feedback.level, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) }
    feedback.hint?.let { hint ->
        val left = hint == DoubleTapAction.SEEK_BACK || hint == DoubleTapAction.PREVIOUS_CHANNEL
        DoubleTapHint(hint, Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 72.dp))
    }
}

/** Lautstärke (Medien) und Fensterhelligkeit lesen und setzen. */
class DeviceLevels(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun volume(): Float {
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return 0f
        if (max <= 0) return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(level: Float) {
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeIndex(level, max), 0) }
    }

    /** Helligkeit dieses Fensters; solange nichts gewischt wurde, die des Systems. */
    fun brightness(activity: Activity?): Float {
        val own = activity?.window?.attributes?.screenBrightness ?: -1f
        if (own >= 0f) return own
        val system = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        return (system ?: 128) / 255f
    }

    fun setBrightness(activity: Activity?, level: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
    }

    /** Beim Verlassen des Players gilt wieder die Helligkeit des Systems. */
    fun resetBrightness(activity: Activity?) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
}

/** Anzeige beim Wischen: Symbol und Balken für Lautstärke bzw. Helligkeit. */
@Composable
fun LevelIndicator(kind: SwipeLevel, level: Float, modifier: Modifier = Modifier) {
    val label = if (kind == SwipeLevel.VOLUME) "Lautstärke" else "Helligkeit"
    val percent = (level.coerceIn(0f, 1f) * 100).roundToInt()
    Row(
        modifier
            .glass(RoundedCornerShape(50), fill = AppColors.Panel)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .semantics { contentDescription = "$label $percent %" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StaticIcon(if (kind == SwipeLevel.VOLUME) AppIcons.Audio else AppIcons.Sun, tint = AppColors.TextPrimary, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(140.dp).height(6.dp).clip(RoundedCornerShape(50)).background(AppColors.GlassRaised)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(AppColors.Accent))
        }
        Spacer(Modifier.width(12.dp))
        Text("$percent %", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp))
    }
}

/** Kurzer Hinweis beim Doppeltippen ("−10 s", "+10 s", "Sender −/+"). */
@Composable
fun DoubleTapHint(action: DoubleTapAction, modifier: Modifier = Modifier) {
    val text = when (action) {
        DoubleTapAction.SEEK_BACK -> "−10 s"
        DoubleTapAction.SEEK_FORWARD -> "+10 s"
        DoubleTapAction.PREVIOUS_CHANNEL -> "Sender zurück"
        DoubleTapAction.NEXT_CHANNEL -> "Nächster Sender"
    }
    Text(
        text,
        modifier = modifier.glass(RoundedCornerShape(50), fill = AppColors.Panel).padding(horizontal = 20.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}
