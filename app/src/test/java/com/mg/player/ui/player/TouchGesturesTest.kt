package com.mg.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Rechenregeln der Handy-Gesten im Player. */
class TouchGesturesTest {

    @Test
    fun screenIsSplitInTheMiddle() {
        assertEquals(TouchSide.LEFT, sideOf(0f, 1000f))
        assertEquals(TouchSide.LEFT, sideOf(499f, 1000f))
        assertEquals(TouchSide.RIGHT, sideOf(500f, 1000f))
        assertEquals(TouchSide.RIGHT, sideOf(999f, 1000f))
    }

    @Test
    fun doubleTapSeeksVodAndZapsLive() {
        assertEquals(DoubleTapAction.SEEK_BACK, doubleTapAction(TouchSide.LEFT, isLive = false))
        assertEquals(DoubleTapAction.SEEK_FORWARD, doubleTapAction(TouchSide.RIGHT, isLive = false))
        assertEquals(DoubleTapAction.PREVIOUS_CHANNEL, doubleTapAction(TouchSide.LEFT, isLive = true))
        assertEquals(DoubleTapAction.NEXT_CHANNEL, doubleTapAction(TouchSide.RIGHT, isLive = true))
    }

    @Test
    fun rightSideIsVolumeLeftSideIsBrightness() {
        assertEquals(SwipeLevel.VOLUME, swipeLevelFor(TouchSide.RIGHT))
        assertEquals(SwipeLevel.BRIGHTNESS, swipeLevelFor(TouchSide.LEFT))
    }

    @Test
    fun swipingUpRaisesSwipingDownLowers() {
        // Nach oben = negative y-Bewegung.
        assertEquals(0.75f, adjustLevel(0.5f, dragY = -200f, heightPx = 1000f), 0.0001f)
        assertEquals(0.25f, adjustLevel(0.5f, dragY = 200f, heightPx = 1000f), 0.0001f)
    }

    @Test
    fun levelStaysBetweenZeroAndOne() {
        assertEquals(1f, adjustLevel(0.9f, dragY = -5000f, heightPx = 1000f), 0f)
        assertEquals(0f, adjustLevel(0.1f, dragY = 5000f, heightPx = 1000f), 0f)
        // Ohne Höhe (noch nicht gemessen) bleibt alles, wie es ist.
        assertEquals(0.4f, adjustLevel(0.4f, dragY = -100f, heightPx = 0f), 0f)
    }

    @Test
    fun aSwipeOverMostOfTheScreenCoversTheWholeRange() {
        assertEquals(1f, adjustLevel(0f, dragY = -800f, heightPx = 1000f), 0.0001f)
    }

    @Test
    fun volumeMapsToDeviceSteps() {
        assertEquals(0, volumeIndex(0f, 15))
        assertEquals(8, volumeIndex(0.5f, 15))
        assertEquals(15, volumeIndex(1f, 15))
        assertEquals(15, volumeIndex(3f, 15))
        assertEquals(0, volumeIndex(-1f, 15))
    }

    @Test
    fun seekBarFractionFollowsTheFinger() {
        assertEquals(0f, seekFraction(-20f, 400f), 0f)
        assertEquals(0.25f, seekFraction(100f, 400f), 0.0001f)
        assertEquals(1f, seekFraction(500f, 400f), 0f)
        assertEquals(0f, seekFraction(100f, 0f), 0f)
    }
}
