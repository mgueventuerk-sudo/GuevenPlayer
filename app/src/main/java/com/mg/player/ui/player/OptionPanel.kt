package com.mg.player.ui.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.player.engine.TrackOption
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.requestFocusWhenReady
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

/** Seitenpanel für Tonspur, Untertitel und Player-Engine. */
@Composable
fun OptionPanel(title: String, options: List<TrackOption>, onSelect: (TrackOption) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(title) { requester.requestFocusWhenReady() }
    val focusIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    PanelFrame(title, onClose, modifier) {
        Spacer(Modifier.height(16.dp))
        if (options.isEmpty()) {
            Text(
                "Für diesen Stream sind keine Optionen verfügbar.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
            // Platzhalter, damit der Fokus im Panel bleibt und Zurück funktioniert.
            ListSurface(onClick = {}, modifier = Modifier.padding(top = 12.dp).focusRequester(requester)) {
                Text("OK", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            return@PanelFrame
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(options.size, key = { options[it].id }, contentType = { "option" }) { index ->
                val option = options[index]
                ListSurface(
                    onClick = { onSelect(option) },
                    selected = option.selected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .then(if (index == focusIndex) Modifier.focusRequester(requester) else Modifier),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MarqueeText(
                            option.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (option.selected) {
                            AnimatedIcon(AppIcons.Check, tint = RowColors.accent, active = true, size = 22.dp)
                        }
                    }
                }
            }
        }
    }
}
