package com.mg.player.player.proof

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AcquireImageSafelyTest {
    @Test
    fun unsupportedDecoderFormatDoesNotEscapeImageCallback() {
        val error = UnsupportedOperationException("format mismatch")
        var reported: RuntimeException? = null

        val image = acquireImageSafely(
            acquire = { throw error },
            onFailure = { reported = it },
        )

        assertNull(image)
        assertSame(error, reported)
    }
}
