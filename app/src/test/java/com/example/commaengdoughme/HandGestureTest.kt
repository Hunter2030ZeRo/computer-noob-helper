package com.example.commaengdoughme

import org.junit.Assert.*
import org.junit.Test

class HandGestureTest {
    private fun open(dx: Float = 0f): List<HandPoint> {
        val p = MutableList(21) { HandPoint(.5f + dx, .7f) }
        p[0] = HandPoint(.5f + dx, .95f)
        p[4] = HandPoint(.15f + dx, .55f)
        for ((base, x) in listOf(5 to .3f, 9 to .45f, 13 to .6f, 17 to .7f)) {
            p[base] = HandPoint(x + dx, .7f)
            p[base + 1] = HandPoint(x + dx, .55f)
            p[base + 2] = HandPoint(x + dx, .4f)
            p[base + 3] = HandPoint(x + dx, .25f)
        }
        return p
    }
    private fun pinch() = open().toMutableList().also { it[4] = it[8].copy(x = it[8].x + .03f) }

    @Test fun pinchRequiresHoldAndReleaseAndDoesNotRepeat() {
        val decoder = HandGestureDecoder()
        assertNull(decoder.update(pinch(), 0))
        assertNull(decoder.update(pinch(), 100))
        assertNull(decoder.update(pinch(), 200))
        assertEquals(HandGesture.PINCH, decoder.update(pinch(), 350))
        for (time in 400L..1800L step 100) assertNull(decoder.update(pinch(), time))
        decoder.update(emptyList(), 1900)
        decoder.update(emptyList(), 2150)
        assertNull(decoder.update(pinch(), 2200))
        assertNull(decoder.update(pinch(), 2300))
        assertNull(decoder.update(pinch(), 2400))
        assertEquals(HandGesture.PINCH, decoder.update(pinch(), 2550))
    }

    @Test fun swipeDirectionsAndOpenPalmHold() {
        val right = HandGestureDecoder()
        right.update(open(), 0)
        assertNull(right.update(open(.25f), 250))
        assertEquals(HandGesture.NEXT, right.update(open(.26f), 350))
        val left = HandGestureDecoder()
        left.update(open(), 0)
        assertNull(left.update(open(-.25f), 250))
        assertEquals(HandGesture.PREVIOUS, left.update(open(-.26f), 350))
        val hold = HandGestureDecoder()
        for (time in 0L..900L step 100) assertNull(hold.update(open(), time))
        assertNull(hold.update(open(), 1000))
        assertNull(hold.update(open(), 1100))
    }

    @Test fun missingFramesAndInvalidLandmarksDoNotCompleteAHold() {
        val decoder = HandGestureDecoder()
        decoder.update(pinch(), 0)
        assertNull(decoder.update(pinch(), 700))
        assertNull(decoder.update(List(21) { HandPoint(Float.NaN, 0f) }, 800))
        assertNull(decoder.update(emptyList(), 900))
    }

    @Test fun sparsePinchesAndOneFrameSwipesDoNotTrigger() {
        val pinchDecoder = HandGestureDecoder()
        assertNull(pinchDecoder.update(pinch(), 0))
        assertNull(pinchDecoder.update(pinch(), 400))
        assertNull(pinchDecoder.update(emptyList(), 500))
        assertNull(pinchDecoder.update(pinch(), 600))
        val swipe = HandGestureDecoder()
        swipe.update(open(), 0)
        assertNull(swipe.update(open(.25f), 200))
        assertNull(swipe.update(open(), 300))
        assertNull(swipe.update(open(.25f), 400))
    }

    @Test fun pinchRearmsAfterBriefLostHandFollowedByOpenHand() {
        val decoder = HandGestureDecoder()
        for (time in listOf(0L, 100L, 200L)) assertNull(decoder.update(pinch(), time))
        assertEquals(HandGesture.PINCH, decoder.update(pinch(), 350))
        // Losing one frame used to clear mode and prevent an open hand releasing the latch.
        decoder.update(emptyList(), 450)
        for (time in 550L..1350L step 100) assertNull(decoder.update(open(), time))
        for (time in listOf(1450L, 1550L, 1650L)) assertNull(decoder.update(pinch(), time))
        assertEquals(HandGesture.PINCH, decoder.update(pinch(), 1800))
    }
}
