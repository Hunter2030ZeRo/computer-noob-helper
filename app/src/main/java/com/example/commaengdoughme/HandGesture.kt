package com.example.commaengdoughme

import kotlin.math.abs
import kotlin.math.sqrt

data class HandPoint(val x: Float, val y: Float, val z: Float = 0f)
enum class HandGesture { PINCH, NEXT, PREVIOUS }
data class GestureEvent(val sequence: Long, val gesture: HandGesture, val atMs: Long)

/** Temporal, palm-relative gestures. Each action requires release before another action. */
class HandGestureDecoder {
    private var mode = ""
    private var since = 0L
    private var originX = 0f
    private var originY = 0f
    private var lastTime = -1L
    private var latched = false
    private var neutralSince: Long? = null
    private var stableFrames = 0
    private var swipeFrames = 0
    private var previousX = 0f
    private var swipeDirection = 0
    private var lastAction = -2000L
    private var lastGesture: HandGesture? = null
    val hint: String get() = when { latched -> if (lastGesture == HandGesture.PINCH) "손을 펼쳐 해제" else "손 내렸다 다시 펼치기"; mode == "pinch" -> "핀치 유지"; mode == "open" -> "손날 좌우로 넘기기"; else -> "입력 준비" }

    fun reset() { stableFrames = 0; swipeFrames = 0; mode = ""; since = 0; lastTime = -1; latched = false; neutralSince = null; lastAction = -2000; lastGesture = null }

    fun update(points: List<HandPoint>, now: Long): HandGesture? {
        if (lastTime >= 0 && (now <= lastTime || now - lastTime >= 700)) {
            mode = ""; since = now; neutralSince = null; stableFrames = 0; swipeFrames = 0
        }
        lastTime = now
        fun distance(a: Int, b: Int) = sqrt((points[a].x - points[b].x).let { it * it } + (points[a].y - points[b].y).let { it * it } + (points[a].z - points[b].z).let { it * it })
        val valid = points.size == 21 && points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
        val palm = if (valid) distance(5, 17) else 0f
        val pinch = valid && palm > .06f && distance(4, 8) / palm < (if (mode == "pinch") .36f else .28f)
        val open = valid && palm > .06f && !pinch && listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18).count {
            distance(it.first, 0) > distance(it.second, 0) * 1.15f
        } >= 3
        if (!pinch && !open) {
            if (neutralSince == null) neutralSince = now
            if (now - neutralSince!! >= 200) latched = false
            mode = ""; stableFrames = 0; swipeFrames = 0
            return null
        }
        // Open hand is also a release of a pinch, but cannot activate until it settles.
        if (latched && lastGesture == HandGesture.PINCH && !pinch) {
            if (neutralSince == null) neutralSince = now
            if (now - neutralSince!! >= 200) { latched = false; mode = "" }
        } else neutralSince = null
        if (latched || now - lastAction < 900) return null
        val next = if (pinch) "pinch" else "open"
        val center = points[9]
        if (mode != next) { mode = next; since = now; originX = center.x; originY = center.y; previousX = center.x; swipeDirection = 0; stableFrames = 0; swipeFrames = 0 }
        val dx = center.x - originX
        val dy = center.y - originY
        stableFrames++
        val movement = center.x - previousX
        previousX = center.x
        if (open && abs(movement) > .025f) {
            val direction = if (movement > 0) 1 else -1
            swipeFrames = if (direction == swipeDirection) swipeFrames + 1 else 1
            swipeDirection = direction
        }
        if (abs(dy) >= .16f) { swipeFrames = 0; swipeDirection = 0 }
        val action = when {
            pinch && stableFrames >= 2 && now - since >= 250 -> HandGesture.PINCH
            open && swipeFrames >= 2 && abs(dx) > .14f && abs(dy) < .16f &&
                (if (dx > 0) 1 else -1) == swipeDirection && now - since in 80..1400 -> if (dx > 0) HandGesture.NEXT else HandGesture.PREVIOUS
            else -> null
        }
        if (open && (abs(dx) >= .07f || abs(dy) >= .07f) && now - since > 1400) {
            since = now; originX = center.x; originY = center.y; swipeFrames = 0; swipeDirection = 0
        }
        if (action != null) { latched = true; lastAction = now; lastGesture = action }
        return action
    }
}
