package com.mg.player.player.proof

/** Android 17: selbst geprüfte DirectByteBuffer-Zugriffe auf ImageReader-Frames stürzten nativ ab. */
internal object ProofCapturePolicy {
    private const val UNSAFE_FROM_API = 37

    fun supported(apiLevel: Int): Boolean = apiLevel < UNSAFE_FROM_API
}
