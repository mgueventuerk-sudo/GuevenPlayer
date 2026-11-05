package com.mg.player.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.SearchHit
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.PosterCard
import com.mg.player.ui.common.PosterItem
import com.mg.player.ui.common.QualityBadge
import com.mg.player.ui.common.edgePadding
import com.mg.player.ui.live.ChannelLogo
import com.mg.player.ui.theme.AppColors

/** Suchergebnisse eines Bereichs (Live-TV, Filme oder Serien). */
@Immutable
data class SearchSection(val type: ContentType, val hits: List<SearchHit>) {
    val title: String
        get() = when (type) {
            ContentType.LIVE -> "Live-TV"
            ContentType.MOVIE -> "Filme"
            ContentType.SERIES -> "Serien"
        }
}

/** Teilt die Treffer in die drei Bereiche auf – leere Bereiche entfallen. */
fun List<SearchHit>.toSections(): List<SearchSection> = listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)
    .map { type -> SearchSection(type, filter { it.type == type }) }
    .filter { it.hits.isNotEmpty() }

/**
 * Ergebnisse in drei Reihen: Live-TV (Sender-Kacheln im Querformat), Filme und Serien (schlanke Poster).
 * Hoch/Runter wechselt den Bereich, Links/Rechts blättert innerhalb eines Bereichs.
 */
@Composable
fun SearchResultSections(
    sections: List<SearchSection>,
    onOpen: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sections, key = { it.type.name }, contentType = { "section" }) { section ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(section.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${section.hits.size} Treffer",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.TextMuted,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                LazyRow(
                    modifier = Modifier.focusRestorer(FocusRequester.Default),
                    contentPadding = PaddingValues(start = 6.dp, end = edgePadding, top = 12.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(section.hits, key = { "${it.type}:${it.id}" }, contentType = { it.type.name }) { hit ->
                        if (hit.type == ContentType.LIVE) {
                            ChannelHitTile(hit, onClick = { onOpen(hit) }, modifier = Modifier.width(196.dp))
                        } else {
                            PosterCard(
                                item = PosterItem(hit.id, hit.title, hit.imageUrl, hit.rating, hit.subtitle, hit.badges),
                                onClick = { onOpen(hit) },
                                modifier = Modifier.width(120.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Sender-Treffer: Logo auf dunkler 16:9-Glaskachel, Qualitäts-Abzeichen, darunter Name und Quellen. */
@Composable
private fun ChannelHitTile(hit: SearchHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused },
            focusedScale = 1.08f,
            containerColor = AppColors.LogoTile,
            focusedContainerColor = AppColors.LogoTile,
        ) {
            ChannelLogo(hit.imageUrl, hit.title, Modifier.fillMaxSize().padding(14.dp))
            hit.badges.firstOrNull()?.let { badge ->
                QualityBadge(badge, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
        }
        Column(Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp)) {
            MarqueeText(
                hit.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                color = if (focused) AppColors.TextPrimary else AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.subtitle ?: "Live",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextMuted,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
