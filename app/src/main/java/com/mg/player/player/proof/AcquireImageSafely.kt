package com.mg.player.player.proof

/** Protects framework callbacks from runtime failures raised while a device decoder publishes a frame. */
@Suppress("TooGenericExceptionCaught") // Vendor decoders can throw different RuntimeExceptions from ImageReader.
internal inline fun <T> acquireImageSafely(acquire: () -> T?, onFailure: (RuntimeException) -> Unit): T? =
    try {
        acquire()
    } catch (error: RuntimeException) {
        onFailure(error)
        null
    }
