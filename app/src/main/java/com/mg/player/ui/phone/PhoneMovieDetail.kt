package com.mg.player.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.PlayRequest
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.EnginePicker
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.MetaLine
import com.mg.player.ui.common.ProgressBar
import com.mg.player.ui.common.SpoilerDescription
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.detail.CastRow
import com.mg.player.ui.detail.MovieDetailViewModel
import com.mg.player.ui.detail.RematchPanel
import com.mg.player.ui.detail.SimilarRow
import com.mg.player.ui.detail.TmdbAttribution
import com.mg.player.ui.detail.TmdbMeta
import com.mg.player.ui.detail.openTrailer
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

/** Film-Details am Handy: Titelbild oben, darunter Infos und Knöpfe – alles in einer Spalte zum Scrollen. */
@Composable
fun PhoneMovieDetail(movieId: String) {
    val vm = appViewModel(key = movieId) { MovieDetailViewModel(it, movieId) }
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val movie by vm.movie.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
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

    val current = movie
    if (current == null) {
        CenteredMessage(if (loading) "Lädt…" else "Film nicht gefunden", loading = loading, icon = AppIcons.Movie)
        return
    }
    val resumeMs = progress?.takeIf { !it.watched && it.positionMs > 30_000 }?.positionMs
    val tmdb = info
    BackHandler(enabled = candidates != null) { vm.tmdb.closeRematch() }

    Box(Modifier.fillMaxSize()) {
        if (candidates == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PhoneDetailHeader(current.backdropUrl ?: tmdb?.backdropUrl ?: current.posterUrl, onBack = navigator::pop)
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(current.name, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    MetaLine(
                        listOfNotNull(
                            current.year ?: tmdb?.year,
                            (current.durationSec?.let { it / 60 } ?: tmdb?.runtimeMin)?.takeIf { it > 0 }?.let { TimeUtils.formatMinutes(it * 60) },
                            if (tmdb?.rating == null) current.rating?.takeIf { it > 0f }?.let { "★ %.1f".format(it) } else null,
                        ),
                        badges = current.badges,
                    )
                    if (tmdb != null) TmdbMeta(tmdb, Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()))
                    Spacer(Modifier.height(16.dp))
                    if (resumeMs != null) {
                        ProgressBar(progress?.fraction ?: 0f, Modifier.fillMaxWidth().padding(bottom = 10.dp), height = 5.dp)
                    }
                    ActionButton(
                        text = if (resumeMs != null) "Fortsetzen bei ${TimeUtils.formatDuration(resumeMs)}" else "Abspielen",
                        icon = AppIcons.Play,
                        primary = true,
                        onClick = { navigator.push(Screen.Player(PlayRequest.Movie(current.id, resumeMs ?: 0, engine))) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    PhoneActionRow {
                        if (resumeMs != null) {
                            IconActionButton(
                                AppIcons.Restart,
                                "Von vorn",
                                onClick = { navigator.push(Screen.Player(PlayRequest.Movie(current.id, 0, engine))) },
                            )
                        }
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
                        if (tmdbEnabled) IconActionButton(AppIcons.Search, "Falscher Film?", onClick = { vm.tmdb.openRematch(current.name) })
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
                    SpoilerDescription(
                        tmdb?.overview ?: current.plot ?: "Keine Beschreibung verfügbar.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (tmdb != null) {
                        CastRow(tmdb.cast, Modifier.padding(top = 24.dp))
                        SimilarRow(similar, onOpen = { navigator.push(Screen.MovieDetail(it.id)) }, modifier = Modifier.padding(top = 16.dp))
                        TmdbAttribution(Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(32.dp))
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
