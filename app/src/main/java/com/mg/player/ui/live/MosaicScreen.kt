package com.mg.player.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.Category
import com.mg.player.domain.model.PlayRequest
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.CenteredMessage
import com.mg.player.ui.common.FocusSurface
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.ScreenTitle
import com.mg.player.ui.common.appViewModel
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.player.EngineSurface
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.AppText
import com.mg.player.ui.theme.CardSize
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.theme.SmoothShape
import com.mg.player.ui.theme.Spacing

/**
 * Live-Mosaik (z. B. Sport-Konferenz): 2×2 oder 3×3 Sender einer Gruppe als Standbilder, alle 30 s neu.
 * OK öffnet den Sender im Vollbild, „Zurück“ kehrt zur Senderliste zurück.
 */
@Composable
fun MosaicScreen(categoryId: String, startChannelId: String?) {
    val vm = appViewModel(key = "mosaic:$categoryId:$startChannelId") { MosaicViewModel(it, categoryId, startChannelId) }
    val navigator = LocalNavigator.current
    val tiles by vm.scanner.tiles.collectAsStateWithLifecycle()
    val size by vm.size.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val engine by vm.scanner.engine.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }

    Column(Modifier.fillMaxSize().padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 28.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val sport = sportEventId(categoryId) != null
                ScreenTitle(if (sport) "Sender suchen" else "Live-Mosaik")
                Text(
                    if (sport) SPORT_HINT else "Standbilder alle 30 Sekunden · OK öffnet den Sender",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }
            ActionButton(
                text = if (size == 4) "3 × 3" else "2 × 2",
                icon = AppIcons.Grid,
                onClick = vm::toggleSize,
                compact = true,
            )
        }
        Spacer(Modifier.size(16.dp))
        val list = tiles
        when {
            list == null -> CenteredMessage(title = "Lädt Sender…", subtitle = null, loading = true, icon = AppIcons.Grid)

            list.isEmpty() -> CenteredMessage(
                title = "Keine Sender in dieser Gruppe",
                subtitle = "Andere Gruppe in der Senderliste wählen und das Mosaik dort erneut öffnen.",
                loading = false,
                icon = AppIcons.Grid,
            )

            else -> {
                val columns = if (size == 4) 2 else 3
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(list.size) { runCatching { firstFocus.requestFocus() } }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    list.chunked(columns).forEachIndexed { rowIndex, row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEachIndexed { colIndex, tile ->
                                val index = rowIndex * columns + colIndex
                                LiveStillCard(
                                    tile = tile,
                                    live = scanning == index,
                                    content = {
                                        val current = engine
                                        if (scanning == index && current != null) {
                                            EngineSurface(current, Modifier.fillMaxSize(), onContainer = vm.scanner::onSurface)
                                        }
                                    },
                                    onClick = {
                                        val eventId = sportEventId(categoryId)
                                        if (eventId == null) {
                                            navigator.push(Screen.Player(PlayRequest.Live(tile.channel.id, categoryId)))
                                        } else {
                                            // Deine Wahl ist der Beweis (Plan „Spiel-Finder“ H/I).
                                            vm.confirmChoice(tile.channel, eventId)
                                            navigator.push(Screen.Player(PlayRequest.Live(tile.channel.id, Category.ALL)))
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                                )
                            }
                            // Letzte Reihe unvollständig: Platz freihalten, damit alle Kacheln gleich groß bleiben.
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sender-Kachel mit Standbild (Mosaik und Startseite „Läuft gerade“): Bild bzw. Logo, darüber kurz das Live-Bild des
 * Abtasters ([content], wenn [live]), unten Name, laufende Sendung und Fortschritt auf einem Verlauf.
 */
@Composable
internal fun LiveStillCard(
    tile: MosaicTile,
    live: Boolean,
    content: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    nowMs: Long? = null,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxSize(),
        shape = SmoothShape(CardSize.Radius),
        focusedScale = 1.04f,
        sheen = false,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val still = tile.still
            when {
                still != null -> Image(still, contentDescription = tile.channel.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                !live -> ChannelLogo(tile.channel.logoUrl, tile.channel.name, Modifier.size(width = 96.dp, height = 72.dp))
            }
            // Der Abtaster spielt den Sender kurz live in seiner Kachel (über dem alten Standbild).
            content()
            // Unten: Name und laufende Sendung auf einem Verlauf, damit beides auf jedem Bild lesbar bleibt.
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        Box(Modifier.size(8.dp).background(AppColors.Live, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    MarqueeText(
                        tile.channel.name,
                        style = AppText.label,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val subtitle = when {
                    tile.failed && tile.still == null && !live -> "Kein Bild"
                    else -> tile.channel.nowTitle
                }
                subtitle?.let {
                    MarqueeText(
                        it,
                        style = AppText.caption,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val progress = nowMs?.let { liveProgress(tile.channel.nowStartMs, tile.channel.nowEndMs, it) }
                if (progress != null) {
                    Box(
                        Modifier
                            .padding(top = Spacing.Xxs)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape),
                    ) {
                        Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Color.White, CircleShape))
                    }
                }
            }
        }
    }
}

/** Anteil der laufenden Sendung (0..1) oder null ohne gültige Zeiten. */
internal fun liveProgress(startMs: Long?, endMs: Long?, nowMs: Long): Float? {
    if (startMs == null || endMs == null) return null
    val duration = endMs - startMs
    if (duration <= 0 || nowMs < startMs) return null
    return ((nowMs - startMs).toFloat() / duration).coerceIn(0f, 1f)
}

private const val SPORT_HINT = "Wo läuft das Spiel? OK auf dem richtigen Bild öffnet den Sender und merkt ihn sich"
