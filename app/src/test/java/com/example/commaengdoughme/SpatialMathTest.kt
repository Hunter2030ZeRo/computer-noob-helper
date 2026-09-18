package com.example.commaengdoughme

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SpatialMathTest {
    private val identity = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private val calibration = SpatialCalibration(90.0, 90.0, 90.0, 90.0)
    private val region = FocusRegion("error", .4, .4, .6, .6)
    private fun pose(matrix: List<Double> = identity, generation: Long = 1) = HeadPose(matrix, 100L, generation)
    private fun yaw(degrees: Double): List<Double> {
        val r = Math.toRadians(degrees)
        return listOf(cos(r), 0.0, sin(r), 0.0, 1.0, 0.0, -sin(r), 0.0, cos(r))
    }

    @Test fun stationaryAnchorAndReturnToOriginalOrientation() {
        val anchor = SpatialMath.anchor(region, pose(), calibration, false)
        val original = SpatialMath.project(anchor, pose(), calibration)!!
        assertEquals(.4f, original[0].x, .0001f)
        assertEquals(.4f, original[0].y, .0001f)
        val turned = SpatialMath.project(anchor, pose(yaw(20.0)), calibration)!!
        assertTrue(turned[0].x > original[0].x)
        assertEquals(original, SpatialMath.project(anchor, pose(), calibration))
    }

    @Test fun capturePoseIsUsedRatherThanResponseTimePose() {
        val captured = pose(yaw(20.0))
        val anchor = SpatialMath.anchor(region, captured, calibration, true)
        val sameView = SpatialMath.project(anchor, captured, calibration)!!
        assertEquals(.4f, sameView[0].x, .0001f)
        val later = SpatialMath.project(anchor, pose(), calibration)!!
        assertTrue(later[0].x < sameView[0].x)
    }

    @Test fun rollRotatesBothAxesAndPitchMovesVertically() {
        val anchor = SpatialMath.anchor(region, pose(), calibration, false)
        val roll = listOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        val rotated = SpatialMath.project(anchor, pose(roll), calibration)!!
        assertEquals(.6f, rotated[0].x, .0001f)
        assertEquals(.4f, rotated[0].y, .0001f)
        val r = Math.toRadians(20.0)
        val pitch = listOf(1.0, 0.0, 0.0, 0.0, cos(r), -sin(r), 0.0, sin(r), cos(r))
        assertTrue(SpatialMath.project(anchor, pose(pitch), calibration)!![0].y > .4f)
    }

    @Test fun behindCameraOutOfViewAndInvalidSessionAreHidden() {
        val anchor = SpatialMath.anchor(region, pose(), calibration, false)
        assertNull(SpatialMath.project(anchor, pose(yaw(180.0)), calibration))
        assertNull(SpatialMath.project(anchor, pose(yaw(80.0)), calibration))
        assertNull(SpatialMath.project(anchor, pose(generation = 2), calibration))
    }

    @Test fun invalidRegionsAndFovRejected() {
        assertFalse(FocusRegion("bad", Double.NaN, 0.0, 1.0, 1.0).valid())
        assertFalse(FocusRegion("bad", .8, .1, .2, .3).valid())
        assertFalse(SpatialCalibration(displayHorizontal = 180.0).valid())
        assertFalse(SpatialCalibration(cameraPitch = Double.NaN).valid())
    }

    @Test fun cameraMountOffsetChangesWorldDirection() {
        val calibrated = calibration.copy(cameraYaw = 15.0)
        val anchor = SpatialMath.anchor(region, pose(), calibrated, true)
        assertTrue(SpatialMath.project(anchor, pose(), calibrated)!![0].x < .4f)
    }
}
