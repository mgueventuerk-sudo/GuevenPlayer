package com.mg.player.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mg.player.domain.model.TmdbCandidate
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LoadingIndicator
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.TvTextField
import com.mg.player.ui.common.glass
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/** "Falscher Titel? Anders zuordnen": TMDB-Suche mit frei wählbarem Suchbegriff. */
@Composable
fun RematchPanel(
    initialQuery: String,
    candidates: List<TmdbCandidate>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onPick: (TmdbCandidate) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    val context = LocalContext.current
    val searchRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { searchRequester.requestFocus() } }
    Box(Modifier.fillMaxSize().background(AppColors.Scrim), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 520.dp)
                .fillMaxHeight()
                .glass(AppShapes.Panel, fill = AppColors.Panel)
                .padding(28.dp),
        ) {
            Text("Richtigen Titel wählen", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            TvTextField(
                value = query,
                onValueChange = { query = it },
                label = "Suchbegriff",
                imeAction = ImeAction.Search,
                onSubmit = { onSearch(query) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(vertical = 12.dp)) {
                ActionButton("Suchen", onClick = {
                    onSearch(query)
                }, primary = true, icon = AppIcons.Search, modifier = Modifier.focusRequester(searchRequester))
                Spacer(Modifier.width(10.dp))
                ActionButton("Abbrechen", onClick = onClose, icon = AppIcons.Close)
            }
            if (searching) {
                LoadingIndicator(Modifier.padding(16.dp))
            } else if (candidates.isEmpty()) {
                Text("Keine Treffer – anderen Suchbegriff probieren.", color = AppColors.TextSecondary)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(candidates, key = { it.id }, contentType = { "candidate" }) { candidate ->
                    val request = remember(candidate.posterUrl) {
                        ImageRequest.Builder(context).data(candidate.posterUrl).size(120, 180).build()
                    }
                    ListSurface(onClick = { onPick(candidate) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(AppColors.LogoTile)) {
                                if (candidate.posterUrl != null) {
                                    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                MarqueeText(
                                    candidate.title + (candidate.year?.let { " ($it)" } ?: ""),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                candidate.overview?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = RowColors.muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
