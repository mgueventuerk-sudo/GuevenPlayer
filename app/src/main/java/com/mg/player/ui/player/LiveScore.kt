package com.mg.player.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.MatchedEvent
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.glass
import com.mg.player.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

/** Laufendes Spiel auf diesem Sender samt Spielstand (aus dem Bereich „Sport“); null ohne Sender oder Spiel. */
@Composable
internal fun rememberLiveMatch(channelId: String?): State<MatchedEvent?> {
    val sport = LocalAppContainer.current.sport.repository
    val flow = remember(channelId) { channelId?.let(sport::liveOn) ?: flowOf(null) }
    return flow.collectAsStateWithLifecycle(null)
}

/** „Galatasaray 2:1 Fenerbahçe · 67'“ als kleine Glas-Pille (Overlay-Kopf bzw. Tor-Einblendung). */
@Composable
internal fun LiveScoreChip(match: MatchedEvent, modifier: Modifier = Modifier, label: String? = null) {
    val score = match.event.score ?: return
    val e = match.event
    Row(
        modifier
            .glass(RoundedCornerShape(50), fill = Color.Black.copy(alpha = 0.6f), raised = label != null)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label?.let {
            Text(
                it,
                color = AppColors.Live,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        val teams = if (e.homeTeam != null && e.awayTeam != null) "${e.homeTeam} ${goalsOf(score)} ${e.awayTeam}" else "${e.title} ${goalsOf(score)}"
        Text(teams, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        detailOf(score)?.let { Text("  $it", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary) }
    }
}

/**
 * Kurze Einblendung, wenn sich der Spielstand ändert (Tor), 10 s lang. Der erste bekannte Stand nach dem Umschalten
 * löst nichts aus. Bleibt auch bei offenem Overlay aktiv, damit kein Tor verloren geht; sichtbar nur, wenn [visible].
 */
@Composable
internal fun GoalFlash(match: MatchedEvent?, visible: Boolean, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<Pair<MatchedEvent, String>?>(null) }
    var lastGoals by remember(match?.event?.id) { mutableStateOf(match?.event?.score?.let(::goalsOf)) }
    val goals = match?.event?.score?.let(::goalsOf)
    LaunchedEffect(goals) {
        val before = lastGoals
        lastGoals = goals ?: before
        if (match == null || goals == null || before == null || goals == before) return@LaunchedEffect
        shown = match to if (totalOf(goals) > totalOf(before)) "TOR!" else "Spielstand"
        delay(FLASH_MS)
        shown = null
    }
    val current = shown
    if (visible && current != null) LiveScoreChip(current.first, modifier, label = current.second)
}

internal fun goalsOf(score: String): String = score.substringBefore(" · ")

internal fun detailOf(score: String): String? = score.substringAfter(" · ", "").takeIf { it.isNotBlank() }

/** Summe der Tore aus „2:1“ (unlesbar = 0). */
internal fun totalOf(goals: String): Int = goals.split(':').sumOf { it.trim().toIntOrNull() ?: 0 }

private const val FLASH_MS = 10_000L
