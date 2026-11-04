package com.mg.player.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/**
 * Gestaltungssystem (ab 3.41): feste Stufen statt freier Zahlen. Neue und überarbeitete Oberflächen nehmen Abstände,
 * Schrift, Kartengrößen und Bewegung nur von hier – so wirken alle Bildschirme gleich ruhig (Apple-Prinzip:
 * wenige, konsequent wiederholte Maße). Ältere Bildschirme werden schrittweise umgestellt.
 */
object Spacing {
    /** 4-dp-Raster; jede Stufe ist ein Vielfaches davon. */
    val Xxs: Dp = 4.dp
    val Xs: Dp = 8.dp
    val S: Dp = 12.dp
    val M: Dp = 16.dp
    val L: Dp = 24.dp
    val Xl: Dp = 32.dp
    val Xxl: Dp = 40.dp

    /** Abstand zwischen Karten in einer Reihe bzw. einem Raster. */
    val CardGap: Dp = S

    /** Abstand zwischen zwei Inhaltsreihen der Startseite. */
    val RowGap: Dp = L

    /** Alle Stufen aufsteigend (für Tests und Dokumentation). */
    val scale: List<Dp> = listOf(Xxs, Xs, S, M, L, Xl, Xxl)
}

/** Kartengrößen am Fernseher: eine Größe je Inhaltsart. */
object CardSize {
    /** Hochformat-Poster (Filme, Serien). */
    val PosterWidth: Dp = 132.dp
    const val POSTER_RATIO = 2f / 3f

    /** Querformat-Kachel (Sender, Live-Standbild, Hero-Vorschau) im 16:9-Format. */
    val LandscapeWidth: Dp = 256.dp
    const val LANDSCAPE_RATIO = 16f / 9f

    /** Ecken der Inhaltskarten (fließend, siehe [SmoothShape]) – wie die Poster bei tvOS. */
    val Radius: Dp = 12.dp
}

/**
 * Fünf Schriftrollen statt freier Größen. Grundlage bleibt die Typografie des Themes (Inter / Inter Display).
 * Aufruf nur in Composables: `AppText.title`.
 */
object AppText {
    /** Bildschirmtitel („Live-Mosaik“, „Einstellungen“). */
    val largeTitle: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineMedium

    /** Reihen- und Abschnittsüberschriften. */
    val title: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)

    /** Fließtext, Beschreibungen. */
    val body: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** Kartentitel, Menüeinträge. */
    val label: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge

    /** Nebeninfos unter Karten (Jahr, laufende Sendung). */
    val caption: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelSmall
}

/**
 * Bewegung mit Federn wie bei Apple: Ein Ziel wird weich angefahren, ohne feste Dauer; Unterbrechungen (schnelles
 * Weiterblättern) gehen nahtlos in die neue Bewegung über statt neu zu starten.
 */
object AppMotion {
    /** Fokus, Anheben von Karten: schnell, ohne Nachschwingen. */
    fun <T> focus(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Größere Flächen (Leiste ein-/ausfahren, Menüs): etwas weicher, ein Hauch Nachschwingen. */
    fun <T> surface(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow)
}
