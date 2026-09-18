package com.example.commaengdoughme

import kotlin.math.*

/** Screen coordinates: +X right, +Y up, forward -Z. Matrices map screen to world. */
data class Ray(val x: Double, val y: Double, val z: Double)
data class ScreenPoint(val x: Float, val y: Float)
data class HeadPose(val rotation: List<Double>, val timestampNs: Long, val generation: Long) {
    init { require(rotation.size == 9 && rotation.all { it.isFinite() }) }
}
data class FocusRegion(val label: String, val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 } && left < right && top < bottom
}
data class SpatialCalibration(
    val displayHorizontal: Double = 30.0, val displayVertical: Double = 22.0,
    val cameraHorizontal: Double = 60.0, val cameraVertical: Double = 45.0,
    val cameraYaw: Double = 0.0, val cameraPitch: Double = 0.0,
) {
    fun valid() = listOf(displayHorizontal, displayVertical, cameraHorizontal, cameraVertical).all { it.isFinite() && it in 5.0..150.0 } &&
        listOf(cameraYaw, cameraPitch).all { it.isFinite() && it in -90.0..90.0 }
}
data class SpatialAnchor(val rays: List<Ray>, val generation: Long, val label: String)

object SpatialMath {
    fun cameraPoint(point: ScreenPoint, calibration: SpatialCalibration): ScreenPoint? {
        if (!calibration.valid() || !point.x.isFinite() || !point.y.isFinite()) return null
        val ray = cameraMount(Ray((2 * point.x - 1) * tangent(calibration.cameraHorizontal),
            (1 - 2 * point.y) * tangent(calibration.cameraVertical), -1.0), calibration.cameraYaw, calibration.cameraPitch)
        if (ray.z >= -0.05) return null
        val x = (0.5 + ray.x / -ray.z / (2 * tangent(calibration.displayHorizontal))).toFloat()
        val y = (0.5 - ray.y / -ray.z / (2 * tangent(calibration.displayVertical))).toFloat()
        return if (x.isFinite() && y.isFinite()) ScreenPoint(x, y) else null
    }
    private fun tangent(degrees: Double) = tan(Math.toRadians(degrees) / 2)
    fun rotate(m: List<Double>, v: Ray) = Ray(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )
    fun inverseRotate(m: List<Double>, v: Ray) = Ray(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )
    private fun cameraMount(v: Ray, yaw: Double, pitch: Double): Ray {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        val pitched = Ray(v.x, cos(p) * v.y - sin(p) * v.z, sin(p) * v.y + cos(p) * v.z)
        return Ray(cos(y) * pitched.x + sin(y) * pitched.z, pitched.y, -sin(y) * pitched.x + cos(y) * pitched.z)
    }
    fun anchor(region: FocusRegion, pose: HeadPose, calibration: SpatialCalibration, camera: Boolean): SpatialAnchor {
        require(region.valid() && calibration.valid())
        val tx = tangent(if (camera) calibration.cameraHorizontal else calibration.displayHorizontal)
        val ty = tangent(if (camera) calibration.cameraVertical else calibration.displayVertical)
        val rays = listOf(region.left to region.top, region.right to region.top,
            region.right to region.bottom, region.left to region.bottom).map { (u, v) ->
            val ray = Ray((2 * u - 1) * tx, (1 - 2 * v) * ty, -1.0)
            rotate(pose.rotation, if (camera) cameraMount(ray, calibration.cameraYaw, calibration.cameraPitch) else ray)
        }
        return SpatialAnchor(rays, pose.generation, region.label)
    }
    /** Cull behind/near-plane anchors. Clip partially visible polygons in the drawing layer. */
    fun project(anchor: SpatialAnchor, pose: HeadPose, calibration: SpatialCalibration): List<ScreenPoint>? {
        if (anchor.generation != pose.generation || !calibration.valid()) return null
        val rays = anchor.rays.map { inverseRotate(pose.rotation, it) }
        if (rays.any { it.z >= -0.05 }) return null
        val points = rays.map {
            ScreenPoint((0.5 + it.x / -it.z / (2 * tangent(calibration.displayHorizontal))).toFloat(),
                (0.5 - it.y / -it.z / (2 * tangent(calibration.displayVertical))).toFloat())
        }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        if (points.all { it.x < 0 } || points.all { it.x > 1 } || points.all { it.y < 0 } || points.all { it.y > 1 }) return null
        return points
    }
}
