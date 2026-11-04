package com.mg.player.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.ConnectionStatus
import com.mg.player.domain.model.subscriptionNotice
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.theme.AppColors

/** Belegte Verbindungen aller Zugänge; wird aktualisiert, solange der aufrufende Bildschirm sichtbar ist. */
@Composable
fun rememberConnections(): Map<Long, ConnectionStatus> {
    val status by LocalAppContainer.current.connectionMonitor.live.collectAsStateWithLifecycle()
    return status
}

/**
 * Abzeichen "1/1": grün, sobald jemand über den Zugang schaut (auch dieses Gerät), grau, wenn er frei ist.
 * Ohne bekannten Stand (M3U ohne Xtream-Daten, offline) erscheint nichts.
 */
@Composable
fun ConnectionBadge(status: ConnectionStatus?, modifier: Modifier = Modifier) {
    if (status == null) return
    val color = if (status.inUse) AppColors.Success else AppColors.TextMuted
    Row(
        modifier
            .semantics { contentDescription = status.description }
            .background(color.copy(alpha = if (status.inUse) 0.16f else 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(status.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Kleine Variante auf dem Avatar-Knopf der Navigationsleiste: nur, wenn der Zugang gerade genutzt wird. */
@Composable
fun ConnectionDot(status: ConnectionStatus?, modifier: Modifier = Modifier) {
    if (status == null || !status.inUse) return
    Box(
        modifier
            .semantics { contentDescription = status.description }
            .background(AppColors.Success, RoundedCornerShape(50))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(status.label, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF05250F))
    }
}

/**
 * Zweite Statuszeile eines Zugangs: Abo-Ende (gelb ab 7 Tage vorher, rot wenn abgelaufen) und
 * „alle Verbindungen belegt“. Ohne Kontodaten (M3U, offline) erscheint nichts.
 */
@Composable
fun AccountStatusLine(status: ConnectionStatus?, modifier: Modifier = Modifier) {
    if (status == null) return
    val notice = status.subscriptionNotice(System.currentTimeMillis())
    val parts = listOfNotNull(notice?.text, "alle Verbindungen belegt".takeIf { status.full })
    if (parts.isEmpty()) return
    val color = when {
        notice?.expired == true -> AppColors.Danger
        notice?.warning == true -> AppColors.Rating
        status.full -> AppColors.Success
        else -> AppColors.TextMuted
    }
    Text(parts.joinToString("  ·  "), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}
