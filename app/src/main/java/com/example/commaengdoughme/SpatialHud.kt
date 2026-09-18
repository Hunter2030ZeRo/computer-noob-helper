package com.example.commaengdoughme

import android.graphics.Matrix
import android.os.SystemClock
import kotlinx.coroutines.delay
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Optical see-through: black is intentionally unlit, with one monochrome-safe accent. */
private object HudStyle {
    val ink = Color(0xFFADFFCC)
    val secondary = ink.copy(alpha = .65f)
    val faint = ink.copy(alpha = .25f)
    val nativeInk = android.graphics.Color.rgb(173, 255, 204)
    val inset = 12.dp
}

@Composable
fun SpatialHud(
    tracker: HeadTracker, text: String, focus: List<SpatialAnchor>, calibration: SpatialCalibration,
    calibrated: Boolean, spatialEnabled: Boolean, cameraReady: Boolean, cameraRequested: Boolean,
    cameraStatus: String, agentBusy: Boolean, agentConfigured: Boolean, continuous: Boolean,
    canSpeak: Boolean, stepIndex: Int, stepCount: Int,
    onSpeak: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSettings: () -> Unit,
    onCameraToggle: () -> Unit, onShare: () -> Unit,
    gestureEvent: GestureEvent? = null, gestureStatus: String = "", recenterEpoch: Long = 0,
    onRecovered: () -> Unit = {},
    handObservation: HandObservation? = null, showHandOverlay: Boolean = false,
) {
    var pose by remember { mutableStateOf<HeadPose?>(null) }
    LaunchedEffect(tracker) {
        while (true) withFrameNanos { pose = tracker.latest() }
    }
    HudSurface(pose, text, focus, calibration, calibrated, spatialEnabled, cameraReady, cameraRequested,
        cameraStatus, agentBusy, agentConfigured, continuous, canSpeak, stepIndex, stepCount,
        onSpeak, onPrevious, onNext, onSettings, onCameraToggle, onShare, gestureEvent, gestureStatus, recenterEpoch, onRecovered, handObservation, showHandOverlay)
}

/** Sensor-free surface also used by small-viewport previews and instrumentation tests. */
@Composable
internal fun HudSurface(
    pose: HeadPose?, text: String, focus: List<SpatialAnchor>, calibration: SpatialCalibration,
    calibrated: Boolean, spatialEnabled: Boolean, cameraReady: Boolean, cameraRequested: Boolean,
    cameraStatus: String, agentBusy: Boolean, agentConfigured: Boolean, continuous: Boolean,
    canSpeak: Boolean, stepIndex: Int, stepCount: Int,
    onSpeak: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSettings: () -> Unit,
    onCameraToggle: () -> Unit, onShare: () -> Unit,
    gestureEvent: GestureEvent? = null, gestureStatus: String = "", recenterEpoch: Long = 0,
    onRecovered: () -> Unit = {},
    handObservation: HandObservation? = null, showHandOverlay: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    var menuSelection by remember { mutableIntStateOf(0) }
    var recoveryMessage by remember { mutableStateOf("") }
    BackHandler(enabled = menu) { menu = false }
    var anchor by remember(calibration) { mutableStateOf<SpatialAnchor?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val compact = maxHeight < 360.dp
        // Glass3 official central-content / reflection guidance, with additional side optical margin.
        val safeX = maxWidth * .12f
        val safeTop = maxHeight * .14f
        val safeBottom = maxHeight * .125f
        val insetPx = widthPx * .12f
        val paddingPx = with(density) { 10.dp.toPx() }
        val panelWidth = (widthPx - insetPx * 2).coerceAtLeast(1f)
        // Keep font readable; paginate content instead of shrinking an eight-line phone card.
        val fontPx = with(density) { (if (compact) 14.sp else 16.sp).toPx() }
        val paint = remember(fontPx) { TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HudStyle.nativeInk; textSize = fontPx
        } }
        val layoutWidth = (panelWidth - paddingPx * 2).toInt().coerceAtLeast(1)
        val fullLayout = remember(text, layoutWidth, fontPx) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(fontPx * .15f, 1f).build()
        }
        val linesPerPage = if (compact) 2 else 3
        val pages = remember(fullLayout, linesPerPage) {
            (0 until fullLayout.lineCount step linesPerPage).map { start ->
                text.substring(fullLayout.getLineStart(start), fullLayout.getLineEnd(minOf(start + linesPerPage - 1, fullLayout.lineCount - 1)))
            }.ifEmpty { listOf("") }
        }
        var page by remember(text, layoutWidth, linesPerPage) { mutableIntStateOf(0) }
        val pageText = pages[page.coerceAtMost(pages.lastIndex)]
        val pageLayout = remember(pageText, layoutWidth, fontPx) {
            StaticLayout.Builder.obtain(pageText, 0, pageText.length, paint, layoutWidth)
                .setIncludePad(false).setLineSpacing(fontPx * .15f, 1f).build()
        }
        val labelHeight = fontPx * 1.2f
        val panelHeight = labelHeight + fontPx * 1.35f * linesPerPage + paddingPx * 2
        val bottomReserve = heightPx * .125f + with(density) { 62.dp.toPx() }
        val bottom = ((heightPx - bottomReserve) / heightPx).coerceIn(.45f, .94f)
        val top = (bottom - panelHeight / heightPx).coerceAtLeast(.22f)
        val region = FocusRegion("문제 안내", (insetPx / widthPx).toDouble(), top.toDouble(),
            (1 - insetPx / widthPx).toDouble(), bottom.toDouble())
        LaunchedEffect(pose?.generation, calibration, widthPx, heightPx, fontPx, spatialEnabled, recenterEpoch) {
            if (pose != null && spatialEnabled && (anchor?.generation != pose.generation || anchor?.label != "$widthPx/$heightPx/$fontPx/$recenterEpoch")) {
                anchor = SpatialMath.anchor(region, pose, calibration, false).copy(label = "$widthPx/$heightPx/$fontPx/$recenterEpoch")
            }
        }
        val fallback = listOf(ScreenPoint(region.left.toFloat(), top), ScreenPoint(region.right.toFloat(), top),
            ScreenPoint(region.right.toFloat(), bottom), ScreenPoint(region.left.toFloat(), bottom))
        val projected = if (pose != null && spatialEnabled) anchor?.let { SpatialMath.project(it, pose, calibration) } else fallback
        LaunchedEffect(recoveryMessage) { if (recoveryMessage.isNotBlank()) { delay(2000); recoveryMessage = "" } }
        fun recenter() {
            anchor = pose?.let { SpatialMath.anchor(region, it, calibration, false).copy(label = "$widthPx/$heightPx/$fontPx/$recenterEpoch") }
            onRecovered()
            recoveryMessage = "안내 위치 재설정"
        }
        fun menuAction(index: Int) {
            when (index) {
                0 -> if (canSpeak) onSpeak()
                1 -> recenter()
                2 -> onCameraToggle()
                3 -> if (continuous || (cameraRequested && agentConfigured)) onShare()
                4 -> onSettings()
            }
            menu = false
        }
        var consumedGesture by remember { mutableLongStateOf(gestureEvent?.sequence ?: -1) }
        LaunchedEffect(recenterEpoch) { menu = false; menuSelection = 0 }
        LaunchedEffect(gestureEvent?.sequence) {
            val event = gestureEvent ?: return@LaunchedEffect
            if (event.sequence == consumedGesture || SystemClock.uptimeMillis() - event.atMs !in 0..700) return@LaunchedEffect
            consumedGesture = event.sequence
            when (event.gesture) {
                HandGesture.PINCH -> if (menu) menuAction(menuSelection) else { menuSelection = 0; menu = true }
                HandGesture.NEXT -> if (menu) menuSelection = (menuSelection + 1) % 6
                    else if (page < pages.lastIndex) page++ else if (stepIndex < stepCount - 1) onNext()
                HandGesture.PREVIOUS -> if (menu) menuSelection = (menuSelection + 5) % 6
                    else if (page > 0) page-- else if (stepIndex > 0) onPrevious()
            }
        }
        val trackedFocus = if (pose != null && calibrated && spatialEnabled) focus.mapNotNull {
            SpatialMath.project(it, pose, calibration)?.let { points -> it.label to points }
        } else emptyList()
        val panelLabel = when {
            agentBusy -> "에이전트 분석 중"
            stepCount > 0 -> "해결 안내  ${stepIndex + 1}/$stepCount"
            else -> "컴맹도우미"
        }
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "$panelLabel. $pageText" }) {
            val edge = insetPx
            val stroke = 1.dp.toPx()
            // Peripheral instrument rails leave the working area transparent and uncluttered.
            for (x in listOf(edge, size.width - edge)) {
                val sign = if (x == edge) 1 else -1
                drawLine(HudStyle.secondary, Offset(x, (size.height * .24f)), Offset(x, (size.height * .28f)), stroke)
                drawLine(HudStyle.secondary, Offset(x, (size.height * .24f)), Offset(x + sign * 24.dp.toPx(), (size.height * .24f)), stroke)
                for (tick in 0..4) {
                    val y = size.height * (.29f + tick * .035f)
                    drawLine(HudStyle.faint, Offset(x, y), Offset(x + sign * (if (tick == 2) 8 else 4).dp.toPx(), y), stroke)
                }
            }
            // Open aiming reference, no opaque central reticle or invented telemetry.
            val center = Offset(size.width / 2, size.height * .37f)
            val radius = 11.dp.toPx()
            drawArc(HudStyle.faint, 15f, 55f, false, center - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(stroke))
            drawArc(HudStyle.faint, 195f, 55f, false, center - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(stroke))
            trackedFocus.forEach { (label, points) ->
                val corners = points.map { Offset(it.x * size.width, it.y * size.height) }
                // Corner brackets track the projected quadrilateral, including head roll.
                corners.indices.forEach { index ->
                    val at = corners[index]
                    listOf(corners[(index + 1) % 4], corners[(index + 3) % 4]).forEach { other ->
                        drawLine(HudStyle.ink, at, at + (other - at) * .22f, 2.dp.toPx())
                    }
                }
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.translate(corners[0].x, corners[0].y)
                    native.rotate(Math.toDegrees(kotlin.math.atan2((corners[1] - corners[0]).y, (corners[1] - corners[0]).x).toDouble()).toFloat())
                    native.drawText(label.take(20), 0f, -5.dp.toPx(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = HudStyle.nativeInk; textSize = 13.sp.toPx()
                    })
                    native.restore()
                }
            }
            projected?.let { points ->
                val destination = points.flatMap { listOf(it.x * size.width, it.y * size.height) }.toFloatArray()
                val matrix = Matrix()
                // Source height matches real pixel height, so static text keeps its intended font size.
                val drawHeight = (bottom - top) * heightPx
                if (matrix.setPolyToPoly(floatArrayOf(0f, 0f, panelWidth, 0f, panelWidth, drawHeight, 0f, drawHeight), 0, destination, 0, 4)) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.concat(matrix)
                        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HudStyle.nativeInk; strokeWidth = stroke }
                        native.drawLine(0f, 0f, panelWidth * .25f, 0f, line)
                        native.drawLine(0f, 0f, 0f, drawHeight, line)
                        line.alpha = 70
                        native.drawLine(0f, drawHeight, panelWidth, drawHeight, line)
                        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HudStyle.nativeInk; textSize = fontPx * .65f }
                        native.drawText(panelLabel, paddingPx, paddingPx + caption.textSize, caption)
                        native.translate(paddingPx, paddingPx + labelHeight)
                        pageLayout.draw(native)
                        native.restore()
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = safeX, vertical = safeTop),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (cameraReady) "시야 ●" else "시야 ○", color = HudStyle.ink, fontSize = 12.sp)
                Text(when { agentBusy -> "분석 중"; continuous -> "시야 공유 중"; agentConfigured -> "요청 대기"; else -> "에이전트 미연결" },
                    color = HudStyle.secondary, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (gestureStatus.contains("실패") || gestureStatus.contains("미지원")) "손 인식 오류" else gestureStatus, color = HudStyle.ink, fontSize = 11.sp, maxLines = 1)
                Text(if (spatialEnabled && pose != null) "3DoF" else "화면 고정", color = HudStyle.secondary, fontSize = 12.sp)
            }
        }
        if (!cameraReady || projected == null) {
            Text(if (!cameraReady) cameraStatus else "안내가 시야 밖에 있습니다 · UI 리셋을 누르세요",
                Modifier.align(Alignment.TopCenter).padding(top = safeTop + 46.dp, start = safeX, end = safeX),
                color = HudStyle.secondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (pages.size > 1) Text("${page + 1} / ${pages.size}",
            Modifier.align(Alignment.BottomEnd).padding(end = safeX, bottom = safeBottom + 59.dp),
            color = HudStyle.secondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = safeX, end = safeX, bottom = safeBottom),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pages.size > 1 || stepCount > 1) {
                HudAction("‹", Modifier.weight(.55f), enabled = page > 0 || stepIndex > 0, description = "이전 안내") {
                    if (page > 0) page-- else onPrevious()
                }
            }
            HudAction(if (agentBusy) "분석 중" else "말하기", Modifier.weight(1f), enabled = canSpeak, onClick = onSpeak)
            HudAction("↺", Modifier.weight(.55f), description = "UI 리셋", onClick = { recenter(); menu = false })
            HudAction("메뉴", Modifier.weight(1f), onClick = { menu = !menu })
            if (pages.size > 1 || stepCount > 1) {
                HudAction("›", Modifier.weight(.55f), enabled = page < pages.lastIndex || stepIndex < stepCount - 1, description = "다음 안내") {
                    if (page < pages.lastIndex) page++ else onNext()
                }
            }
        }
        if (recoveryMessage.isNotBlank()) Text(recoveryMessage,
            Modifier.align(Alignment.Center).padding(horizontal = safeX), color = HudStyle.ink, fontSize = 12.sp)
        if (menu) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth(.76f).fillMaxHeight(.68f)
                .background(Color.Black).border(1.dp, HudStyle.faint, CutCornerShape(8.dp))
                .padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("손 이동: 항목 · 핀치: 선택", color = HudStyle.ink, fontSize = 11.sp)
                val labels = listOf("말하기", "UI 리셋", if (cameraRequested) "시야 일시 정지" else "카메라 다시 연결",
                    if (continuous) "시야 공유 중지" else "시야 연속 공유", "연결 · 화면 설정", "닫기")
                // Three items at a time so the gesture-selected action never scrolls out of sight.
                val first = (menuSelection / 3) * 3
                labels.subList(first, minOf(first + 3, labels.size)).forEachIndexed { offset, label ->
                    val index = first + offset
                    HudAction(label, Modifier.fillMaxWidth(), selected = index == menuSelection,
                        enabled = when (index) { 0 -> canSpeak;
                            3 -> continuous || (cameraRequested && agentConfigured); else -> true }) { menuAction(index) }
                }
                Row {
                    HudAction("‹", Modifier.weight(1f), description = "이전 메뉴") { menuSelection = (menuSelection + 5) % 6 }
                    HudAction("›", Modifier.weight(1f), description = "다음 메뉴") { menuSelection = (menuSelection + 1) % 6 }
                }
                Text("${menuSelection + 1}/6 · ${if (calibrated) "보정 적용" else "영역 보정 필요"}", color = HudStyle.secondary, fontSize = 10.sp)
            }
        }
        if (showHandOverlay) HandOverlay(handObservation, calibration, calibrated)

    }
}

@Composable
private fun HudAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                      description: String = label, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = if (enabled) HudStyle.ink else HudStyle.faint
    Box(modifier.heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
        .background(if (focused || selected) HudStyle.faint else Color.Black, CutCornerShape(6.dp))
        .border(1.dp, if (focused || selected) HudStyle.ink else color.copy(alpha = .4f), CutCornerShape(6.dp))
        .semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = color, fontSize = 12.sp, maxLines = 1)
    }
}

@Preview(name = "Compact glasses · 320×426", widthDp = 320, heightDp = 426, backgroundColor = 0xFF000000, showBackground = true)
@Preview(name = "Small landscape · 426×280", widthDp = 426, heightDp = 280, backgroundColor = 0xFF000000, showBackground = true)
@Composable
internal fun HudPreview() {
    val pose = HeadPose(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), 1, 1)
    val calibration = SpatialCalibration()
    HudSurface(pose, "오류 메시지를 확인했습니다. 네트워크 연결 상태부터 살펴볼게요.", emptyList(), calibration,
        calibrated = true, spatialEnabled = false, cameraReady = true, cameraRequested = true,
        cameraStatus = "시야 인식 중", agentBusy = false, agentConfigured = true, continuous = false,
        canSpeak = true, stepIndex = 0, stepCount = 3,
        onSpeak = {}, onPrevious = {}, onNext = {}, onSettings = {}, onCameraToggle = {}, onShare = {})
}
