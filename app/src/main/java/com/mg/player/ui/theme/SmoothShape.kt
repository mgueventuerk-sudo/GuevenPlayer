package com.mg.player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Abgerundete Ecken mit fließendem Übergang wie bei Apple („Continuous Corners“, Squircle): Die Rundung beginnt
 * früher und läuft weicher in die Kante aus als ein Kreisbogen. Kurven nach dem bekannten iOS-7-Pfad (1,528 × r).
 * Pro Ecke eigener Radius (Gruppen-Kästen sind nur oben bzw. unten rund).
 */
@Immutable
class SmoothShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        // Der Pfad braucht bis zu 1,528 × r je Kante: bei kleinen Flächen den Radius begrenzen.
        val max = minOf(size.width, size.height) / 2f / EXTENT
        fun px(d: Dp) = with(density) { d.toPx() }.coerceIn(0f, max)
        val tl = px(if (ltr) topStart else topEnd)
        val tr = px(if (ltr) topEnd else topStart)
        val br = px(if (ltr) bottomEnd else bottomStart)
        val bl = px(if (ltr) bottomStart else bottomEnd)
        return Outline.Generic(smoothPath(size, Corners(tl, tr, br, bl)))
    }

    override fun equals(other: Any?): Boolean = other is SmoothShape &&
        other.topStart == topStart && other.topEnd == topEnd && other.bottomEnd == bottomEnd && other.bottomStart == bottomStart

    override fun hashCode(): Int = ((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()

    override fun toString(): String = "SmoothShape($topStart, $topEnd, $bottomEnd, $bottomStart)"

    private companion object {
        const val EXTENT = 1.52866f
    }
}

/** Gleich große Ecken (häufigster Fall). */
fun smoothShape(radius: Dp): Shape = SmoothShape(radius)

/** Nur oben bzw. nur unten gerundet (Abschnitte eines Gruppen-Kastens). */
fun smoothShape(top: Dp, bottom: Dp): Shape = SmoothShape(top, top, bottom, bottom)

/** Eckradien in Pixeln: oben links, oben rechts, unten rechts, unten links. */
private class Corners(val tl: Float, val tr: Float, val br: Float, val bl: Float)

@Suppress("MagicNumber")
private fun smoothPath(size: Size, c: Corners): Path = Path().apply {
    val w = size.width
    val h = size.height
    val tl = c.tl
    val tr = c.tr
    val br = c.br
    val bl = c.bl
    // Oben links → oben rechts
    moveTo(1.52866f * tl, 0f)
    lineTo(w - 1.52866f * tr, 0f)
    if (tr > 0f) {
        cubicTo(w - 1.08849f * tr, 0f, w - 0.86840f * tr, 0f, w - 0.63149f * tr, 0.07491f * tr)
        cubicTo(w - 0.37282f * tr, 0.16905f * tr, w - 0.16905f * tr, 0.37282f * tr, w - 0.07491f * tr, 0.63149f * tr)
        cubicTo(w, 0.86840f * tr, w, 1.08849f * tr, w, 1.52866f * tr)
    }
    // rechts → unten rechts
    lineTo(w, h - 1.52866f * br)
    if (br > 0f) {
        cubicTo(w, h - 1.08849f * br, w, h - 0.86840f * br, w - 0.07491f * br, h - 0.63149f * br)
        cubicTo(w - 0.16905f * br, h - 0.37282f * br, w - 0.37282f * br, h - 0.16905f * br, w - 0.63149f * br, h - 0.07491f * br)
        cubicTo(w - 0.86840f * br, h, w - 1.08849f * br, h, w - 1.52866f * br, h)
    }
    // unten → unten links
    lineTo(1.52866f * bl, h)
    if (bl > 0f) {
        cubicTo(1.08849f * bl, h, 0.86840f * bl, h, 0.63149f * bl, h - 0.07491f * bl)
        cubicTo(0.37282f * bl, h - 0.16905f * bl, 0.16905f * bl, h - 0.37282f * bl, 0.07491f * bl, h - 0.63149f * bl)
        cubicTo(0f, h - 0.86840f * bl, 0f, h - 1.08849f * bl, 0f, h - 1.52866f * bl)
    }
    // links → oben links
    lineTo(0f, 1.52866f * tl)
    if (tl > 0f) {
        cubicTo(0f, 1.08849f * tl, 0f, 0.86840f * tl, 0.07491f * tl, 0.63149f * tl)
        cubicTo(0.16905f * tl, 0.37282f * tl, 0.37282f * tl, 0.16905f * tl, 0.63149f * tl, 0.07491f * tl)
        cubicTo(0.86840f * tl, 0f, 1.08849f * tl, 0f, 1.52866f * tl, 0f)
    }
    close()
}

/** Gemeinsame Formen: Karten, Zeilen, Panels – überall dieselbe weiche Rundung. */
object AppShapes {
    val Card: Shape = SmoothShape(Dimens.CardRadius)
    val Row: Shape = SmoothShape(Dimens.RowRadius)
    val Panel: Shape = SmoothShape(Dimens.PanelRadius)
    val Small: Shape = SmoothShape(10.dp)
}
