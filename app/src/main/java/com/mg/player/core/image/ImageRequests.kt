package com.mg.player.core.image

import android.content.Context
import androidx.compose.ui.unit.IntSize
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale

/**
 * Die Bild-Anfragen von Poster und Senderlogo an **einer** Stelle: Anzeige und Vorladen bauen sie aus derselben Quelle.
 * Nur so passt das vorgeladene Bild zum Schlüssel, unter dem die Anzeige später im Arbeitsspeicher nachschaut.
 */
internal object ImageRequests {
    /** Poster: undurchsichtig, deshalb RGB_565 (halber Speicher); Größe ergibt sich in der Anzeige aus der Kachel. */
    fun poster(context: Context, url: String?): ImageRequest = ImageRequest.Builder(context).data(url).allowRgb565(true).crossfade(false).build()

    /**
     * Dasselbe Poster zum Vorladen in den Arbeitsspeicher: in der Kachelgröße [size] (vom Raster selbst gemessen) und so,
     * wie Coil es für `ContentScale.Crop` selbst einstellt (Scale.FILL, ungefähre Genauigkeit).
     */
    fun posterAhead(context: Context, url: String, size: IntSize): ImageRequest =
        poster(context, url).newBuilder().size(size.width, size.height).scale(Scale.FILL).precision(Precision.INEXACT).build()

    /** Senderlogo: oft transparent, deshalb ARGB_8888; feste Größe. */
    fun channelLogo(context: Context, url: String?): ImageRequest =
        ImageRequest.Builder(context).data(url).size(LOGO_WIDTH, LOGO_HEIGHT).allowRgb565(false).build()

    /** Dasselbe Logo zum Vorladen (Anzeige: `ContentScale.Fit` = Scale.FIT). */
    fun channelLogoAhead(context: Context, url: String): ImageRequest = channelLogo(context, url).newBuilder().scale(Scale.FIT).build()

    private const val LOGO_WIDTH = 160
    private const val LOGO_HEIGHT = 120
}
