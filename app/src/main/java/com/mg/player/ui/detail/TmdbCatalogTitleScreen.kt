package com.mg.player.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.domain.model.ContentType
import com.mg.player.domain.model.Episode
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.PlaybackProgress
import com.mg.player.domain.model.TmdbLibraryMatch
import com.mg.player.domain.model.TmdbRecommendation
import com.mg.player.domain.usecase.ResolveNextEpisodeUseCase
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.DetailBackdrop
import com.mg.player.ui.common.LoadingIndicator
import com.mg.player.ui.common.MetaLine
import com.mg.player.ui.common.SpoilerDescription
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun TmdbCatalogTitleScreen(recommendation: TmdbRecommendation) {
    val vm = appViewModel(key = "${recommendation.type}:${recommendation.tmdbId}") {
        TmdbCatalogTitleViewModel(it, recommendation)
    }
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val copies by vm.copies.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val openingItemId by vm.openingItemId.collectAsStateWithLifecycle()
    val searchError by vm.searchError.collectAsStateWithLifecycle()
    val actionMessage by vm.actionMessage.collectAsStateWithLifecycle()
    val touch = isTouch
    val firstPlayRequester = remember { FocusRequester() }

    LaunchedEffect(searching, copies, touch) {
        if (!touch && !searching && copies.isNotEmpty()) runCatching { firstPlayRequester.requestFocus() }
    }

    BoxScreenBackground(recommendation.backdropUrl ?: recommendation.posterUrl) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (touch) 20.dp else 56.dp, vertical = if (touch) 28.dp else 40.dp),
            verticalArrangement = Arrangement.spacedBy(if (touch) 18.dp else 14.dp),
        ) {
            Text(
                "TMDB · ${if (recommendation.type == ContentType.MOVIE) "Film" else "Serie"}",
                color = AppColors.AccentText,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                recommendation.title,
                style = if (touch) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(listOfNotNull(recommendation.year, recommendation.rating?.let { "★ %.1f".format(it) }, "TMDB"))
            recommendation.overview?.takeIf(String::isNotBlank)?.let { overview ->
                SpoilerDescription(
                    overview,
                    modifier = Modifier.fillMaxWidth(if (touch) 1f else 0.7f),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                )
            }
            TmdbAttribution()
            Spacer(Modifier.height(4.dp))
            Text("In deinen Playlists", style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            when {
                searching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier, size = 24.dp)
                    Text("Alle Playlists werden durchsucht …", color = AppColors.TextSecondary)
                }
                searchError != null -> Text(searchError!!, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                copies.isEmpty() -> Text(
                    "Dieser Titel wurde in deinen Playlists nicht gefunden. Er bleibt im TMDB-Katalog sichtbar, ist hier aber nicht direkt abspielbar.",
                    modifier = Modifier.fillMaxWidth(if (touch) 1f else 0.7f),
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> {
                    Text(
                        "${copies.size} abspielbare ${if (copies.size == 1) "Fassung" else "Fassungen"} gefunden",
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    copies.forEach { copy ->
                        Column(
                            Modifier
                                .fillMaxWidth(if (touch) 1f else 0.78f)
                                .background(AppColors.GlassRaised, RoundedCornerShape(16.dp))
                                .padding(if (touch) 14.dp else 18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                copy.playlistName,
                                style = MaterialTheme.typography.labelLarge,
                                color = AppColors.AccentText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                copy.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MetaLine(listOfNotNull(copy.year), badges = copy.badges)
                            if (copy.isApproximate) {
                                Text(
                                    "Ähnlicher Anbietertitel – bitte vor dem Start prüfen",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AppColors.TextSecondary,
                                )
                            }
                            ActionButton(
                                text = if (openingItemId == copy.itemId) "Wird vorbereitet …" else "Abspielen",
                                onClick = {
                                    if (openingItemId == null) scope.launch {
                                        vm.playRequest(copy)?.let { request -> navigator.push(Screen.Player(request)) }
                                    }
                                },
                                modifier = when {
                                    touch -> Modifier.fillMaxWidth()
                                    copy.itemId == copies.firstOrNull()?.itemId -> Modifier.focusRequester(firstPlayRequester)
                                    else -> Modifier
                                },
                                icon = AppIcons.Play,
                                primary = true,
                                compact = !touch,
                            )
                        }
                    }
                    actionMessage?.let { Text(it, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun BoxScreenBackground(imageUrl: String?, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        DetailBackdrop(imageUrl)
        content()
    }
}

class TmdbCatalogTitleViewModel(
    private val container: AppContainer,
    private val recommendation: TmdbRecommendation,
) : ViewModel() {
    private val _copies = MutableStateFlow<List<TmdbLibraryMatch>>(emptyList())
    val copies: StateFlow<List<TmdbLibraryMatch>> = _copies.asStateFlow()

    private val _searching = MutableStateFlow(true)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _openingItemId = MutableStateFlow<String?>(null)
    val openingItemId: StateFlow<String?> = _openingItemId.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _copies.value = container.tmdbRepository.findPlayableCopies(recommendation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _searchError.value = "Deine Playlists konnten gerade nicht durchsucht werden. Bitte versuche es erneut."
            } finally {
                _searching.value = false
            }
        }
    }

    suspend fun playRequest(copy: TmdbLibraryMatch): PlayRequest? {
        if (_openingItemId.value != null) return null
        _openingItemId.value = copy.itemId
        _actionMessage.value = null
        return try {
            val engine = container.settingsRepository.current().vodEngine
            when (recommendation.type) {
                ContentType.MOVIE -> {
                    val progress = container.playbackRepository.progress(PlaybackProgress.movieKey(copy.itemId))
                    val resume = progress?.takeIf { !it.watched && it.positionMs > 30_000 }?.positionMs ?: 0L
                    PlayRequest.Movie(copy.itemId, resume, engine)
                }
                ContentType.SERIES -> {
                    val episodes = container.seriesRepository.loadEpisodes(copy.itemId)
                    val episode = chooseEpisode(episodes, container.playbackRepository.observeSeriesProgress(copy.itemId).first())
                    if (episode == null) {
                        _actionMessage.value = "Für diese Serie wurden keine abspielbaren Episoden gefunden."
                        null
                    } else {
                        PlayRequest.Episode(episode.first.id, copy.itemId, episode.second, engine)
                    }
                }
                ContentType.LIVE -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _actionMessage.value = "Die Wiedergabe konnte nicht vorbereitet werden. Prüfe den IPTV-Zugang und versuche es erneut."
            null
        } finally {
            _openingItemId.value = null
        }
    }

    private fun chooseEpisode(
        episodes: List<Episode>,
        progress: Map<String, PlaybackProgress>,
    ): Pair<Episode, Long>? {
        if (episodes.isEmpty()) return null
        val last = progress.values.maxByOrNull { it.updatedAtMs }
        val current = last?.let { l -> episodes.firstOrNull { PlaybackProgress.episodeKey(it.id) == l.contentKey } }
        return when {
            last == null || current == null -> episodes.first() to 0L
            !last.watched -> current to last.positionMs
            else -> (ResolveNextEpisodeUseCase.next(episodes, current) ?: current) to 0L
        }
    }
}
