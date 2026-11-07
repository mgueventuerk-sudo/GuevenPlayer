package com.mg.player.player.proof

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ProofCapturePolicyTest {
    @Test fun usesLegacyDecoderOnlyWithoutPixelCopy() {
        assertFalse(ProofCapturePolicy.usePixelCopy(24))
        assertFalse(ProofCapturePolicy.usePixelCopy(25))
    }

    @Test fun copiesFramesWithoutDirectBufferAccessOnModernAndroid() {
        assertTrue(ProofCapturePolicy.usePixelCopy(26))
        assertTrue(ProofCapturePolicy.usePixelCopy(34))
        assertTrue(ProofCapturePolicy.usePixelCopy(37))
        assertTrue(ProofCapturePolicy.usePixelCopy(38))
    }
}
