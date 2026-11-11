package com.mg.player.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium,
        color = AppColors.TextPrimary,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextMuted,
        letterSpacing = 1.4.sp,
    )
}

/** Dünner Fortschrittsbalken (EPG, Weiterschauen, Episoden). */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, color: Color? = null, trackColor: Color = AppColors.ProgressTrack) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(trackColor),
    ) {
        val fill = Modifier
            .fillMaxWidth(progress.coerceIn(0f, 1f))
            .height(height)
            .clip(RoundedCornerShape(height))
        Box(if (color != null) fill.background(color) else fill.background(AppColors.brandBrush()))
    }
}

/** Ladeanzeige: ein Ring im Akzentverlauf, der sich dreht und dabei "atmet". */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val transition = rememberInfiniteTransition(label = "loading")
    // Zwei absichtlich verschiedene Perioden (1100/900 ms): Liefen Drehung und Bogenlänge im selben Takt, träfen
    // sich beide Zyklen ständig an derselben Stelle wieder - das sieht mechanisch aus statt organisch. Deshalb
    // bewusst NICHT auf AppMotion.AMBIENT_MS vereinheitlicht (anders als die Einzel-Puls-Effekte in VpnIndicators
    // und GuideComponents, wo nur eine Animation läuft).
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val sweep by transition.animateFloat(60f, 280f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sweep")
    val colors = AppColors.BrandGradient
    val track = AppColors.ProgressTrack
    Canvas(modifier.size(size)) {
        val stroke = size.toPx() * 0.1f
        drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(width = stroke))
        drawArc(
            brush = Brush.sweepGradient(listOf(colors[1], colors[0], colors[1])),
            startAngle = angle,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun CenteredMessage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    loading: Boolean = false,
    icon: AppIcon? = null,
    /** Leerer Zustand mit Ausweg (wie Apple TV): Knopf, der direkt weiterhilft, z. B. „Alle Sender zeigen“. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        // Seitenrand: Am Handy liefen lange Texte sonst bis an den Bildschirmrand.
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            loading -> LoadingIndicator()

            icon != null -> Box(
                Modifier.size(72.dp).glass(CircleShape),
                contentAlignment = Alignment.Center,
            ) { AnimatedIcon(icon, tint = AppColors.TextSecondary, active = true, size = 34.dp) }
        }
        if (loading || icon != null) Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 540.dp),
            )
        }
        if (actionLabel != null && onAction != null && !loading) {
            Spacer(Modifier.height(20.dp))
            ActionButton(actionLabel, onClick = onAction, primary = true)
        }
    }
}

@Composable
fun RatingBadge(rating: Float, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("★", style = MaterialTheme.typography.labelSmall, color = AppColors.Rating)
        Spacer(Modifier.width(3.dp))
        Text("%.1f".format(rating), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** Kleines Qualitäts-Abzeichen ("4K", "FHD", "HEVC" …) für Poster- und Senderkacheln. */
@Composable
fun QualityBadge(text: String, modifier: Modifier = Modifier) {
    val highlight = text == "4K" || text == "8K"
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlight) AppColors.Accent else Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (highlight) AppColors.OnAccent else Color.White,
        maxLines = 1,
    )
}

/** Roter "LIVE"-Punkt mit Text – für laufende Sendungen und Aufnahmen. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "LIVE") {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(AppColors.Live)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(Color.White, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 0.8.sp)
    }
}

/** Kleiner Hinweis-Chip (z. B. "3 Quellen", "Archiv"). */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier, color: Color = AppColors.TextSecondary) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(AppColors.Glass)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}
