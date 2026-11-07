package com.mg.player.player.proof

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofCapturePolicyTest {
    @Test fun keepsImageProofOnOlderAndroid() {
        assertTrue(ProofCapturePolicy.supported(24))
        assertTrue(ProofCapturePolicy.supported(34))
        assertTrue(ProofCapturePolicy.supported(36))
    }

    @Test fun avoidsNativeBufferReadsOnAndroid17AndLater() {
        assertFalse(ProofCapturePolicy.supported(37))
        assertFalse(ProofCapturePolicy.supported(38))
    }
}
