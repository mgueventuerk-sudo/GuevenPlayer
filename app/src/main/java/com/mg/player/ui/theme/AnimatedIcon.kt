package com.mg.player.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.Group
import androidx.compose.ui.graphics.vector.Path
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bewegung eines Icon-Teils. Drei Taktgeber:
 * - [pose]: 0 -> 1, solange das Icon aktiv (fokussiert/ausgewählt) ist, und zurück (bleibende Haltung, z. B. Zahnrad gedreht).
 * - [gesture]: läuft beim Aktivieren einmal von 0 nach 1 (kurze Geste, z. B. Antenne wackelt, Klappe schlägt).
 * - [loop]: pendelt endlos 0..1..0, solange aktiv (z. B. pulsierender Aufnahme-Punkt).
 */
@Immutable
data class Motion(
    val pivotX: Float = 12f,
    val pivotY: Float = 12f,
    val wiggle: Float = 0f,
    val tilt: Float = 0f,
    val turn: Float = 0f,
    val spin: Float = 0f,
    val bounce: Float = 0f,
    val pulse: Float = 0f,
    val shiftX: Float = 0f,
    val shiftY: Float = 0f,
    val slideX: Float = 0f,
    val slideY: Float = 0f,
    val redraw: Boolean = false,
) {
    val usesLoop: Boolean get() = pulse != 0f
}

/** Ein Teil eines Icons: Linie (Stroke) oder Fläche (Fill), optional bewegt. */
@Immutable
data class IconPart(val nodes: List<PathNode>, val fill: Boolean = false, val strokeWidth: Float = 1.9f, val motion: Motion = Motion())

@Immutable
class AppIcon(val name: String, val parts: List<IconPart>) {
    val usesLoop: Boolean = parts.any { it.motion.usesLoop }
}

/**
 * Zeichnet ein [AppIcon]. Ist [active] (Fokus oder Auswahl), spielt die zum Motiv passende Animation:
 * einmalige Geste beim Aktivieren, bleibende Haltung oder ein ruhiges Pulsieren.
 */
@Composable
fun AnimatedIcon(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.TextPrimary,
    active: Boolean = false,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    val pose by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(if (active) 420 else 260, easing = FastOutSlowInEasing),
        label = "pose",
    )
    val gesture = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            gesture.snapTo(0f)
            gesture.animateTo(1f, tween(720, easing = LinearEasing))
        } else {
            gesture.snapTo(0f)
        }
    }
    val loop = if (active && icon.usesLoop) loopValue() else 0f
    IconCanvas(icon, tint, pose, gesture.value, loop, size, modifier, contentDescription)
}

@Composable
private fun loopValue(): Float {
    val transition = rememberInfiniteTransition(label = "iconLoop")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "loop",
    )
    return value
}

/** Statisches Icon ohne Animation (z. B. in sehr langen Listen). */
@Composable
fun StaticIcon(icon: AppIcon, modifier: Modifier = Modifier, tint: Color = AppColors.TextPrimary, size: Dp = 24.dp, contentDescription: String? = null) {
    IconCanvas(icon, tint, 0f, 0f, 0f, size, modifier, contentDescription)
}

@Composable
private fun IconCanvas(icon: AppIcon, tint: Color, pose: Float, gesture: Float, loop: Float, size: Dp, modifier: Modifier, contentDescription: String?) {
    val bell = sin(PI * gesture).toFloat()
    val damped = (sin(3 * PI * gesture) * (1 - gesture)).toFloat()
    val painter = rememberVectorPainter(
        defaultWidth = size,
        defaultHeight = size,
        viewportWidth = 24f,
        viewportHeight = 24f,
        name = icon.name,
        tintColor = Color.Unspecified,
        autoMirror = false,
    ) { _, _ ->
        icon.parts.forEach { part ->
            val m = part.motion
            val scale = 1f + m.bounce * bell + m.pulse * loop
            Group(
                rotation = m.wiggle * damped + m.tilt * bell + m.turn * pose + m.spin * gesture,
                pivotX = m.pivotX,
                pivotY = m.pivotY,
                scaleX = scale,
                scaleY = scale,
                translationX = m.shiftX * bell + m.slideX * pose,
                translationY = m.shiftY * bell + m.slideY * pose,
            ) {
                val trimEnd = if (m.redraw && gesture > 0f) gesture else 1f
                if (part.fill) {
                    Path(pathData = part.nodes, fill = SolidColor(tint))
                } else {
                    Path(
                        pathData = part.nodes,
                        stroke = SolidColor(tint),
                        strokeLineWidth = part.strokeWidth,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                        trimPathEnd = trimEnd,
                    )
                }
            }
        }
    }
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier
            .size(size)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    )
}
