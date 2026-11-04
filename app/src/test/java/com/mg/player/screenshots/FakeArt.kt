package com.mg.player.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlin.math.abs

/**
 * Erzeugt Poster, Hintergrundbilder, Senderlogos und Episodenbilder direkt im Test (kein Netz nötig):
 * Farbverläufe mit Lichtflecken und dem Titel – damit die Bildschirmfotos wie mit echtem Inhalt aussehen.
 */
object FakeArt {

    // setUnsafe: Tests ersetzen den Bild-Lader bewusst, auch wenn schon einer erzeugt wurde.
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    fun install(context: Context) {
        val loader = ImageLoader.Builder(context)
            .components { add(Interceptor { chain -> chain.respond() }) }
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    private fun Interceptor.Chain.respond(): ImageResult {
        val uri = Uri.parse(request.data.toString())
        val kind = uri.pathSegments.firstOrNull() ?: "poster"
        val title = uri.getQueryParameter("t") ?: uri.lastPathSegment.orEmpty()
        val bitmap = when (kind) {
            "logo" -> logo(title)
            "backdrop" -> scene(title, 1280, 720, withTitle = false)
            "still" -> scene(title, 480, 270, withTitle = false)
            else -> poster(title)
        }
        return SuccessResult(bitmap.asImage(), request, DataSource.MEMORY)
    }

    private fun palette(seed: String): Pair<Int, Int> {
        val h = abs(seed.hashCode())
        val hue = (h % 360).toFloat()
        val a = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.55f))
        val b = Color.HSVToColor(floatArrayOf((hue + 40 + h % 60) % 360, 0.75f, 0.22f))
        return a to b
    }

    fun scene(seed: String, w: Int, h: Int, withTitle: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val (a, b) = palette(seed)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Lichtflecken für Tiefe.
        val spots = listOf(0.25f to 0.3f, 0.75f to 0.65f, 0.55f to 0.15f)
        spots.forEachIndexed { i, (x, y) ->
            p.shader = RadialGradient(
                w * x,
                h * y,
                w * (0.35f - i * 0.07f),
                Color.argb(110 - i * 25, 255, 240, 220),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        // Horizont / Silhouette.
        p.shader = null
        p.color = Color.argb(120, 0, 0, 0)
        val path = android.graphics.Path().apply {
            moveTo(0f, h * 0.78f)
            cubicTo(w * 0.25f, h * 0.62f, w * 0.45f, h * 0.9f, w * 0.7f, h * 0.7f)
            cubicTo(w * 0.85f, h * 0.6f, w * 0.95f, h * 0.75f, w.toFloat(), h * 0.7f)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(0f, h.toFloat())
            close()
        }
        c.drawPath(path, p)
        if (withTitle) drawTitle(c, seed, w, h)
        return bmp
    }

    private fun poster(title: String): Bitmap {
        val w = 300
        val h = 450
        val bmp = scene(title, w, h, withTitle = false)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(210, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        drawTitle(c, title, w, h)
        return bmp
    }

    private fun drawTitle(c: Canvas, title: String, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = w * 0.11f
            letterSpacing = 0.02f
        }
        val words = title.uppercase().split(" ")
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(candidate) > w * 0.84f && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        var y = h - 26f - (lines.size - 1) * p.textSize * 1.1f
        lines.forEach {
            c.drawText(it, w * 0.08f, y, p)
            y += p.textSize * 1.1f
        }
    }

    /** Senderlogo: Name in kräftiger Schrift auf transparentem Grund, mit farbigem Akzent. */
    private fun logo(name: String): Bitmap {
        val w = 320
        val h = 180
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val (a, _) = palette(name)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 64f
        }
        val text = name.take(10)
        while (p.measureText(text) > w * 0.86f) p.textSize -= 2f
        val tw = p.measureText(text)
        c.drawText(text, (w - tw) / 2f, h / 2f + p.textSize / 3f, p)
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = a }
        c.drawRoundRect(RectF((w - tw) / 2f, h / 2f + p.textSize / 2.2f, (w - tw) / 2f + tw * 0.4f, h / 2f + p.textSize / 2.2f + 10f), 5f, 5f, accent)
        return bmp
    }
}
