package com.mg.player.player.proof

/** Main-thread ownership: hält das Bild während PixelCopy und leert danach die angestaute Queue. */
internal class LatestProofFrame<T : AutoCloseable>(private val acquire: () -> T?) : AutoCloseable {
    var image: T? = null
        private set
    private var copying = false
    private var closed = false

    fun onFrame() {
        if (closed || copying) return
        val next = acquire() ?: return
        image?.close()
        image = next
    }

    fun beginCopy() {
        copying = true
    }

    fun finishCopy() {
        copying = false
        // Während PixelCopy übersprungene Events werden nicht erneut zugestellt.
        // Ohne Abholen kann die volle Queue den Decoder dauerhaft anhalten.
        onFrame()
    }

    override fun close() {
        closed = true
        image?.close()
        image = null
    }
}
