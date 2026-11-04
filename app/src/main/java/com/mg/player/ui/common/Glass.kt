package com.mg.player.ui.common

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * Material-Fläche im Apple-Stil: halbtransparente Füllung ohne Rand, oben ein Hauch Licht. Liegt über dem
 * weichgezeichneten Hintergrundbild bzw. dem Aurora-Hintergrund – daraus entsteht der Milchglas-Eindruck.
 * Bewusst ohne Echtzeit-Weichzeichner: Der kostet auf TV-Sticks zu viel Leistung.
 */
fun Modifier.glass(shape: Shape = AppShapes.Panel, raised: Boolean = false, fill: Color? = null): Modifier {
    val base = fill ?: if (raised) AppColors.GlassRaised else AppColors.Glass
    if (AppColors.reducedEffects) return this.clip(shape).background(base)
    val highlight = AppColors.Highlight
    return this
        .clip(shape)
        .background(base)
        .background(Brush.verticalGradient(0f to highlight.copy(alpha = highlight.alpha * 0.22f), 0.35f to Color.Transparent))
}

/**
 * „Liquid Glass“ (tvOS 26): schwebendes Material für Leisten und Menüs. Dichter als [glass], mit hellem Lichtrand oben,
 * weichem Schatten darunter und einem Hauch Abdunklung am unteren Rand – so wirkt die Fläche wie eine Glasscheibe über
 * dem Inhalt. Bewusst ohne echte Lichtbrechung (Echtzeit-Blur überfordert TV-Sticks). Mit „Effekte reduzieren“: nur
 * die deckende Fläche.
 */
fun Modifier.liquidGlass(shape: Shape): Modifier {
    if (AppColors.reducedEffects) return this.clip(shape).background(AppColors.GlassRaised)
    val highlight = AppColors.Highlight
    val shade = Color.Black.copy(alpha = if (AppColors.isDark) 0.10f else 0.04f)
    return this
        .shadow(elevation = 18.dp, shape = shape, ambientColor = AppColors.Shadow, spotColor = AppColors.Shadow)
        .clip(shape)
        .background(AppColors.GlassRaised)
        .background(Brush.verticalGradient(0f to highlight.copy(alpha = highlight.alpha * 0.30f), 0.45f to Color.Transparent, 1f to shade))
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(highlight.copy(alpha = highlight.alpha * 0.85f), Color.Transparent, highlight.copy(alpha = highlight.alpha * 0.20f)),
            ),
            shape = shape,
        )
}

/** Glas-Container mit Innenabstand. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.Panel,
    raised: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, raised), content = content)
}

/**
 * Bild hinter der ganzen Oberfläche (weichgezeichnet): Poster-Raster und Detailseiten setzen hier das Bild des
 * fokussierten Titels, damit der Hintergrund wie bei Apple TV zum Inhalt passt.
 */
@Stable
class BackdropState {
    var url by mutableStateOf<String?>(null)
}

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

/**
 * App-Hintergrund: ruhige Grundfarbe mit zwei weichen Farbflächen ("Aurora") in Akzent- und Partnerfarbe.
 * Mit [artworkUrl] liegt darüber das Bild des gerade gewählten Titels – stark verkleinert geladen und groß
 * gezeichnet wirkt es weichgezeichnet, ganz ohne teuren Blur-Effekt (ab Android 12 zusätzlich echter Blur).
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier, artworkUrl: String? = null, content: @Composable BoxScope.() -> Unit) {
    val background = AppColors.Background
    val plain = AppColors.reducedEffects
    Box(
        modifier
            .fillMaxSize()
            .background(background),
    ) {
        // Eigene Ebene: Das Ambient-Licht ändert sich laufend und soll nur sie neu zeichnen, nie den Inhalt.
        if (!plain) AuroraLayer()
        // Erst nach kurzem Verweilen wechseln: Beim schnellen Durchblättern lädt und überblendet nicht jedes Poster.
        var shown by remember { mutableStateOf(artworkUrl) }
        LaunchedEffect(artworkUrl) {
            if (artworkUrl != null) delay(ARTWORK_DELAY_MS)
            shown = artworkUrl
        }
        Crossfade(targetState = shown, animationSpec = tween(600), label = "artwork") { url ->
            if (!url.isNullOrBlank() && !plain) BlurredArtwork(url)
        }
        content()
    }
}

/**
 * Zwei weiche Farbflächen in Akzent- und Partnerfarbe. Ambient-Licht: Läuft eine Vorschau, nehmen sie langsam die
 * Farben des Bildes an (links oben = linke Bildhälfte, rechts unten = rechte). Gelesen wird nur beim Zeichnen.
 */
@Composable
private fun AuroraLayer() {
    val ambient = AppColors.videoAmbient
    val ambientAlpha = if (AppColors.isDark) 0.34f else 0.22f
    val auroraA = animateColorAsState(ambient?.left?.copy(alpha = ambientAlpha) ?: AppColors.AuroraA, tween(AMBIENT_FADE_MS), label = "ambientA")
    val auroraB = animateColorAsState(ambient?.right?.copy(alpha = ambientAlpha) ?: AppColors.AuroraB, tween(AMBIENT_FADE_MS), label = "ambientB")
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(auroraA.value, Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.05f),
                        radius = size.width * 0.55f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(auroraB.value, Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.95f),
                        radius = size.width * 0.5f,
                    ),
                )
            },
    )
}

@Composable
private fun BlurredArtwork(url: String) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).size(48, 27).allowRgb565(true).crossfade(false).build()
    }
    val dark = AppColors.isDark
    val background = AppColors.Background
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = if (dark) 0.55f else 0.45f,
            modifier = Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(24.dp) else Modifier),
        )
        // Lesbarkeit: zur Seite und nach unten hin in die Grundfarbe überblenden.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(background.copy(alpha = 0.35f), background.copy(alpha = 0.9f)))),
        )
    }
}

private const val ARTWORK_DELAY_MS = 350L

/** Überblendzeit des Ambient-Lichts: langsam, damit Schnitte im Bild nicht flackern. */
private const val AMBIENT_FADE_MS = 1_400
