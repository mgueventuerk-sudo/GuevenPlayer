package com.mg.player.domain.model

import com.mg.player.data.sport.ChannelEventMatcher
import com.mg.player.data.sport.guide.GuideMatching
import com.mg.player.data.sport.guide.KickoffCheck
import com.mg.player.data.sport.guide.SportChannelFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Herkunft der Sender-Zuordnung (Plan „Spiel-Finder“ A/J): jeder Score landet bei der richtigen Quelle. */
class MatchSourceTest {

    @Test
    fun everyScoreHasItsSource() {
        assertEquals(MatchSource.CONFIRMED, MatchSource.of(KickoffCheck.CONFIRMED))
        assertEquals(MatchSource.GUIDE, MatchSource.of(GuideMatching.BOTH_TEAMS + 1))
        assertEquals(MatchSource.STATION, MatchSource.of(SportChannelFinder.STATION))
        assertEquals(MatchSource.EPG, MatchSource.of(80))
        assertEquals(MatchSource.GUIDE_WEAK, MatchSource.of(GuideMatching.ONE_TEAM))
        assertEquals(MatchSource.KEYWORD, MatchSource.of(60))
        assertEquals(MatchSource.MANUAL, MatchSource.of(ChannelEventMatcher.MANUAL_SCORE + 1))
    }

    /** Event-Sender ohne Beweis und widerlegte Sender liegen unter der Anzeigegrenze. */
    @Test
    fun eventChannelsAndContradictedAreNeverShown() {
        assertTrue(SportChannelFinder.EVENT_CHANNEL + 1 < MatchSource.DISPLAY_MIN)
        assertTrue(KickoffCheck.OTHER + 1 < MatchSource.DISPLAY_MIN)
        assertTrue(ChannelEventMatcher.MANUAL_SCORE >= MatchSource.DISPLAY_MIN)
    }

    @Test
    fun onlyRealDataSourcesAreSwitchable() {
        assertFalse(MatchSource.CONFIRMED.switchable)
        assertFalse(MatchSource.MANUAL.switchable)
        assertTrue(MatchSource.GUIDE.switchable && MatchSource.STATION.switchable && MatchSource.EPG.switchable)
    }
}
