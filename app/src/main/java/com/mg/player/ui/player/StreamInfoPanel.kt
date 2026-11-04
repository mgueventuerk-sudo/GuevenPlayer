package com.mg.player.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.requestFocusWhenReady
import com.mg.player.ui.common.trapFocus
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/**
 * Stream-Info im Player-Menü (neben Ton & Untertitel): VPN-Status, tatsächliche Auflösung und Bitrate,
 * aktuelle Downloadrate und Verbindung. Aktualisiert sich jede Sekunde.
 */
@Composable
fun StreamInfoPanel(info: StreamInfo?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocusWhenReady() }
    Column(
        modifier
            .trapFocus()
            .padding(16.dp)
            .width(440.dp)
            .fillMaxHeight()
            .glass(AppShapes.Panel, fill = AppColors.Panel)
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedIcon(AppIcons.Speed, tint = AppColors.AccentText, active = true, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Text("Stream-Info", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Live gemessen, jede Sekunde aktualisiert",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextMuted,
        )
        Spacer(Modifier.height(16.dp))
        val rows = info?.rows
        // Scrollbar: Im Querformat eines Handys ist das Panel nur ~410 dp hoch – sonst fiele "Schließen" heraus.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (rows == null) {
                Text("Wird gemessen …", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            } else {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            row.label,
                            modifier = Modifier.width(140.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextSecondary,
                        )
                        Text(
                            row.value,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (row.highlight) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (row.highlight) AppColors.AccentText else AppColors.TextPrimary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Fokussierbarer Knopf: hält den Fokus im Panel (Pfeiltasten zappen nicht versehentlich) und schließt es.
        ActionButton("Schließen", onClick = onClose, icon = AppIcons.Close, modifier = Modifier.fillMaxWidth().focusRequester(requester))
    }
}
