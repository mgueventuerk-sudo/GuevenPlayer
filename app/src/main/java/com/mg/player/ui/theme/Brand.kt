package com.mg.player.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

const val APP_NAME = "GuevenPlayer"

/** Markenfarben (wie App-Icon und TV-Banner): Rot-Verlauf mit gelber Bildmarke. */
private val BrandRed = Color(0xFFDE1E2C)
private val BrandRedDark = Color(0xFF8C0626)
val BrandYellow = Color(0xFFFDB912)

/**
 * Bildmarke von GuevenPlayer (wie das App-Icon): rote Verlaufs-Kachel, darin ein offener gelber Ring (das "G")
 * mit einem Play-Dreieck, das in die Öffnung zeigt.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(
            brush = Brush.linearGradient(listOf(BrandRed, BrandRedDark), start = Offset.Zero, end = Offset(s, s)),
            cornerRadius = CornerRadius(s * 0.28f, s * 0.28f),
        )
        val stroke = s * 0.095f
        val radius = s * 0.29f
        val center = Offset(s / 2f, s / 2f)
        drawArc(
            color = BrandYellow,
            startAngle = 38f,
            sweepAngle = 284f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val triangle = Path().apply {
            moveTo(center.x - s * 0.085f, center.y - s * 0.115f)
            lineTo(center.x + s * 0.135f, center.y)
            lineTo(center.x - s * 0.085f, center.y + s * 0.115f)
            close()
        }
        drawPath(triangle, BrandYellow)
    }
}

/** Schriftzug "GuevenPlayer": "Gueven" in der Textfarbe, "Player" in den Markenfarben (dunkel gelb, hell rot). */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 22.sp) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = AppColors.TextPrimary)) { append("Gueven") }
            withStyle(SpanStyle(color = if (AppColors.isDark) BrandYellow else BrandRed)) { append("Player") }
        },
        modifier = modifier,
        fontFamily = InterDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
    )
}

/** Bildmarke plus Schriftzug nebeneinander. */
@Composable
fun BrandLogo(modifier: Modifier = Modifier, markSize: Dp = 44.dp, fontSize: TextUnit = 26.sp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BrandMark(size = markSize)
        Spacer(Modifier.width(markSize * 0.3f))
        BrandWordmark(fontSize = fontSize)
    }
}
