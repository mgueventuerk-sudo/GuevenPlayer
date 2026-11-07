package com.mg.player.player.proof

/** Ab Android 8 kopiert PixelCopy das Videobild ohne Zugriff auf ImageReader-Pufferspeicher. */
internal object ProofCapturePolicy {
    private const val PIXEL_COPY_FROM_API = 26

    fun usePixelCopy(apiLevel: Int): Boolean = apiLevel >= PIXEL_COPY_FROM_API
}
