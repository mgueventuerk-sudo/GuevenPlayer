package com.mg.player.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.Channel
import com.mg.player.domain.model.EpgProgram
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.ProgressBar
import com.mg.player.ui.common.QualityBadge
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.SectionLabel
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon

@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Senderlogos sind oft transparente PNGs: bewusst ARGB_8888 (kein RGB_565), sonst wird der Hintergrund schwarz.
    val request = remember(url) { ImageRequest.Builder(context).data(url).size(160, 120).allowRgb565(false).build() }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.LogoTile),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Text(name.take(2).uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
        } else {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/** Zeile der Senderliste: Logo, Name, laufende Sendung, farbiger EPG-Fortschrittsbalken. */
@Composable
fun ChannelRow(
    channel: Channel,
    number: Int,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    measuredResolution: String? = null,
) {
    ListSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = number.toString(),
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.labelLarge,
                color = RowColors.muted,
            )
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 64.dp, height = 44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    measuredResolution?.let { QualityBadge(it, Modifier.padding(start = 8.dp)) }
                    if (channel.variantCount > 1) {
                        // Mehrere Streams desselben Senders unter einer Kachel zusammengefasst.
                        Text(
                            "${channel.variantCount} Quellen",
                            modifier = Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = RowColors.muted,
                            maxLines = 1,
                        )
                    }
                    if (channel.hasArchive) {
                        // Timeshift/Catch-up laut tv_archive der Xtream-API.
                        StaticIcon(
                            AppIcons.Archive,
                            tint = RowColors.muted,
                            size = 15.dp,
                            contentDescription = "Archiv verfügbar",
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                val start = channel.nowStartMs
                val end = channel.nowEndMs
                if (channel.nowTitle != null && start != null && end != null && end > start) {
                    Text(
                        channel.nowTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = RowColors.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    val focused = LocalSurfaceFocused.current
                    ProgressBar(
                        ((nowMs - start).toFloat() / (end - start)),
                        Modifier.fillMaxWidth(),
                        height = 3.dp,
                        color = if (focused) AppColors.OnFocus else null,
                        trackColor = if (focused) AppColors.OnFocus.copy(alpha = 0.22f) else AppColors.ProgressTrack,
                    )
                } else {
                    Text("Keine Programminfo", style = MaterialTheme.typography.bodySmall, color = RowColors.muted)
                }
            }
        }
    }
}

/** Rechte Spalte: aktuelle Sendung mit Beschreibung und die nächsten Sendungen. */
@Composable
fun EpgDetails(channel: Channel?, programs: List<EpgProgram>, nowMs: Long, modifier: Modifier = Modifier) {
    if (channel == null) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            StaticIcon(AppIcons.Guide, tint = AppColors.TextMuted, size = 30.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Wähle einen Sender – hier erscheinen die laufende Sendung und was danach kommt.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextMuted,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        val current = programs.firstOrNull { it.startMs <= nowMs && it.endMs > nowMs }
        if (current != null) {
            Text(
                "${TimeUtils.formatClock(current.startMs)} – ${TimeUtils.formatClock(current.endMs)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.AccentText,
            )
            Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ProgressBar(
                (nowMs - current.startMs).toFloat() / (current.endMs - current.startMs).coerceAtLeast(1),
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            if (!current.description.isNullOrBlank()) {
                Text(
                    current.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text("Für diesen Sender liegt kein EPG vor.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
        }
        val upcoming = programs.filter { it.startMs > nowMs }.take(6)
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionLabel("Danach")
            upcoming.forEach { program ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        TimeUtils.formatClock(program.startMs),
                        modifier = Modifier.width(56.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                    Text(program.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
