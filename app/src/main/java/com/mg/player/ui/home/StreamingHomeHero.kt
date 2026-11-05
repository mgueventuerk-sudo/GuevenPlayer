package com.mg.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
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
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon

@Composable
internal fun HeroBanner(
    entry: HomeEntry?,
    loading: Boolean,
    noPlaylist: Boolean,
    favorite: Boolean,
    modifier: Modifier,
    playRequester: FocusRequester?,
    onAddPlaylist: () -> Unit,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSection: (String) -> Unit,
) {
    val touch = isTouch
    val context = LocalContext.current
    val request = remember(entry?.imageUrl) {
        ImageRequest.Builder(context).data(entry?.imageUrl).allowRgb565(true).crossfade(false).build()
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (touch) 370.dp else 440.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(AppColors.BackgroundAlt, AppColors.Panel, AppColors.Background))),
    ) {
        if (!entry?.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(AppColors.Background.copy(alpha = 0.08f), AppColors.Background.copy(alpha = 0.72f), AppColors.Background.copy(alpha = 0.98f)),
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, AppColors.Background.copy(alpha = 0.8f)))))
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(if (touch) 1f else 0.7f)
                .padding(if (touch) 20.dp else 34.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Für dich", style = MaterialTheme.typography.labelLarge, color = AppColors.AccentText, fontWeight = FontWeight.SemiBold)
            when {
                entry != null -> {
                    entry.subtitle?.let {
                        MarqueeText(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color = AppColors.AccentText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        entry.title,
                        style = if (touch) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.description?.takeIf(String::isNotBlank) ?: entry.subtitle ?: "Entdecke deine Inhalte und aktuellen Sendungen.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary,
                        maxLines = if (touch) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FocusableButton(
                            text = when (entry.target) {
                                is HomeTarget.MovieTarget -> "Abspielen"
                                is HomeTarget.SeriesTarget -> "Folge wählen"
                                is HomeTarget.ChannelTarget -> "Live ansehen"
                                is HomeTarget.RecommendationTarget -> "Playlists prüfen"
                            },
                            icon = AppIcons.Play,
                            onClick = onPlay,
                            modifier = if (playRequester != null) Modifier.focusRequester(playRequester) else Modifier,
                        )
                        FocusableButton("Mehr Infos", AppIcons.Info, onDetails)
                        if (entry.target !is HomeTarget.ChannelTarget && entry.target !is HomeTarget.RecommendationTarget) {
                            FocusableButton(
                                if (favorite) "Entfernen" else "Zur Liste",
                                if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                                onToggleFavorite,
                            )
                        }
                    }
                }
                loading -> {
                    Text("Deine Startseite", style = MaterialTheme.typography.headlineMedium, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Inhalte werden geladen …", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
                    SkeletonRow()
                }
                noPlaylist -> {
                    Text("Deine Startseite", style = MaterialTheme.typography.headlineMedium, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Füge einen Zugang hinzu, damit hier Filme, Serien und Live-Sender erscheinen.",
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    FocusableButton(
                        "Zugang hinzufügen",
                        AppIcons.Plus,
                        onAddPlaylist,
                        if (playRequester != null) Modifier.focusRequester(playRequester) else Modifier,
                    )
                }
                else -> {
                    Text(
                        "Dein nächster Lieblingsinhalt",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Sobald deine Bibliothek Inhalte enthält, findest du sie hier.",
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    FocusableButton(
                        "Live TV öffnen",
                        AppIcons.LiveTv,
                        { onOpenSection(HomeViewModel.SECTION_LIVE) },
                        if (playRequester != null) Modifier.focusRequester(playRequester) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
internal fun FocusableButton(
    text: String,
    icon: com.mg.player.ui.theme.AppIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 42.dp else 48.dp),
        shape = RoundedCornerShape(28.dp),
        focusedScale = if (compact) 1.02f else 1.04f,
        sheen = false,
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticIcon(icon, tint = AppColors.TextPrimary, size = if (compact) 17.dp else 19.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
            )
        }
    }
}
