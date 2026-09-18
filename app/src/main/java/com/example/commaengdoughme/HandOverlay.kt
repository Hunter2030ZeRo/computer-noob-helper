package com.example.commaengdoughme

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Normalized rotated camera coordinates, not the aspect-corrected gesture coordinates. */
data class HandObservation(val points: List<ScreenPoint>, val capturedAtMs: Long,
    val latencyMs: Long, val hint: String, val gesture: HandGesture?) {
    fun fresh(now: Long) = now - capturedAtMs in 0..700
}

internal val handBones = listOf(listOf(0,1,2,3,4), listOf(0,5,6,7,8), listOf(5,9,10,11,12),
    listOf(9,13,14,15,16), listOf(13,17,18,19,20), listOf(0,17)).flatMap { it.zipWithNext() }

/** Live camera rays; never world-anchor a moving hand or clamp offscreen joints to an edge. */
@Composable
internal fun HandOverlay(observation: HandObservation?, calibration: SpatialCalibration, calibrated: Boolean) {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var recognized by remember { mutableStateOf("") }
    var recognizedAt by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.uptimeMillis(); delay(50) } }
    LaunchedEffect(observation) {
        if (observation?.fresh(SystemClock.uptimeMillis()) == true && observation.gesture != null) {
            recognized = when (observation.gesture) {
                HandGesture.PINCH -> "핀치 인식"
                HandGesture.NEXT -> "오른쪽 이동 인식"
                HandGesture.PREVIOUS -> "왼쪽 이동 인식"
            }
            recognizedAt = SystemClock.uptimeMillis()
        }
    }
    val current = observation?.takeIf { it.fresh(now) }
    val points = current?.points?.takeIf { it.size == 21 }?.map { SpatialMath.cameraPoint(it, calibration) }.orEmpty()
    val flash = recognized.isNotEmpty() && now - recognizedAt in 0..1200
    val color = if (flash) Color.White else Color(0xFF62FF9A)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusTop = maxHeight * .14f + 38.dp
        Canvas(Modifier.fillMaxSize()) {
            fun pixel(p: ScreenPoint) = Offset(p.x * size.width, p.y * size.height)
            if (points.size == 21) {
                handBones.forEach { (a,b) ->
                    val start = points[a]; val end = points[b]
                    if (start != null && end != null) drawLine(color, pixel(start), pixel(end), 1.5.dp.toPx())
                }
                points.filterNotNull().forEach { drawCircle(color, (if (flash) 4.dp else 2.5.dp).toPx(), pixel(it)) }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth(.76f).padding(top = statusTop),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when {
                current == null -> "손 관절 대기"
                current.points.size != 21 -> "손 미검출 · ${current.latencyMs}ms"
                current.latencyMs >= 450 -> "관절 21 · 처리 지연 ${current.latencyMs}ms"
                else -> "관절 21 · ${current.latencyMs}ms · ${current.hint}"
            }, color = color, fontSize = 11.sp)
            Text(if (flash) recognized else if (calibrated) "손 위치 근사 투영" else "손 위치 임시 보정 · 설정에서 조정",
                color = color, fontSize = 11.sp)
        }
    }
}
