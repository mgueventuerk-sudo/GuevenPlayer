package com.mg.player.ui.sport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.mg.player.domain.sport.EventCountdownTicker
import com.mg.player.domain.sport.formatCountdown

/**
 * Nur dieser Text liest den Takt. derivedStateOf zeichnet ihn nur neu, wenn sich die Zeichenfolge ändert (über 1 h also
 * einmal pro Minute); Karte und Liste werden nie neu komponiert. Ziffern mit fester Breite (tnum), damit nichts springt.
 */
@Composable
fun CountdownText(startMs: Long, ticker: EventCountdownTicker, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val nowState = ticker.now.collectAsStateWithLifecycle()
    val text by remember(startMs) { derivedStateOf { formatCountdown(startMs, nowState.value) } }
    Text(text, style = style.copy(fontFeatureSettings = "tnum"), color = color, modifier = modifier, maxLines = 1)
}
