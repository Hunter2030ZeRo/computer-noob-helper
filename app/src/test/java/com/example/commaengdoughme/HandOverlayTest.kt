package com.example.commaengdoughme

import org.junit.Assert.*
import org.junit.Test

class HandOverlayTest {
    @Test fun equalFieldsOfViewPreserveNormalizedCoordinates() {
        val c = SpatialCalibration(cameraHorizontal = 30.0, cameraVertical = 22.0)
        val point = SpatialMath.cameraPoint(ScreenPoint(.2f, .7f), c)!!
        assertEquals(.2f, point.x, .0001f)
        assertEquals(.7f, point.y, .0001f)
    }
    @Test fun widerCameraDoesNotSqueezeOffscreenHandsIntoDisplay() {
        val c = SpatialCalibration()
        assertEquals(.5f, SpatialMath.cameraPoint(ScreenPoint(.5f, .5f), c)!!.x, .0001f)
        assertTrue(SpatialMath.cameraPoint(ScreenPoint(0f, .5f), c)!!.x < 0)
        assertNull(SpatialMath.cameraPoint(ScreenPoint(Float.NaN, .5f), c))
        assertNull(SpatialMath.cameraPoint(ScreenPoint(.5f, .5f), c.copy(displayHorizontal = 0.0)))
    }
    @Test fun oldAndFutureHandFramesAreNotDisplayed() {
        val observation = HandObservation(emptyList(), 1000, 50, "", null)
        assertFalse(observation.fresh(999))
        assertTrue(observation.fresh(1700))
        assertFalse(observation.fresh(1701))
    }
}
