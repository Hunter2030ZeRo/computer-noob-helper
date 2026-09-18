package com.example.commaengdoughme

import android.os.SystemClock
import kotlinx.coroutines.delay
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    handObservation: HandObservation? = null, showHandOverlay: Boolean = false, mvp: Boolean = false,
) {
    var pose by remember { mutableStateOf<HeadPose?>(null) }
    LaunchedEffect(tracker, spatialEnabled) {
        if (!spatialEnabled) { pose = null; return@LaunchedEffect }
        while (true) withFrameNanos { pose = tracker.latest() }
    }
    HudSurface(pose, text, focus, calibration, calibrated, spatialEnabled, cameraReady, cameraRequested,
        cameraStatus, agentBusy, agentConfigured, continuous, canSpeak, stepIndex, stepCount,
        onSpeak, onPrevious, onNext, onSettings, onCameraToggle, onShare, gestureEvent, gestureStatus, recenterEpoch, onRecovered, handObservation, showHandOverlay, mvp)
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
    handObservation: HandObservation? = null, showHandOverlay: Boolean = false, mvp: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    var menuSelection by remember { mutableIntStateOf(0) }
    var recoveryMessage by remember { mutableStateOf("") }
    val menuButtonFocus = remember { FocusRequester() }
    val menuCount = if (mvp) 4 else 6
    val itemFocus = remember { List(6) { FocusRequester() } }
    var menuWasOpened by remember { mutableStateOf(false) }
    LaunchedEffect(menu) {
        if (menu) menuWasOpened = true
        else if (menuWasOpened) menuButtonFocus.requestFocus()
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val compact = maxHeight < 360.dp
        val menuWidth = (maxWidth * .68f).coerceAtMost(340.dp)
        val menuHeight = maxHeight * .78f
        // Glass3 official central-content / reflection guidance, with additional side optical margin.
        val safeX = maxWidth * .18f
        val safeTop = maxHeight * (if (compact) .13f else .22f)
        val safeBottom = maxHeight * (if (compact) .13f else .22f)
        val insetPx = widthPx * .18f
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
        val bottomReserve = with(density) { safeBottom.toPx() + 56.dp.toPx() }
        val bottom = ((heightPx - bottomReserve) / heightPx).coerceIn(.45f, .94f)
        val top = (bottom - panelHeight / heightPx).coerceAtLeast(.22f)
        LaunchedEffect(recoveryMessage) { if (recoveryMessage.isNotBlank()) { delay(2000); recoveryMessage = "" } }
        fun recenter() {
            onRecovered()
            page = 0
            recoveryMessage = "HUD 초기화"
        }
        fun menuEnabled(index: Int) = if (mvp) when (index) {
            1 -> continuous || (cameraRequested && agentConfigured)
            else -> true
        } else when (index) {
            0 -> canSpeak
            3 -> continuous || (cameraRequested && agentConfigured)
            else -> true
        }
        fun moveMenu(direction: Int) {
            for (offset in 1..menuCount) {
                val candidate = (menuSelection + direction * offset + menuCount) % menuCount
                if (menuEnabled(candidate)) { menuSelection = candidate; return }
            }
        }
        fun openMenu() { menuSelection = if (mvp || canSpeak) 0 else 1; menu = true }
        fun menuAction(index: Int) {
            if (!menuEnabled(index)) return
            if (mvp) {
                when (index) { 0 -> onCameraToggle(); 1 -> onShare(); 2 -> onSettings() }
                menu = false
                return
            }
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
                HandGesture.PINCH -> if (menu) menuAction(menuSelection) else openMenu()
                HandGesture.NEXT -> if (menu) moveMenu(1)
                    else if (page < pages.lastIndex) page++ else if (stepIndex < stepCount - 1) onNext()
                HandGesture.PREVIOUS -> if (menu) moveMenu(-1)
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
            val center = Offset(size.width / 2, top * size.height - 12.dp.toPx())
            val radius = 6.dp.toPx()
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
            // The advice card belongs to the display, never to an IMU/world anchor.
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(insetPx, top * heightPx)
                val drawHeight = (bottom - top) * heightPx
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HudStyle.nativeInk; strokeWidth = stroke }
                native.drawLine(0f, 0f, panelWidth * .25f, 0f, line)
                native.drawLine(0f, 0f, 0f, 10.dp.toPx(), line)
                native.drawLine(panelWidth, drawHeight, panelWidth - 20.dp.toPx(), drawHeight, line)
                native.drawLine(panelWidth, drawHeight, panelWidth, drawHeight - 10.dp.toPx(), line)
                val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HudStyle.nativeInk; textSize = fontPx * .65f }
                native.drawText(panelLabel, paddingPx, paddingPx + caption.textSize, caption)
                native.translate(paddingPx, paddingPx + labelHeight)
                pageLayout.draw(native)
                native.restore()
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
                Text(if (mvp) (if (continuous) "공유 중" else "공유 꺼짐") else if (gestureStatus.contains("실패") || gestureStatus.contains("미지원")) "손 인식 오류" else gestureStatus, color = HudStyle.ink, fontSize = 11.sp, maxLines = 1)
                Text("HUD", color = HudStyle.secondary, fontSize = 12.sp)
            }
        }
        if (!cameraReady) {
            Text(cameraStatus,
                Modifier.align(Alignment.TopCenter).padding(top = safeTop + 46.dp, start = safeX, end = safeX),
                color = HudStyle.secondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (pages.size > 1) Text("${page + 1} / ${pages.size}",
            Modifier.align(Alignment.BottomEnd).padding(end = safeX, bottom = safeBottom + 59.dp),
            color = HudStyle.secondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = safeX, end = safeX, bottom = safeBottom),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pages.size > 1 || stepCount > 1) {
                HudAction("‹", Modifier.weight(.55f), enabled = !menu && (page > 0 || stepIndex > 0), description = "이전 안내") {
                    if (page > 0) page-- else onPrevious()
                }
            }
            HudAction(if (agentBusy) "분석 중" else if (mvp) "시야 보내기" else "말하기", Modifier.weight(1f), enabled = !menu && canSpeak, onClick = onSpeak)
            if (!mvp) HudAction("↺", Modifier.weight(.55f), description = "UI 리셋", enabled = !menu, onClick = { recenter(); menu = false })
            HudAction("메뉴", Modifier.weight(1f).focusRequester(menuButtonFocus), enabled = !menu, onClick = { openMenu() })
            if (pages.size > 1 || stepCount > 1) {
                HudAction("›", Modifier.weight(.55f), enabled = !menu && (page < pages.lastIndex || stepIndex < stepCount - 1), description = "다음 안내") {
                    if (page < pages.lastIndex) page++ else onNext()
                }
            }
        }
        if (recoveryMessage.isNotBlank()) Text(recoveryMessage,
            Modifier.align(Alignment.Center).padding(horizontal = safeX), color = HudStyle.ink, fontSize = 12.sp)
        if (menu) {
            Dialog(onDismissRequest = { menu = false }, properties = DialogProperties(
                dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
            LaunchedEffect(menuSelection) { itemFocus[menuSelection].requestFocus() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.width(menuWidth).heightIn(max = menuHeight)
                .background(Color.Black).border(1.dp, HudStyle.faint, CutCornerShape(8.dp))
                .padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (mvp) "시야 공유 설정" else "손날 넘기기: 항목 · 핀치: 선택", color = HudStyle.ink, fontSize = 11.sp)
                val labels = if (mvp) listOf(if (cameraRequested) "카메라 일시 정지" else "카메라 다시 연결",
                    if (continuous) "시야 공유 중지" else "시야 공유 시작", "Provider 설정", "닫기") else listOf("말하기", "UI 리셋", if (cameraRequested) "시야 일시 정지" else "카메라 다시 연결",
                    if (continuous) "시야 공유 중지" else "시야 연속 공유", "연결 · 화면 설정", "닫기")
                // Three items at a time so the gesture-selected action never scrolls out of sight.
                val first = (menuSelection / 3) * 3
                labels.subList(first, minOf(first + 3, labels.size)).forEachIndexed { offset, label ->
                    val index = first + offset
                    HudAction(label, Modifier.fillMaxWidth().focusRequester(itemFocus[index]), selected = index == menuSelection,
                        onFocused = { menuSelection = index },
                        enabled = menuEnabled(index)) { menuAction(index) }
                }
                Row {
                    HudAction("‹", Modifier.weight(1f), description = "이전 메뉴") { moveMenu(-1) }
                    HudAction("›", Modifier.weight(1f), description = "다음 메뉴") { moveMenu(1) }
                }
                Text("${menuSelection + 1}/$menuCount · 뒤로: 메뉴 닫기", color = HudStyle.secondary, fontSize = 10.sp)
            }
            }
            }
        }
        if (showHandOverlay && !menu) HandOverlay(handObservation, calibration, calibrated)

    }
}

@Composable
private fun HudAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                      description: String = label, selected: Boolean = false, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = if (enabled) HudStyle.ink else HudStyle.faint
    Box(modifier.heightIn(min = 48.dp).focusProperties { canFocus = enabled }.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .background(if (focused || selected) HudStyle.faint else Color.Black, CutCornerShape(6.dp))
        .border(1.dp, if (focused || selected) HudStyle.ink else color.copy(alpha = .4f), CutCornerShape(6.dp))
        .semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
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
