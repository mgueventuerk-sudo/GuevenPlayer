package com.mg.player.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.theme.StaticIcon

/** Gemeinsame Darstellung für Film- und Serien-Poster. */
@Immutable
data class PosterItem(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val rating: Float?,
    val subtitle: String?,
    /** Qualitäts-Abzeichen aus dem Titel, z. B. "4K" (max. zwei werden gezeigt). */
    val badges: List<String> = emptyList(),
    /** Wiedergabe-Fortschritt 0..1 für den Balken unten auf dem Poster. */
    val progress: Float? = null,
)

/**
 * 2:3-Poster im Apple-TV-Stil: Bild als abgerundete Glaskachel, Titel darunter. Mit dem Fokus hebt sich die Kachel,
 * ein Lichtreflex wandert darüber und der Titel wird hell. Das Bild wird von Coil auf die Kachelgröße heruntergerechnet
 * und – weil Poster undurchsichtig sind – als RGB_565 gehalten (halber Bitmap-Speicher).
 */
@Composable
fun PosterCard(item: PosterItem, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onFocused: (() -> Unit)? = null) {
    val context = LocalContext.current
    val request = remember(item.imageUrl) {
        ImageRequest.Builder(context)
            .data(item.imageUrl)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardSize.POSTER_RATIO)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                },
            focusedScale = 1.08f,
        ) {
            if (item.imageUrl.isNullOrBlank()) {
                Column(
                    Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StaticIcon(AppIcons.Movie, tint = AppColors.TextMuted, size = 28.dp)
                    Text(
                        text = item.title,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // Ohne feste Größe nimmt Coil automatisch die Maße dieser Box.
                AsyncImage(
                    model = request,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.badges.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    item.badges.take(2).forEach { QualityBadge(it) }
                }
            }
            if (item.rating != null && item.rating > 0f) {
                RatingBadge(item.rating, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            item.progress?.let { progress ->
                ProgressBar(
                    progress,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    height = 4.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
            }
        }
        Column(Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                color = if (focused) AppColors.TextPrimary else AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Flüssiges Poster-Grid mit Paging, stabilen Keys und contentType. */
@Composable
fun <T : Any> PagingPosterGrid(
    items: LazyPagingItems<T>,
    key: (T) -> String,
    toPoster: (T) -> PosterItem,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "Keine Einträge",
    restoreKey: String? = null,
    onLongClick: ((T) -> Unit)? = null,
    emptyIcon: AppIcon? = null,
    minCellWidth: Dp = 104.dp,
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, end = Dimens.ScreenPadding, top = 14.dp, bottom = 28.dp),
    spacing: Dp = 18.dp,
) {
    if (items.itemCount == 0) {
        val loading = items.loadState.refresh is androidx.paging.LoadState.Loading
        CenteredMessage(title = if (loading) "Lädt…" else emptyText, loading = loading, modifier = modifier, icon = emptyIcon)
        return
    }
    val backdrop = LocalBackdrop.current
    val gridState = rememberLazyGridState()
    // Nach "Zurück" aus der Detailseite: Fokus wieder auf das zuletzt gewählte Poster.
    val restoreRequester = remember { FocusRequester() }
    var restorePending by remember { mutableStateOf(restoreKey != null) }
    LazyVerticalGrid(
        // Schlanke Kacheln: ca. 104–150 dp breit, je nach Bildschirm 5–7 Spalten.
        columns = GridCells.Adaptive(minSize = minCellWidth),
        state = gridState,
        modifier = modifier.focusRestorer(FocusRequester.Default),
        // Platz rundum, damit die vergrößerte Kachel samt Schatten nicht abgeschnitten wird.
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey(key),
            contentType = items.itemContentType { "poster" },
        ) { index ->
            val item = items[index]
            if (item != null) {
                val poster = remember(item) { toPoster(item) }
                val isRestoreTarget = restorePending && key(item) == restoreKey
                PosterCard(
                    poster,
                    onClick = { onClick(item) },
                    onLongClick = onLongClick?.let { handler -> { handler(item) } },
                    onFocused = { backdrop.url = poster.imageUrl },
                    modifier = if (isRestoreTarget) Modifier.focusRequester(restoreRequester) else Modifier,
                )
                if (isRestoreTarget) {
                    LaunchedEffect(Unit) {
                        runCatching { restoreRequester.requestFocus() }
                        restorePending = false
                    }
                }
            }
        }
    }
}
