package com.mg.player.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.ProgressBar
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon

/** Episode mit Vorschaubild, Fortschrittsbalken und "Gesehen"-Haken. */
@Composable
fun EpisodeRow(episode: Episode, progress: PlaybackProgress?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(episode.imageUrl) { ImageRequest.Builder(context).data(episode.imageUrl).size(320, 180).build() }
    ListSurface(onClick = onClick, modifier = modifier.padding(vertical = 3.dp), containerColor = AppColors.Glass) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(if (isTouch) 120.dp else 160.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.LogoTile),
                contentAlignment = Alignment.Center,
            ) {
                if (!episode.imageUrl.isNullOrBlank()) {
                    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(episode.code, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                }
                if (progress != null && progress.fraction > 0f) {
                    ProgressBar(
                        progress.fraction,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp),
                        height = 3.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(episode.code, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = RowColors.accent)
                Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!episode.plot.isNullOrBlank()) {
                    Text(
                        episode.plot,
                        style = MaterialTheme.typography.bodySmall,
                        color = RowColors.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (progress?.watched == true) {
                StaticIcon(
                    AppIcons.CheckCircle,
                    tint = if (LocalSurfaceFocused.current) AppColors.OnFocus else AppColors.Success,
                    size = 26.dp,
                    contentDescription = "Gesehen",
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}
