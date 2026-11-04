package com.mg.player.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.PlayRequest
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.DetailBackdrop
import com.mg.player.ui.common.EnginePicker
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.MetaLine
import com.mg.player.ui.common.ProgressBar
import com.mg.player.ui.common.SpoilerDescription
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.rememberFocusMemory
import com.mg.player.ui.common.remembered
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons

@Composable
fun MovieDetailScreen(movieId: String) {
    val vm = appViewModel(key = movieId) { MovieDetailViewModel(it, movieId) }
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val movie by vm.movie.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val tmdb by vm.tmdb.info.collectAsStateWithLifecycle()
    val tmdbEnabled by vm.tmdb.enabled.collectAsStateWithLifecycle()
    val similar by vm.tmdb.similar.collectAsStateWithLifecycle()
    val message by vm.tmdb.message.collectAsStateWithLifecycle()
    val candidates by vm.tmdb.candidates.collectAsStateWithLifecycle()
    val searching by vm.tmdb.searching.collectAsStateWithLifecycle()
    var showEnginePicker by rememberSaveable { mutableStateOf(false) }
    var trailerError by remember { mutableStateOf(false) }
    val playRequester = remember { FocusRequester() }
    // Zurück aus Player, Trailer oder Engine-Auswahl: Fokus wieder auf den zuletzt gewählten Knopf.
    val memory = rememberFocusMemory()

    val current = movie
    if (current == null) {
        CenteredMessage(if (loading) "Lädt…" else "Film nicht gefunden", loading = loading, icon = AppIcons.Movie)
        return
    }
    val resumeMs = progress?.takeIf { !it.watched && it.positionMs > 30_000 }?.positionMs
    LaunchedEffect(current.id, resumeMs != null) { memory.restore(playRequester) }
    val info = tmdb

    BackHandler(enabled = candidates != null) { vm.tmdb.closeRematch() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wie in der Apple-TV-App: Das Bild füllt den Bildschirm, Titel und Knöpfe stehen im unteren Drittel.
        val heroTop = if (isTouch) 64.dp else maxHeight * HERO_TOP_FRACTION
        DetailBackdrop(current.backdropUrl ?: info?.backdropUrl ?: current.posterUrl)
        // Während der Neuzuordnung nur das Panel zeigen, damit der Fokus dort bleibt.
        if (candidates == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 64.dp, top = heroTop, end = 40.dp, bottom = 40.dp),
            ) {
                Column(Modifier.fillMaxWidth(0.6f)) {
                    Text(current.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    MetaLine(
                        listOfNotNull(
                            current.year ?: info?.year,
                            (current.durationSec?.let { it / 60 } ?: info?.runtimeMin)?.takeIf { it > 0 }?.let { TimeUtils.formatMinutes(it * 60) },
                            if (info?.rating == null) current.rating?.takeIf { it > 0f }?.let { "★ %.1f".format(it) } else null,
                        ),
                        badges = current.badges,
                    )
                    if (info != null) TmdbMeta(info, Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(14.dp))
                    SpoilerDescription(
                        info?.overview ?: current.plot ?: "Keine Beschreibung verfügbar.",
                        style = MaterialTheme.typography.bodyLarge,
                        expandedLines = 12,
                    )
                    (message ?: if (trailerError) "Keine App zum Abspielen des Trailers gefunden" else null)?.let {
                        Text(it, color = AppColors.AccentText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    if (resumeMs != null) {
                        ProgressBar(progress?.fraction ?: 0f, Modifier.fillMaxWidth(0.6f), height = 5.dp)
                        Spacer(Modifier.height(14.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (resumeMs != null) {
                        ActionButton(
                            text = "Fortsetzen bei ${TimeUtils.formatDuration(resumeMs)}",
                            icon = AppIcons.Play,
                            primary = true,
                            modifier = Modifier.focusRequester(playRequester).remembered(memory, "play"),
                            onClick = { navigator.push(Screen.Player(PlayRequest.Movie(current.id, resumeMs, engine))) },
                        )
                        IconActionButton(
                            AppIcons.Restart,
                            "Von vorn",
                            onClick = { navigator.push(Screen.Player(PlayRequest.Movie(current.id, 0, engine))) },
                            modifier = Modifier.remembered(memory, "restart"),
                        )
                    } else {
                        ActionButton(
                            text = "Abspielen",
                            icon = AppIcons.Play,
                            primary = true,
                            modifier = Modifier.focusRequester(playRequester).remembered(memory, "play"),
                            onClick = { navigator.push(Screen.Player(PlayRequest.Movie(current.id, 0, engine))) },
                        )
                    }
                    info?.trailerKey?.let { key ->
                        IconActionButton(AppIcons.Movie, "Trailer", onClick = {
                            trailerError = !openTrailer(context, key)
                        }, modifier = Modifier.remembered(memory, "trailer"))
                    }
                    IconActionButton(
                        icon = if (isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                        label = if (isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                        iconTint = if (isFavorite) AppColors.Live else null,
                        onClick = vm::toggleFavorite,
                        modifier = Modifier.remembered(memory, "favorite"),
                    )
                    IconActionButton(AppIcons.Engine, "Player-Engine", onClick = {
                        showEnginePicker = !showEnginePicker
                    }, modifier = Modifier.remembered(memory, "engine"))
                    if (tmdbEnabled) {
                        IconActionButton(AppIcons.Search, "Falscher Film?", onClick = {
                            vm.tmdb.openRematch(current.name)
                        }, modifier = Modifier.remembered(memory, "rematch"))
                    }
                }
                if (showEnginePicker) {
                    Spacer(Modifier.height(18.dp))
                    EnginePicker(selected = engine, onPick = vm::pickEngine)
                }
                if (info != null) {
                    CastRow(info.cast, Modifier.padding(top = 28.dp))
                    SimilarRow(similar, onOpen = { navigator.push(Screen.MovieDetail(it.id)) }, modifier = Modifier.padding(top = 20.dp))
                    TmdbAttribution(Modifier.padding(top = 12.dp))
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

/** Abstand des Titels von oben (Anteil der Bildschirmhöhe) – darüber wirkt das Bild. */
private const val HERO_TOP_FRACTION = 0.34f
