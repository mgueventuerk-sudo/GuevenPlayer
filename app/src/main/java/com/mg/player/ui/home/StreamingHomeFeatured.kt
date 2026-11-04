package com.mg.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.detail.TmdbAttribution
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/** TV-Layout nach der Netflix-Oberfläche: kompakte Fokus-Kacheln, Details direkt darunter, keine Vollbild-Hero-Fläche. */
@Composable
internal fun TvFeaturedSection(
    entries: List<HomeEntry>,
    entry: HomeEntry?,
    loading: Boolean,
    noPlaylist: Boolean,
    favorite: Boolean,
    playRequester: FocusRequester?,
    onFocus: (HomeEntry) -> Unit,
    onAddPlaylist: () -> Unit,
    onPlay: () -> Unit,
    onDetails: (HomeEntry) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSection: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Für dich",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
            color = AppColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (entry != null && entries.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(158.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { "${it.target.type}:${it.target.id}" }, contentType = { "tv-feature" }) { item ->
                    val selected = item.target.type == entry.target.type && item.target.id == entry.target.id
                    val focusModifier = if (item.id == entries.first().id && playRequester != null) {
                        Modifier.focusRequester(playRequester)
                    } else {
                        Modifier
                    }
                    TvFeaturedCard(
                        entry = item,
                        selected = selected,
                        modifier = focusModifier,
                        onFocus = { onFocus(item) },
                        onClick = { onDetails(item) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).padding(end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 31.sp),
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.subtitle?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = AppColors.AccentText, maxLines = 1)
                    }
                    entry.description?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
                            color = AppColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusableButton(
                        when (entry.target) {
                            is HomeTarget.MovieTarget -> "Abspielen"
                            is HomeTarget.SeriesTarget -> "Folge wählen"
                            is HomeTarget.ChannelTarget -> "Live ansehen"
                            is HomeTarget.RecommendationTarget -> "Playlists prüfen"
                        },
                        AppIcons.Play,
                        onPlay,
                        compact = true,
                    )
                    FocusableButton("Mehr Infos", AppIcons.Info, { onDetails(entry) }, compact = true)
                    if (entry.target !is HomeTarget.ChannelTarget && entry.target !is HomeTarget.RecommendationTarget) {
                        FocusableButton(
                            if (favorite) "Entfernen" else "Zur Liste",
                            if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                            onToggleFavorite,
                            compact = true,
                        )
                    }
                }
            }
        } else {
            if (loading) SkeletonRow()
            Text(
                when {
                    loading -> "Deine Empfehlungen werden geladen …"
                    noPlaylist -> "Füge einen Zugang hinzu, damit hier Filme, Serien und Live-TV erscheinen."
                    else -> "Sobald deine Bibliothek Inhalte enthält, findest du sie hier."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
            val emptyFocusModifier = playRequester?.let { Modifier.focusRequester(it) } ?: Modifier
            if (noPlaylist) {
                FocusableButton("Zugang hinzufügen", AppIcons.Plus, onAddPlaylist, emptyFocusModifier, compact = true)
            } else if (!loading) {
                FocusableButton("Live TV öffnen", AppIcons.LiveTv, { onOpenSection(HomeViewModel.SECTION_LIVE) }, emptyFocusModifier, compact = true)
            }
        }
    }
}

@Composable
internal fun TvFeaturedCard(
    entry: HomeEntry,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageUrl = if (selected) entry.imageUrl ?: entry.posterUrl else entry.posterUrl ?: entry.imageUrl
    val request = remember(imageUrl) {
        ImageRequest.Builder(context).data(imageUrl).allowRgb565(true).crossfade(false).build()
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier
            .width(if (selected) 360.dp else 132.dp)
            .height(150.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.015f,
        containerColor = Color.Transparent,
        focusedContainerColor = Color.Transparent,
        glow = false,
        sheen = false,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(AppColors.AccentSoft, AppColors.Panel, AppColors.BackgroundAlt)))) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(model = request, contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (selected) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, AppColors.Background.copy(alpha = 0.86f))),
                    ),
                )
                Text(
                    entry.title,
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp),
                    color = AppColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun TmdbSetupPrompt(onOpenSettings: () -> Unit) {
    val touch = isTouch
    FocusSurface(
        onClick = onOpenSettings,
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (touch) 12.dp else Dimens.ScreenPadding),
        shape = RoundedCornerShape(22.dp),
        focusedScale = 1.02f,
        sheen = false,
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("TMDB-Highlights aktivieren", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "Hinterlege den TMDB-API-Schlüssel unter Einstellungen › Serien. " +
                    "Die Empfehlungen werden mit deinen abspielbaren Filmen und Serien abgeglichen.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
            TmdbAttribution()
        }
    }
}
