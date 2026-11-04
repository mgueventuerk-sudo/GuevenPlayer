package com.mg.player.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

/**
 * Inhaltsbeschreibung, die eingeklappt beginnt: Die ersten [teaserLines] Zeilen sind normal lesbar und laufen nach
 * unten weich aus, darunter „Mehr anzeigen“. OK bzw. ein Tipp klappt auf, der nächste wieder ein. Lange
 * TMDB-Beschreibungen füllten vorher bis zu einen halben Bildschirm.
 *
 * Passt der Text ohnehin in [teaserLines] Zeilen, steht er einfach da – ohne Schalter.
 */
@Composable
fun SpoilerDescription(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    teaserLines: Int = 3,
    expandedLines: Int = Int.MAX_VALUE,
) {
    val content = text.trim()
    if (content.isEmpty()) return
    // Erst nach dem Messen bekannt: Ragt der Text über die Vorschau hinaus, gibt es den Schalter.
    var overflows by rememberSaveable(content) { mutableStateOf(false) }
    var expanded by rememberSaveable(content) { mutableStateOf(false) }
    if (!overflows) {
        Text(
            content,
            modifier = modifier,
            style = style,
            color = AppColors.TextSecondary,
            maxLines = teaserLines,
            overflow = TextOverflow.Ellipsis,
            // Mit „…“ meldet hasVisualOverflow nichts (der Text bleibt ja im Rahmen) – entscheidend ist,
            // ob mehr Zeilen da wären, als angezeigt werden.
            onTextLayout = { if (it.didOverflowHeight || it.hasVisualOverflow || it.isLineEllipsized(it.lineCount - 1)) overflows = true },
        )
        return
    }
    FocusSurface(
        onClick = { expanded = !expanded },
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1f,
        containerColor = Color.Transparent,
        glow = false,
        sheen = false,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                content,
                style = style,
                color = RowColors.secondary,
                maxLines = if (expanded) expandedLines else teaserLines,
                overflow = TextOverflow.Clip,
                modifier = if (expanded) Modifier else Modifier.fadeOutBottom(),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedIcon(if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown, tint = RowColors.primary, active = false, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (expanded) "Weniger anzeigen" else "Mehr anzeigen", style = MaterialTheme.typography.labelLarge, color = RowColors.primary)
            }
        }
    }
}

/**
 * Letzte Zeile weich ausblenden (statt „…“): zeigt, dass es weitergeht. Über eine Maske mit BlendMode.DstIn –
 * funktioniert auf allen Android-Versionen, anders als Weichzeichnen (erst ab Android 12).
 */
private fun Modifier.fadeOutBottom(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(FADE_MASK, blendMode = BlendMode.DstIn)
    }

/** Oben voll sichtbar, ab gut der Hälfte nach unten auslaufend (Höhe passt sich der Fläche an). */
private val FADE_MASK = Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent)
