package com.mg.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewResolutionTest {

    @Test
    fun missingOrInvalidDimensionsStayUnknown() {
        assertNull(measuredPreviewResolution("channel", null, 1080))
        assertNull(measuredPreviewResolution("channel", 1920, 0))
    }

    @Test
    fun labelUsesExactDecoderDimensions() {
        val measured = measuredPreviewResolution("channel", 1920, 1080)

        assertEquals(PreviewResolution("channel", 1920, 1080), measured)
        assertEquals("1920×1080", measured?.label)
    }
}
