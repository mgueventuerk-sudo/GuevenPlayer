package com.mg.player.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.Dimens

/**
 * Leuchtschatten bei Fokus. Mit „Effekte reduzieren“ (auf schwachen Geräten wie dem Fire TV Stick Lite automatisch)
 * ohne Schatten: Der weichgezeichnete Schatten wird bei jedem Fokuswechsel neu gerechnet und kostet die schwache GPU
 * die meiste Zeit je Bild (Plan B3, 3.25). Fokus bleibt über Füllung, Rand und Skalierung deutlich sichtbar.
 */
@Composable
@ReadOnlyComposable
internal fun focusGlow(elevation: Dp): Glow =
    if (AppColors.reducedEffects) Glow.None else Glow(elevationColor = AppColors.Shadow, elevation = elevation)

/** Tiefer, weicher Schatten einer angehobenen Kachel (Apple TV); ohne Effekte keiner. */
@Composable
@ReadOnlyComposable
internal fun liftShadow(): Glow = if (AppColors.reducedEffects) Glow.None else Glow(elevationColor = Color.Black.copy(alpha = 0.6f), elevation = 26.dp)

/** true, solange die umgebende Fläche den Fokus hat – für Texte, die ihre Farbe anpassen. */
val LocalSurfaceFocused = compositionLocalOf { false }

/** Textfarben innerhalb einer fokussierbaren Zeile: Auf der hellen Fokus-Fläche wird die Schrift dunkel. */
object RowColors {
    val primary: Color @Composable get() = if (LocalSurfaceFocused.current) AppColors.OnFocus else AppColors.TextPrimary
    val secondary: Color @Composable get() = if (LocalSurfaceFocused.current) AppColors.OnFocus.copy(alpha = 0.72f) else AppColors.TextSecondary
    val muted: Color @Composable get() = if (LocalSurfaceFocused.current) AppColors.OnFocus.copy(alpha = 0.55f) else AppColors.TextMuted
    val accent: Color @Composable get() = if (LocalSurfaceFocused.current) AppColors.OnFocus else AppColors.AccentText
}

/**
 * Basis aller fokussierbaren Kacheln (Poster, Sender, Aktionen) im Apple-TV-Stil: Mit dem Fokus hebt sich die Kachel
 * aus der Fläche – größer, mit weichem, tiefem Schatten, und ein Lichtreflex wandert einmal darüber. Kein Rand, keine
 * Umfärbung: Das Bild bleibt, wie es ist. Mit „Effekte reduzieren“ (ohne Schatten) zeigt stattdessen ein heller Rand
 * den Fokus, damit er auf schwachen Sticks aus der Entfernung erkennbar bleibt.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.Card,
    focusedScale: Float = 1.08f,
    containerColor: Color = AppColors.Glass,
    focusedContainerColor: Color = AppColors.GlassRaised,
    glow: Boolean = true,
    sheen: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val sweep = remember { Animatable(0f) }
    val sheenOn = sheen && !AppColors.reducedEffects
    // Lichtreflex beim Fokus (Fernseher) bzw. beim Antippen (Handy).
    LaunchedEffect(focused || pressed) {
        if ((focused || pressed) && sheenOn) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
        } else {
            sweep.snapTo(0f)
        }
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.touchClick(interaction, onClick, onLongClick),
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = AppColors.TextPrimary,
            pressedContainerColor = focusedContainerColor,
            pressedContentColor = AppColors.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = if (AppColors.reducedEffects) {
            ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.FocusFill), shape = shape))
        } else {
            ClickableSurfaceDefaults.border()
        },
        glow = if (glow) {
            ClickableSurfaceDefaults.glow(focusedGlow = liftShadow())
        } else {
            ClickableSurfaceDefaults.glow()
        },
    ) {
        CompositionLocalProvider(LocalSurfaceFocused provides false) {
            Box(
                Modifier.drawWithContent {
                    drawContent()
                    val p = sweep.value
                    if (p > 0f && p < 1f) {
                        // Lichtreflex: ein schräger heller Streifen wandert einmal von links oben nach rechts unten.
                        val w = size.width
                        val h = size.height
                        val x = -w + p * (2.2f * w)
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = 0.32f),
                                1f to Color.Transparent,
                                start = Offset(x, 0f),
                                end = Offset(x + w * 0.6f, h),
                            ),
                        )
                    }
                },
            ) {
                // Texte darin wissen, ob ihre Kachel den Fokus hat (Laufschrift langer Titel, siehe MarqueeText).
                CompositionLocalProvider(LocalSurfaceFocused provides focused) { content() }
            }
        }
    }
}

/**
 * Zeile in Listen (Kategorien, Einstellungen, Sender): ruhig, ohne Skalierung. Mit dem Fokus füllt sich die Zeile
 * hell (dunkler Modus) bzw. in der Akzentfarbe (heller Modus) – auf Abstand sofort erkennbar.
 */
@Composable
fun ListSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = AppShapes.Row,
    containerColor: Color? = null,
    focusedScale: Float = 1.02f,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.touchClick(interaction, onClick, onLongClick),
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor ?: if (selected) AppColors.AccentSoft else Color.Transparent,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.FocusFill,
            focusedContentColor = AppColors.OnFocus,
            pressedContainerColor = AppColors.FocusFill,
            pressedContentColor = AppColors.OnFocus,
        ),
        // Skalierung bleibt auch mit „Effekte reduzieren“: Abschalten brachte im Labor keinen messbaren Gewinn (3.25).
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow(10.dp)),
    ) {
        CompositionLocalProvider(LocalSurfaceFocused provides focused) { content() }
    }
}
