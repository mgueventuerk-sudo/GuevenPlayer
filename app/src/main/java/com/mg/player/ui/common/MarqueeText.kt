package com.mg.player.ui.common

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text

/**
 * Einzeiliger Text, der lange Namen lesbar macht (ab 3.43, wie bei Apple TV): Ohne Fokus endet er mit „…“, hat seine
 * Kachel/Zeile den Fokus ([active], Standard: [LocalSurfaceFocused]), läuft er nach kurzer Pause als Laufschrift
 * durch – so ist z. B. „|DE| ✪ NEUE FILME 2025 (DEUTSCH)“ vollständig lesbar. Kurze Texte bewegen sich nie.
 *
 * Mit [maxLines] > 1 verhält er sich wie ein normaler Text (mehrzeilig, „…“ am Ende).
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    @Suppress("UNUSED_PARAMETER", "UnusedParameter") overflow: TextOverflow = TextOverflow.Ellipsis,
    style: TextStyle = LocalTextStyle.current,
    active: Boolean = LocalSurfaceFocused.current,
) {
    val scroll = active && maxLines == 1
    Text(
        text = text,
        modifier = if (scroll) {
            modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = MARQUEE_START_DELAY_MS,
                repeatDelayMillis = MARQUEE_REPEAT_DELAY_MS,
                spacing = MarqueeSpacing(32.dp),
                velocity = 36.dp,
            )
        } else {
            modifier
        },
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        // Laufschrift braucht ungekürzten Text; ohne Fokus „…“.
        maxLines = maxLines,
        overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis,
        softWrap = !scroll,
        style = style,
    )
}

/** Erst lesen lassen, dann laufen: Beim schnellen Durchblättern bewegt sich nichts. */
private const val MARQUEE_START_DELAY_MS = 900
private const val MARQUEE_REPEAT_DELAY_MS = 1_500
