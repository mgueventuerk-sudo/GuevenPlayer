package com.mg.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.mg.player.R
import com.mg.player.domain.model.AccentColor
import com.mg.player.domain.model.ThemeMode
import java.util.Calendar

/**
 * Alle Farben einer Darstellung. Glas-Flächen sind halbtransparent und liegen über dem "Aurora"-Hintergrund
 * (weiche Farbflächen in Akzent- und Partnerfarbe) – daraus entsteht die Tiefe des Glas-Looks.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val background: Color,
    val backgroundAlt: Color,
    val auroraA: Color,
    val auroraB: Color,
    val glass: Color,
    val glassRaised: Color,
    val glassBorder: Color,
    val highlight: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceFocused: Color,
    val border: Color,
    val focusFill: Color,
    val onFocus: Color,
    val shadow: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentAlt: Color,
    val accentText: Color,
    val accentSoft: Color,
    /** Schrift auf der Akzentfarbe (Primärknöpfe, gewählte Chips): weiß oder dunkel – je nachdem, was lesbarer ist. */
    val onAccent: Color,
    val scrim: Color,
    val progressTrack: Color,
)

/** Akzentfarbe plus Partnerfarbe für Verläufe (Logo, Fortschritt, Aurora). */
private data class AccentPair(val main: Color, val partner: Color, val textOnDark: Color, val textOnLight: Color)

private fun accentOf(accent: AccentColor) = when (accent) {
    AccentColor.BLUE -> AccentPair(Color(0xFF2F7BFF), Color(0xFF22D3EE), Color(0xFF8AB6FF), Color(0xFF1F5FD6))

    AccentColor.VIOLET -> AccentPair(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFC4B1FF), Color(0xFF6D3FE0))

    AccentColor.TEAL -> AccentPair(Color(0xFF14B8A6), Color(0xFF84CC16), Color(0xFF7EE6D8), Color(0xFF0B7569))

    AccentColor.ORANGE -> AccentPair(Color(0xFFFF7A1A), Color(0xFFFFC23D), Color(0xFFFFB37A), Color(0xFFA84500))

    AccentColor.ROSE -> AccentPair(Color(0xFFF43F7E), Color(0xFFFB923C), Color(0xFFFF9DBE), Color(0xFFC81E5B))

    // Markenfarben von GuevenPlayer (Icon): Rot mit Gelb als Partner.
    AccentColor.BRAND -> AccentPair(Color(0xFFDE1E2C), Color(0xFFFDB912), Color(0xFFFF8A92), Color(0xFFB3121F))
}

private val InkDark = Color(0xFF0A0E18)

/**
 * Lesbare Schriftfarbe auf einer kräftigen Fläche: Weiß, solange der Kontrast mindestens 3:1 erreicht
 * (fette Beschriftung auf Knöpfen), sonst fast Schwarz – z. B. auf Türkis oder Orange.
 */
fun contentColorFor(background: Color): Color {
    val contrastWhite = 1.05 / (background.luminance() + 0.05)
    return if (contrastWhite >= 3.0) Color.White else InkDark
}

/** Akzentfarbe mit Partnerfarbe – für die Farbfelder in den Einstellungen. */
fun accentGradient(accent: AccentColor): List<Color> = accentOf(accent).let { listOf(it.main, it.partner) }

fun paletteFor(dark: Boolean, accent: AccentColor): Palette {
    val a = accentOf(accent)
    return if (dark) {
        Palette(
            dark = true,
            background = Color(0xFF05070D),
            backgroundAlt = Color(0xFF0B1020),
            auroraA = a.main.copy(alpha = 0.30f),
            auroraB = a.partner.copy(alpha = 0.16f),
            // Material statt Glas mit Rand: etwas dichter, dafür ohne Linien (Apple-Stil).
            glass = Color.White.copy(alpha = 0.085f),
            glassRaised = Color.White.copy(alpha = 0.14f),
            glassBorder = Color.White.copy(alpha = 0.12f),
            highlight = Color.White.copy(alpha = 0.22f),
            surface = Color(0xFF111521),
            surfaceHigh = Color(0xFF1A1F2D),
            surfaceFocused = Color(0xFF2A3042),
            border = Color.White.copy(alpha = 0.10f),
            focusFill = Color(0xFFF5F7FB),
            onFocus = Color(0xFF0A0E18),
            shadow = Color.Black.copy(alpha = 0.55f),
            // Schrift über Deckkraft abgestuft (wie Apples „Vibrancy“): passt sich jedem Hintergrundbild an.
            textPrimary = Color(0xFFF5F7FB),
            textSecondary = Color.White.copy(alpha = 0.68f),
            // War 0.44 (4,32:1) - unter WCAG AA (4,5:1) für echten Informationstext (Jahr, Fortschritt), nicht nur
            // Dekoration. 0.50 ergibt 5,31:1, aus den Hex-Werten dieser Datei berechnet (keine Schätzung).
            textMuted = Color.White.copy(alpha = 0.50f),
            accent = a.main,
            accentAlt = a.partner,
            accentText = a.textOnDark,
            accentSoft = a.main.copy(alpha = 0.22f),
            onAccent = contentColorFor(a.main),
            scrim = Color.Black.copy(alpha = 0.62f),
            progressTrack = Color.White.copy(alpha = 0.18f),
        )
    } else {
        Palette(
            dark = false,
            background = Color(0xFFE9EDF5),
            backgroundAlt = Color(0xFFF7F8FC),
            // War 0.20 (Abstand zum Hintergrund nur 43,7 statt 81,5 im Dunklen - der Blob war gegen den schon
            // bläulichen hellen Hintergrund knapp halb so gut wahrnehmbar). 0.30 gleicht das Verhältnis zu
            // auroraB (0.16 in beiden Themes) an das dunkle Theme an.
            auroraA = a.main.copy(alpha = 0.30f),
            auroraB = a.partner.copy(alpha = 0.16f),
            glass = Color.White.copy(alpha = 0.64f),
            glassRaised = Color.White.copy(alpha = 0.82f),
            glassBorder = Color.White.copy(alpha = 0.85f),
            highlight = Color.White.copy(alpha = 0.95f),
            surface = Color(0xFFF8FAFD),
            surfaceHigh = Color(0xFFFFFFFF),
            surfaceFocused = Color(0xFFFFFFFF),
            border = Color(0xFF0B1220).copy(alpha = 0.08f),
            focusFill = a.textOnLight,
            onFocus = Color.White,
            shadow = Color(0xFF1B2440).copy(alpha = 0.22f),
            textPrimary = Color(0xFF0B1220),
            textSecondary = Color(0xFF0B1220).copy(alpha = 0.70f),
            // War 0.50 (3,40:1) - unter WCAG AA (4,5:1) für echten Informationstext. 0.62 ergibt 4,97:1.
            textMuted = Color(0xFF0B1220).copy(alpha = 0.62f),
            accent = a.main,
            accentAlt = a.partner,
            accentText = a.textOnLight,
            accentSoft = a.main.copy(alpha = 0.16f),
            onAccent = contentColorFor(a.main),
            scrim = Color(0xFF0B1220).copy(alpha = 0.45f),
            progressTrack = Color(0xFF0B1220).copy(alpha = 0.12f),
        )
    }
}

/** Farben des Ambient-Lichts: linke und rechte Bildhälfte, schon für den Hintergrund gezähmt. */
@Immutable
data class VideoAmbient(val left: Color, val right: Color)

/** Automatisch: von 7 bis 19 Uhr hell, sonst dunkel. */
fun isDarkFor(mode: ThemeMode, hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.AUTO -> hourOfDay < 7 || hourOfDay >= 19
}

/**
 * Farben der Oberfläche. Sie hängen am Compose-Snapshot-Zustand: Ein Wechsel zwischen hell und dunkel
 * oder der Akzentfarbe färbt die ganze Oberfläche sofort um, ohne Neustart.
 */
object AppColors {
    private var current by mutableStateOf(paletteFor(dark = true, accent = AccentColor.BRAND))
    private var darkVariant by mutableStateOf(current)
    private var videoScopes by mutableIntStateOf(0)

    /** „Effekte reduzieren“: Glas ohne Verlauf/Rand, Hintergrund ohne Farbflächen und Bilder. */
    var reducedEffects by mutableStateOf(false)

    /**
     * Ambient-Licht: Farben des laufenden Vorschaubilds (links, rechts). Der App-Hintergrund färbt seine beiden
     * Farbflächen sanft darauf um; null = wieder die Akzentfarben. Bei „Effekte reduzieren“ nie gesetzt.
     */
    var videoAmbient by mutableStateOf<VideoAmbient?>(null)

    /** Aktive Farben. Solange ein Videobild zu sehen ist ([VideoScope]), immer die dunkle Variante. */
    val palette: Palette get() = if (videoScopes > 0) darkVariant else current

    fun apply(dark: Boolean, accent: AccentColor) {
        val next = paletteFor(dark, accent)
        if (next != current) {
            current = next
            darkVariant = if (dark) next else paletteFor(dark = true, accent = accent)
        }
    }

    /**
     * Über Videos ist die Oberfläche immer dunkel (wie bei Apple TV): Helle Flächen über einem Film blenden
     * und machen Schrift schwer lesbar. Der Player ruft das auf, solange er sichtbar ist.
     */
    @Composable
    fun VideoScope() {
        DisposableEffect(Unit) {
            videoScopes++
            onDispose { videoScopes-- }
        }
    }

    val isDark: Boolean get() = palette.dark
    val Background: Color get() = palette.background
    val BackgroundAlt: Color get() = palette.backgroundAlt
    val AuroraA: Color get() = palette.auroraA
    val AuroraB: Color get() = palette.auroraB
    val Glass: Color get() = palette.glass
    val GlassRaised: Color get() = palette.glassRaised
    val GlassBorder: Color get() = palette.glassBorder
    val Highlight: Color get() = palette.highlight
    val Surface: Color get() = palette.surface
    val SurfaceHigh: Color get() = palette.surfaceHigh
    val SurfaceFocused: Color get() = palette.surfaceFocused
    val Border: Color get() = palette.border
    val FocusFill: Color get() = palette.focusFill
    val OnFocus: Color get() = palette.onFocus
    val Shadow: Color get() = palette.shadow
    val TextPrimary: Color get() = palette.textPrimary
    val TextSecondary: Color get() = palette.textSecondary
    val TextMuted: Color get() = palette.textMuted
    val Accent: Color get() = palette.accent
    val AccentAlt: Color get() = palette.accentAlt
    val AccentText: Color get() = palette.accentText
    val AccentSoft: Color get() = palette.accentSoft
    val OnAccent: Color get() = palette.onAccent
    val AccentBright: Color get() = lerp(palette.accent, Color.White, 0.18f)
    val Scrim: Color get() = palette.scrim
    val ProgressTrack: Color get() = palette.progressTrack

    /** Fast deckende Glasfläche für Dialoge und Seitenpanels (über dem abgedunkelten Hintergrund gut lesbar). */
    val Panel: Color get() = if (palette.dark) Color(0xFF141A2A).copy(alpha = 0.94f) else Color(0xFFF8FAFD).copy(alpha = 0.96f)

    /** Kachel hinter Senderlogos: immer dunkel, weil die meisten Logos für dunkle Flächen gemacht sind. */
    val LogoTile: Color get() = if (palette.dark) Color(0xFF1D2333) else Color(0xFF1F2737)
    val Progress: Color get() = palette.accent

    val Success = Color(0xFF22C55E)
    val Danger = Color(0xFFEF4444)
    val Rating = Color(0xFFFFC23D)
    val Live = Color(0xFFFF3B5C)

    /** Der Markenverlauf (Logo, Fortschrittsbalken, Ladeanzeige). */
    val BrandGradient: List<Color> get() = listOf(palette.accent, palette.accentAlt)

    fun brandBrush(): Brush = Brush.horizontalGradient(BrandGradient)
}

object Dimens {
    val TopBarHeight = 64.dp
    val ScreenPadding = 40.dp
    val ContentTop = 84.dp
    // Fließende Ecken (AppShapes) wirken kleiner als Kreisbögen – darum etwas größere Radien als bis 3.32.
    val CardRadius = 20.dp
    val PanelRadius = 28.dp
    val RowRadius = 16.dp
}

/** Inter (SIL Open Font License 1.1): klar, modern, gut lesbar auf Abstand – Display-Schnitt für Überschriften. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val InterDisplay = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
    Font(R.font.inter_display_semibold, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.Normal),
)

private val typography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        displayMedium = base.displayMedium.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        displaySmall = base.displaySmall.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
        bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
        bodySmall = base.bodySmall.copy(fontFamily = Inter),
        labelLarge = base.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    val palette = AppColors.palette
    val colorScheme = remember(palette) {
        if (palette.dark) {
            darkColorScheme(
                primary = palette.accent,
                onPrimary = Color.White,
                secondary = palette.surfaceFocused,
                onSecondary = palette.textPrimary,
                background = palette.background,
                onBackground = palette.textPrimary,
                surface = palette.surface,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.surfaceHigh,
                onSurfaceVariant = palette.textSecondary,
                border = palette.border,
            )
        } else {
            lightColorScheme(
                primary = palette.accent,
                onPrimary = Color.White,
                secondary = palette.surfaceFocused,
                onSecondary = palette.textPrimary,
                background = palette.background,
                onBackground = palette.textPrimary,
                surface = palette.surface,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.surfaceHigh,
                onSurfaceVariant = palette.textSecondary,
                border = palette.border,
            )
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        // Text ohne eigene Farbe (und außerhalb von Flächen) folgt hell/dunkel – sonst wäre er schwarz.
        CompositionLocalProvider(LocalContentColor provides palette.textPrimary, content = content)
    }
}
