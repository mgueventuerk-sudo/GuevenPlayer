package com.mg.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mg.player.domain.binge.BingeState
import com.mg.player.domain.model.Episode
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.glass
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/** Fokussierbarer Button „Intro überspringen“ unten rechts – direkt mit OK auslösbar. */
@Composable
fun SkipIntroButton(state: BingeState, takeFocus: Boolean, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    AnimatedVisibility(
        visible = state.skipIntroVisible,
        enter = fadeIn() + slideInHorizontally { it / 3 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        LaunchedEffect(takeFocus) { if (takeFocus) runCatching { requester.requestFocus() } }
        ActionButton(
            text = state.skipIntroLabel,
            icon = AppIcons.SkipNext,
            primary = true,
            onClick = onSkip,
            modifier = Modifier.focusRequester(requester),
        )
    }
}

/** Netflix-artige Karte „Nächste Folge“ mit 10-Sekunden-Countdown-Ring. */
@Composable
fun NextEpisodeCard(state: BingeState, next: Episode?, takeFocus: Boolean, onPlayNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    AnimatedVisibility(
        visible = state.nextVisible && next != null,
        enter = fadeIn() + slideInHorizontally { it / 2 },
        exit = fadeOut() + slideOutHorizontally { it / 2 },
        modifier = modifier,
    ) {
        if (next == null) return@AnimatedVisibility
        LaunchedEffect(takeFocus) { if (takeFocus) runCatching { requester.requestFocus() } }
        val context = LocalContext.current
        val request = remember(next.imageUrl) { ImageRequest.Builder(context).data(next.imageUrl).size(320, 180).build() }
        Column(
            Modifier
                .width(480.dp)
                .glass(AppShapes.Panel, fill = AppColors.Panel)
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(150.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.LogoTile),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!next.imageUrl.isNullOrBlank()) {
                        AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    CountdownRing(state.nextProgress, state.nextSecondsLeft)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Nächste Folge", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                    Text(next.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppColors.AccentText)
                    Text(next.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(
                    text = "Jetzt abspielen",
                    icon = AppIcons.Play,
                    primary = true,
                    onClick = onPlayNow,
                    modifier = Modifier.weight(1f).focusRequester(requester),
                )
                ActionButton(text = "Abspann sehen", onClick = onCancel, icon = AppIcons.Close, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CountdownRing(progress: Float, secondsLeft: Int) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(AppColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(48.dp)) {
            val stroke = 4.dp.toPx()
            drawArc(AppColors.ProgressTrack, 0f, 360f, false, style = Stroke(stroke))
            drawArc(Color.White, -90f, 360f * (1f - progress.coerceIn(0f, 1f)), false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(secondsLeft.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
