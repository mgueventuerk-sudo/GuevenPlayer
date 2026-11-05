package com.mg.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.detail.TmdbAttribution
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.AppText
import com.mg.player.ui.theme.CardSize
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.theme.SmoothShape
import com.mg.player.ui.theme.Spacing

@Composable
internal fun TmdbContentRow(
    title: String,
    entries: List<HomeEntry>,
    state: TmdbLoadState,
    onSeeAll: () -> Unit,
    onClick: (HomeEntry) -> Unit,
    showAttribution: Boolean = false,
    onLongClick: ((HomeEntry) -> Unit)? = null,
) {
    val touch = isTouch
    if (!touch && !showAttribution && entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 5.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (touch) 16.dp else Dimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = if (touch) MaterialTheme.typography.titleLarge else AppText.title,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (showAttribution) TmdbAttribution()
            }
            if (touch) FocusableButton("Alle", AppIcons.ChevronRight, onSeeAll)
        }
        when {
            state is TmdbLoadState.Loading -> SkeletonRow()
            state is TmdbLoadState.Error -> Text(
                "TMDB konnte nicht geladen werden. Prüfe Schlüssel und Internetverbindung.",
                Modifier.padding(horizontal = if (touch) 16.dp else Dimens.ScreenPadding),
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            entries.isEmpty() -> Text(
                "TMDB hat derzeit keine Titel für diese Auswahl.",
                Modifier.padding(horizontal = if (touch) 16.dp else Dimens.ScreenPadding),
                color = AppColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = if (touch) 18.dp else Dimens.ScreenPadding, vertical = if (touch) 10.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap),
            ) {
                items(entries, key = { it.id }, contentType = { "tmdb-home-card" }) { entry ->
                    MediaCard(entry, onClick = { onClick(entry) }, touch = touch, onLongClick = onLongClick?.let { { it(entry) } })
                }
            }
        }
    }
}

@Composable
internal fun <T : Any> ContentRow(
    title: String,
    paged: LazyPagingItems<T>,
    toEntry: (T) -> HomeEntry,
    emptyText: String,
    onSeeAll: () -> Unit,
    onClick: (HomeEntry) -> Unit,
    onLongClick: ((HomeEntry) -> Unit)? = null,
) {
    val touch = isTouch
    if (!touch && paged.itemCount == 0 && paged.loadState.refresh is LoadState.NotLoading) return
    Column(verticalArrangement = Arrangement.spacedBy(if (touch) 10.dp else 5.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (touch) 16.dp else Dimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = if (touch) MaterialTheme.typography.titleLarge else AppText.title,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            if (touch) FocusableButton("Alle", AppIcons.ChevronRight, onSeeAll)
        }
        when {
            paged.itemCount == 0 && paged.loadState.refresh is LoadState.Loading -> SkeletonRow()
            paged.itemCount == 0 && paged.loadState.refresh is LoadState.Error -> Text(
                "Inhalte konnten nicht geladen werden.",
                Modifier.padding(horizontal = if (touch) 16.dp else Dimens.ScreenPadding),
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            paged.itemCount == 0 -> Text(
                emptyText,
                Modifier.padding(horizontal = if (touch) 16.dp else Dimens.ScreenPadding),
                color = AppColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = if (touch) 18.dp else Dimens.ScreenPadding, vertical = if (touch) 10.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap),
            ) {
                items(
                    count = paged.itemCount,
                    key = { index -> paged.peek(index)?.let { toEntry(it).id } ?: "$title-$index" },
                    contentType = { "home-card" },
                ) { index ->
                    paged[index]?.let { value ->
                        val entry = remember(value) { toEntry(value) }
                        MediaCard(entry, onClick = { onClick(entry) }, touch = touch, onLongClick = onLongClick?.let { { it(entry) } })
                    } ?: SkeletonCard(touch)
                }
            }
        }
    }
}

@Composable
internal fun MediaCard(entry: HomeEntry, onClick: () -> Unit, touch: Boolean, onLongClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val live = entry.target is HomeTarget.ChannelTarget
    val imageUrl = if (!touch && !live) entry.posterUrl ?: entry.imageUrl else entry.imageUrl ?: entry.posterUrl
    val request = remember(imageUrl) {
        ImageRequest.Builder(context).data(imageUrl).allowRgb565(true).crossfade(false).build()
    }
    // Gestaltungssystem: am Fernseher eine Größe je Inhaltsart (Poster 2:3, Sender 16:9).
    val width = if (touch) 142.dp else if (live) CardSize.LandscapeWidth else CardSize.PosterWidth
    var focused by remember { mutableStateOf(false) }
    val cardRatio = when {
        touch -> 1.42f
        live -> CardSize.LANDSCAPE_RATIO
        else -> CardSize.POSTER_RATIO
    }
    Column(Modifier.width(width)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(cardRatio).onFocusChanged { focused = it.isFocused },
            shape = if (touch) RoundedCornerShape(18.dp) else SmoothShape(CardSize.Radius),
            focusedScale = if (touch) 1.06f else 1.025f,
            sheen = false,
        ) {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(AppColors.AccentSoft, AppColors.Panel, AppColors.BackgroundAlt))),
                contentAlignment = Alignment.Center,
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(model = request, contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        entry.title,
                        Modifier.padding(12.dp),
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        MarqueeText(
            entry.title,
            Modifier.padding(start = 3.dp, top = if (touch) 7.dp else 4.dp),
            style = if (touch) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Titel unter der Karte: Laufschrift, solange die Karte den Fokus hat.
            active = focused,
        )
        entry.subtitle?.let {
            MarqueeText(
                it,
                Modifier.padding(start = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SkeletonRow() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = if (isTouch) 18.dp else Dimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap),
    ) { items(5) { SkeletonCard(isTouch) } }
}

@Composable
internal fun SkeletonCard(touch: Boolean) {
    Box(
        Modifier
            .width(if (touch) 142.dp else CardSize.PosterWidth)
            .aspectRatio(if (touch) 1.42f else CardSize.POSTER_RATIO)
            .clip(if (touch) RoundedCornerShape(18.dp) else SmoothShape(CardSize.Radius))
            .background(Brush.linearGradient(listOf(AppColors.Glass, AppColors.GlassRaised, AppColors.Glass))),
    )
}

internal fun <T : Any> LazyPagingItems<T>.peekFirst(): T? = if (itemCount > 0) peek(0) else null
