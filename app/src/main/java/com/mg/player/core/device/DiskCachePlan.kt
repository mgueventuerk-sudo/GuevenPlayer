package com.mg.player.core.device

/**
 * Wie groß der Bild-Cache auf dem internen Speicher sein darf – je nach freiem Platz beim Start. Er ist ein Cache: Android
 * darf ihn bei Platznot ohnehin leeren, und er verdrängt von selbst das Älteste. Vorher fest 256 MB, auch auf einem Gerät
 * mit fast vollem Speicher.
 */
object DiskCachePlan {
    private const val MB = 1024L * 1024L
    private const val TINY_MB = 64L
    private const val SMALL_MB = 128L
    private const val NORMAL_MB = 256L
    private const val LARGE_MB = 512L

    fun maxBytes(freeMb: Long): Long = MB * when {
        freeMb < PreloadPolicyRules.MIN_STORAGE_MB -> TINY_MB
        freeMb < PreloadPolicyRules.ROOMY_STORAGE_MB -> SMALL_MB
        freeMb < PreloadPolicyRules.PLENTY_STORAGE_MB -> NORMAL_MB
        else -> LARGE_MB
    }
}
