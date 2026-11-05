package com.mg.player.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.PlayRequest
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.DetailBackdrop
import com.mg.player.ui.common.EnginePicker
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.MetaLine
import com.mg.player.ui.common.SpoilerDescription
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

@Composable
fun SeriesDetailScreen(seriesId: String) {
    val vm = appViewModel(key = seriesId) { SeriesDetailViewModel(it, seriesId) }
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val series by vm.series.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val season by vm.selectedSeason.collectAsStateWithLifecycle()
    val target by vm.continueTarget.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val info by vm.tmdb.info.collectAsStateWithLifecycle()
    val tmdbEnabled by vm.tmdb.enabled.collectAsStateWithLifecycle()
    val similar by vm.tmdb.similar.collectAsStateWithLifecycle()
    val message by vm.tmdb.message.collectAsStateWithLifecycle()
    val candidates by vm.tmdb.candidates.collectAsStateWithLifecycle()
    val searching by vm.tmdb.searching.collectAsStateWithLifecycle()
    var showEnginePicker by rememberSaveable { mutableStateOf(false) }
    var trailerError by remember { mutableStateOf(false) }
    val continueRequester = remember { FocusRequester() }

    val current = series
    if (current == null) {
        CenteredMessage(if (loading) "Lädt…" else "Serie nicht gefunden", loading = loading, icon = AppIcons.Series)
        return
    }
    val play: (Episode, Long) -> Unit = { ep, pos -> navigator.push(Screen.Player(PlayRequest.Episode(ep.id, current.id, pos, engine))) }
    LaunchedEffect(target != null) { runCatching { continueRequester.requestFocus() } }
    BackHandler(enabled = candidates != null) { vm.tmdb.closeRematch() }
    val tmdbInfo = info
    val seasons = episodes.map { it.season }.distinct()

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(current.backdropUrl ?: tmdbInfo?.backdropUrl ?: current.posterUrl)
        if (candidates == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 56.dp, top = 40.dp, end = 32.dp, bottom = 40.dp),
            ) {
                item(key = "header", contentType = "header") {
                    Column(Modifier.fillMaxWidth(0.6f)) {
                        MarqueeText(current.name, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        MetaLine(
                            listOfNotNull(
                                current.year ?: tmdbInfo?.year,
                                if (seasons.isNotEmpty()) "${seasons.size} Staffel${if (seasons.size == 1) "" else "n"}" else null,
                                if (tmdbInfo?.rating == null) current.rating?.takeIf { it > 0f }?.let { "★ %.1f".format(it) } else null,
                            ),
                            badges = current.badges,
                        )
                        if (tmdbInfo != null) TmdbMeta(tmdbInfo, Modifier.padding(top = 8.dp))
                        SpoilerDescription(
                            tmdbInfo?.overview ?: current.plot.orEmpty(),
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            expandedLines = 10,
                        )
                        (message ?: if (trailerError) "Keine App zum Abspielen des Trailers gefunden" else null)?.let {
                            Text(it, color = AppColors.AccentText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                item(key = "actions", contentType = "actions") {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        target?.let { t ->
                            ActionButton(
                                text = t.label,
                                icon = AppIcons.Play,
                                primary = true,
                                modifier = Modifier.focusRequester(continueRequester),
                                onClick = { play(t.episode, t.positionMs) },
                            )
                        }
                        tmdbInfo?.trailerKey?.let { key ->
                            IconActionButton(AppIcons.Movie, "Trailer", onClick = { trailerError = !openTrailer(context, key) })
                        }
                        IconActionButton(
                            icon = if (isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                            label = if (isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                            iconTint = if (isFavorite) AppColors.Live else null,
                            onClick = vm::toggleFavorite,
                        )
                        IconActionButton(AppIcons.Engine, "Player-Engine", onClick = { showEnginePicker = !showEnginePicker })
                        if (tmdbEnabled) {
                            IconActionButton(AppIcons.Search, "Falsche Serie?", onClick = { vm.tmdb.openRematch(current.name) })
                        }
                    }
                    if (showEnginePicker) EnginePicker(selected = engine, onPick = vm::pickEngine, modifier = Modifier.padding(top = 14.dp))
                }
                if (episodes.isEmpty()) {
                    item(key = "empty", contentType = "message") {
                        Text(
                            if (loading) "Lade Episoden…" else "Keine Episoden gefunden",
                            modifier = Modifier.padding(top = 24.dp),
                            color = AppColors.TextSecondary,
                        )
                    }
                } else {
                    item(key = "seasons", contentType = "seasons") {
                        LazyRow(
                            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).focusRestorer(FocusRequester.Default),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(end = 32.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            items(seasons, key = { "season-$it" }, contentType = { "season" }) { s ->
                                ChoiceChip("Staffel $s", selected = s == season, onClick = { vm.selectSeason(s) })
                            }
                        }
                    }
                    items(episodes.filter { it.season == season }, key = { it.id }, contentType = { "episode" }) { ep ->
                        EpisodeRow(ep, progress[ep.id], modifier = Modifier.fillMaxWidth(0.7f), onClick = {
                            val p = progress[ep.id]
                            play(ep, if (p != null && !p.watched) p.positionMs else 0)
                        })
                    }
                }
                if (tmdbInfo != null) {
                    item(key = "cast", contentType = "cast") { CastRow(tmdbInfo.cast, Modifier.padding(top = 24.dp)) }
                    item(key = "similar", contentType = "similar") {
                        SimilarRow(similar, onOpen = { navigator.push(Screen.SeriesDetail(it.id)) }, modifier = Modifier.padding(top = 16.dp))
                        TmdbAttribution(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        candidates?.let { list ->
            RematchPanel(
                initialQuery = current.name,
                candidates = list,
                searching = searching,
                onSearch = vm.tmdb::search,
                onPick = vm.tmdb::assign,
                onClose = vm.tmdb::closeRematch,
            )
        }
    }
}
