package com.mg.player.domain.binge

import com.mg.player.domain.model.ChapterInfo
import com.mg.player.domain.model.IntroProfile

enum class IntroSource { CHAPTER, PROFILE, FALLBACK }

data class IntroWindow(val startMs: Long, val endMs: Long, val source: IntroSource)

/** Erkennt Intro-, Recap- und Abspann-Kapitel anhand ihrer Titel. */
object ChapterMatcher {

    private val introWords = setOf("intro", "opening", "op", "vorspann", "recap", "rueckblick", "rückblick", "previously", "titelsequenz")
    private val outroWords = setOf("outro", "credits", "abspann", "ending", "ed", "nachspann", "endcredits")

    private fun words(title: String): List<String> = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun findIntro(chapters: List<ChapterInfo>): ChapterInfo? = chapters.firstOrNull { chapter ->
        chapter.endMs > chapter.startMs &&
            chapter.startMs < MAX_INTRO_START_MS &&
            words(chapter.title).any { it in introWords }
    }

    fun findOutroStart(chapters: List<ChapterInfo>, durationMs: Long): Long? = chapters.lastOrNull { chapter ->
        chapter.startMs > durationMs / 2 && words(chapter.title).any { it in outroWords }
    }?.startMs

    private const val MAX_INTRO_START_MS = 15 * 60_000L
}

/**
 * 3-Stufen-Intro-Erkennung:
 * 1. Kapitel aus dem Stream, 2. gespeichertes Serien-Profil (Room), 3. Fallback-Zeitfenster.
 */
object IntroResolver {

    const val FALLBACK_WINDOW_START_MS = 20_000L
    const val FALLBACK_WINDOW_END_MS = 210_000L

    fun resolve(chapters: List<ChapterInfo>, profile: IntroProfile?): IntroWindow? {
        ChapterMatcher.findIntro(chapters)?.let { return IntroWindow(it.startMs, it.endMs, IntroSource.CHAPTER) }
        if (profile != null && profile.introEndMs > profile.introStartMs) {
            return IntroWindow(profile.introStartMs, profile.introEndMs, IntroSource.PROFILE)
        }
        return null
    }

    fun fallback(): IntroWindow = IntroWindow(FALLBACK_WINDOW_START_MS, FALLBACK_WINDOW_END_MS, IntroSource.FALLBACK)
}
