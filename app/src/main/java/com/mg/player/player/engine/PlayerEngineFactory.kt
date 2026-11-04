package com.mg.player.player.engine

import android.content.Context
import com.mg.player.domain.model.PlayerEngineType
import okhttp3.OkHttpClient

class PlayerEngineFactory(private val context: Context, private val httpClient: OkHttpClient) {

    fun create(type: PlayerEngineType): VideoPlayerEngine = when (type) {
        PlayerEngineType.MEDIA3 -> Media3Engine(context, httpClient)
        PlayerEngineType.LIBVLC -> LibVlcEngine(context)
        PlayerEngineType.NATIVE -> NativeEngine(context)
        PlayerEngineType.EXTERNAL -> ExternalPlayerEngine(context)
    }

    /** Für die Live-Vorschau kommt nur eine interne Engine in Frage. */
    fun previewType(preferred: PlayerEngineType): PlayerEngineType = if (preferred == PlayerEngineType.EXTERNAL) PlayerEngineType.MEDIA3 else preferred
}
