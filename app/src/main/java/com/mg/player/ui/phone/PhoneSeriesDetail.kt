package com.mg.player.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.mg.player.ui.common.EnginePicker
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.MetaLine
import com.mg.player.ui.common.SpoilerDescription
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.detail.CastRow
import com.mg.player.ui.detail.EpisodeRow
import com.mg.player.ui.detail.RematchPanel
import com.mg.player.ui.detail.SeriesDetailViewModel
import com.mg.player.ui.detail.SimilarRow
import com.mg.player.ui.detail.TmdbAttribution
import com.mg.player.ui.detail.TmdbMeta
import com.mg.player.ui.detail.openTrailer
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

/** Serien-Details am Handy: Titelbild, Weiterschauen, Staffeln als Chips und die Folgen untereinander. */
@Composable
fun PhoneSeriesDetail(seriesId: String) {
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

    val current = series
    if (current == null) {
        CenteredMessage(if (loading) "Lädt…" else "Serie nicht gefunden", loading = loading, icon = AppIcons.Series)
        return
    }
    val play: (Episode, Long) -> Unit = { ep, pos -> navigator.push(Screen.Player(PlayRequest.Episode(ep.id, current.id, pos, engine))) }
    BackHandler(enabled = candidates != null) { vm.tmdb.closeRematch() }
    val tmdb = info
    val seasons = episodes.map { it.season }.distinct()
    // Lief bisher im LazyColumn-Content-Lambda - das filtert bei jeder Rekomposition neu (z. B. wenn progress
    // emittiert), statt nur wenn episodes oder season sich tatsächlich ändern.
    val seasonEpisodes = remember(episodes, season) { episodes.filter { it.season == season } }

    Box(Modifier.fillMaxSize()) {
        if (candidates == null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "header", contentType = "header") {
                    PhoneDetailHeader(current.backdropUrl ?: tmdb?.backdropUrl ?: current.posterUrl, onBack = navigator::pop)
                }
                item(key = "info", contentType = "info") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(current.name, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        MetaLine(
                            listOfNotNull(
                                current.year ?: tmdb?.year,
                                if (seasons.isNotEmpty()) "${seasons.size} Staffel${if (seasons.size == 1) "" else "n"}" else null,
                                if (tmdb?.rating == null) current.rating?.takeIf { it > 0f }?.let { "★ %.1f".format(it) } else null,
                            ),
                            badges = current.badges,
                        )
                        if (tmdb != null) TmdbMeta(tmdb, Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()))
                        Spacer(Modifier.height(16.dp))
                        target?.let { t ->
                            ActionButton(t.label, onClick = {
                                play(t.episode, t.positionMs)
                            }, icon = AppIcons.Play, primary = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                        }
                        PhoneActionRow {
                            tmdb?.trailerKey?.let { key ->
                                IconActionButton(AppIcons.Movie, "Trailer", onClick = { trailerError = !openTrailer(context, key) })
                            }
                            IconActionButton(
                                icon = if (isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                                label = if (isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                                iconTint = if (isFavorite) AppColors.Live else null,
                                onClick = vm::toggleFavorite,
                            )
                            IconActionButton(AppIcons.Engine, "Player-Engine", onClick = { showEnginePicker = !showEnginePicker })
                            if (tmdbEnabled) IconActionButton(AppIcons.Search, "Falsche Serie?", onClick = { vm.tmdb.openRematch(current.name) })
                        }
                        if (showEnginePicker) {
                            EnginePicker(
                                selected = engine,
                                onPick = vm::pickEngine,
                                modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                            )
                        }
                        (message ?: if (trailerError) "Keine App zum Abspielen des Trailers gefunden" else null)?.let {
                            Text(it, color = AppColors.AccentText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        }
                        val plot = tmdb?.overview ?: current.plot
                        if (!plot.isNullOrBlank()) {
                            SpoilerDescription(plot, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                }
                if (episodes.isEmpty()) {
                    item(key = "empty", contentType = "message") {
                        Text(
                            if (loading) "Lade Episoden…" else "Keine Episoden gefunden",
                            modifier = Modifier.padding(20.dp),
                            color = AppColors.TextSecondary,
                        )
                    }
                } else {
                    item(key = "seasons", contentType = "seasons") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasons, key = { "season-$it" }) { s ->
                                ChoiceChip("Staffel $s", selected = s == season, onClick = { vm.selectSeason(s) })
                            }
                        }
                    }
                    items(seasonEpisodes, key = { it.id }, contentType = { "episode" }) { ep ->
                        EpisodeRow(ep, progress[ep.id], modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(), onClick = {
                            val p = progress[ep.id]
                            play(ep, if (p != null && !p.watched) p.positionMs else 0)
                        })
                    }
                }
                if (tmdb != null) {
                    item(key = "cast", contentType = "cast") { CastRow(tmdb.cast, Modifier.padding(start = 20.dp, top = 24.dp)) }
                    item(key = "similar", contentType = "similar") {
                        SimilarRow(similar, onOpen = { navigator.push(Screen.SeriesDetail(it.id)) }, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
                        TmdbAttribution(Modifier.padding(start = 20.dp, top = 8.dp))
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
