package com.example.commaengdoughme

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.SystemClock
import android.content.Intent
import android.speech.RecognizerIntent
import android.content.pm.PackageManager
import android.os.Bundle
import android.hardware.camera2.CameraCharacteristics
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.example.commaengdoughme.ui.theme.컴맹도우미Theme
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import org.json.JSONObject
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { 컴맹도우미Theme(darkTheme = true, dynamicColor = false) { VisionScreen(this) } }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun VisionScreen(activity: ComponentActivity) {
    val mainExecutor = remember { ContextCompat.getMainExecutor(activity) }
    val network = remember { Executors.newSingleThreadExecutor() }
    var disposed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("시야 연결 중") }
    var result by remember { mutableStateOf<VisionResult?>(null) }
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var includeImage by remember { mutableStateOf(true) }
    var threshold by remember { mutableStateOf(false) }
    var interval by remember { mutableStateOf("1500") }
    var cameraConnected by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("지금 보고 있는 컴퓨터 문제를 해결해 주세요") }
    var allowPc by remember { mutableStateOf(false) }
    var continuous by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf(true) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var pendingAddress by remember { mutableStateOf("") }
    var pendingToken by remember { mutableStateOf("") }
    var lastSent by remember { mutableStateOf("") }
    var lastSendTime by remember { mutableLongStateOf(0L) }
    var steps by remember { mutableStateOf(listOf<String>()) }
    var stepIndex by remember { mutableIntStateOf(0) }
    val tracker = remember { HeadTracker(activity) { activity.window.decorView.display?.rotation ?: Surface.ROTATION_0 } }
    val diagnostics = remember { TrackingDiagnostics(activity.applicationContext) }
    var diagnosticStatus by remember { mutableStateOf(diagnostics.status()) }
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            exporting = true
            network.execute {
                val message = runCatching {
                    activity.contentResolver.openOutputStream(uri)?.use { diagnostics.export(it) }
                        ?: error("출력 파일을 열 수 없습니다")
                    "진단 ZIP 저장 완료"
                }.getOrElse { "진단 저장 실패: ${it.javaClass.simpleName}" }
                mainExecutor.execute { if (!disposed) { exporting = false; exportStatus = message } }
            }
        }
    }
    LaunchedEffect(diagnostics) {
        while (true) { diagnosticStatus = diagnostics.status(); delay(500) }
    }
    var spatial by remember { mutableStateOf(true) }
    var calibrated by remember { mutableStateOf(false) }
    var calibration by remember { mutableStateOf(SpatialCalibration()) }
    var calibrationFields by remember { mutableStateOf(listOf("30", "22", "60", "45", "0", "0")) }
    var calibrationError by remember { mutableStateOf("") }
    var pendingPose by remember { mutableStateOf<HeadPose?>(null) }
    var pendingCalibration by remember { mutableStateOf(SpatialCalibration()) }
    var focus by remember { mutableStateOf(listOf<SpatialAnchor>()) }
    var gesturesEnabled by remember { mutableStateOf(true) }
    var handObservation by remember { mutableStateOf<HandObservation?>(null) }
    var showHandOverlay by remember { mutableStateOf(true) }
    var handPreview by remember { mutableStateOf(false) }
    var handPreviewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var handPreviewDetail by remember { mutableStateOf("카메라 영상 대기") }
    val previewAllowed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var gestureStatus by remember { mutableStateOf("손 대기") }
    var gestureEvent by remember { mutableStateOf<GestureEvent?>(null) }
    var gestureSequence by remember { mutableLongStateOf(0) }
    var recenterEpoch by remember { mutableLongStateOf(0) }
    var wearing by remember { mutableStateOf(true) }
    var foreground by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val handInputAllowed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val handInputSession = remember { java.util.concurrent.atomic.AtomicLong(0) }
    DisposableEffect(hud, gesturesEnabled, wearing, foreground, handPreview) {
        handObservation = null
        handInputSession.incrementAndGet()
        handInputAllowed.set((hud && gesturesEnabled || !hud && handPreview) && wearing && foreground)
        previewAllowed.set(!hud && handPreview && wearing && foreground)
        onDispose { handInputAllowed.set(false); previewAllowed.set(false); handInputSession.incrementAndGet() }
    }
    DisposableEffect(activity) {
        var registered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = when (intent?.action) {
                    "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED" -> intent.getStringExtra("glasses_take_state")
                    "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED" -> intent.getStringExtra("glasses_leg_state")
                    else -> null
                }
                when (state) {
                    "0" -> { handInputAllowed.set(false); handInputSession.incrementAndGet(); gestureEvent = null; wearing = false; continuous = false; focus = emptyList(); pendingPose = null }
                    "1" -> { handInputSession.incrementAndGet(); gestureEvent = null; wearing = true; recenterEpoch++; focus = emptyList(); pendingPose = null }
                }
            }
        }
        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(activity, receiver, IntentFilter().apply {
                    addAction("com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED")
                    addAction("com.rokid.sprite.ACTION_LEG_STATUS_CHANGED")
                }, ContextCompat.RECEIVER_EXPORTED)
                registered = true
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) register()
            if (event == Lifecycle.Event.ON_STOP && registered) { activity.unregisterReceiver(receiver); registered = false }
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()
        onDispose { activity.lifecycle.removeObserver(observer); if (registered) activity.unregisterReceiver(receiver) }
    }
    DisposableEffect(hud, spatial) {
        val view = activity.window.decorView
        val insets = WindowCompat.getInsetsController(activity.window, view)
        val previousBehavior = insets.systemBarsBehavior
        val keptAwake = view.keepScreenOn
        if (hud) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = true
        }
        onDispose {
            if (hud) {
                insets.show(WindowInsetsCompat.Type.systemBars())
                insets.systemBarsBehavior = previousBehavior
                view.keepScreenOn = keptAwake
            }
        }
    }
    DisposableEffect(activity, tracker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { tracker.start(); foreground = true; recenterEpoch++ }
                Lifecycle.Event.ON_PAUSE -> { diagnostics.stop("앱 일시 정지"); tracker.stop(); foreground = false; handInputAllowed.set(false) }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) tracker.start()
        onDispose { activity.lifecycle.removeObserver(observer); tracker.stop(); diagnostics.stop("앱 종료") }
    }

    fun acceptReply(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val state = json?.optString("status")
        if (state == "queued" || state == "running") {
            val id = json.optString("request_id")
            if (!id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) {
                pendingId = null
                continuous = false
                response = "서버의 요청 ID가 올바르지 않습니다"
                return
            }
            pendingId = id
            response = "에이전트가 분석 중입니다…"
        } else {
            pendingId = null
            response = json?.optString("advice", raw) ?: raw
            val array = json?.optJSONArray("steps")
            steps = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }
            stepIndex = 0
            val capturedPose = pendingPose
            val regions = json?.optJSONArray("focus_regions")
            focus = if (state == "completed" && calibrated && pendingCalibration == calibration && capturedPose != null && regions != null) {
                (0 until minOf(regions.length(), 4)).mapNotNull { index ->
                    val item = regions.optJSONObject(index) ?: return@mapNotNull null
                    val box = item.optJSONArray("box") ?: return@mapNotNull null
                    if (box.length() != 4) return@mapNotNull null
                    val region = FocusRegion(item.optString("label").take(80), box.optDouble(0), box.optDouble(1), box.optDouble(2), box.optDouble(3))
                    if (region.valid()) SpatialMath.anchor(region, capturedPose, pendingCalibration, true) else null
                }
            } else emptyList()
            if (state == "failed") continuous = false
        }
    }
    fun submit(spoken: String? = null) {
        val frame = result
        if (frame == null && spoken == null) return
        val snapshot = frame?.copy(id = java.util.UUID.randomUUID().toString()) ?: VisionResult(
            java.util.UUID.randomUUID().toString(), "", System.currentTimeMillis(), 0, 0, byteArrayOf()
        )
        if (sending || pendingId != null) return
        val destination = address
        val credential = token
        val attach = includeImage && frame != null
        val task = spoken ?: goal
        val pc = allowPc
        try {
            AgentClient.endpoint(destination)
            sending = true
            lastSent = frame?.id.orEmpty()
            lastSendTime = System.currentTimeMillis()
            pendingAddress = destination
            pendingToken = credential
            steps = emptyList()
            focus = emptyList()
            pendingPose = if (attach && calibrated) snapshot.headPose else null
            pendingCalibration = calibration
            response = "전송 중"
            network.execute {
                val reply = runCatching { AgentClient.send(destination, credential, snapshot, attach, task, pc) }
                mainExecutor.execute {
                    if (!disposed) {
                        sending = false
                        reply.onSuccess { acceptReply(it) }.onFailure {
                            continuous = false
                            // Retry the same snapshot ID: the server deduplicates accepted jobs.
                            pendingId = snapshot.id
                            response = "접수 확인 실패. 결과 조회를 시도합니다."
                        }
                    }
                }
            }
        } catch (e: Exception) { continuous = false; response = e.message ?: "주소를 확인하세요" }
    }
    LaunchedEffect(pendingId) {
        val id = pendingId ?: return@LaunchedEffect
        while (pendingId == id) {
            delay(2000)
            if (sending || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
            sending = true
            val destination = pendingAddress
            val credential = pendingToken
            network.execute {
                val reply = runCatching { AgentClient.poll(destination, credential, id) }
                mainExecutor.execute {
                    if (!disposed) {
                        sending = false
                        reply.onSuccess { if (pendingId == id) acceptReply(it) }.onFailure {
                            continuous = false
                            response = "결과 조회 실패. 연결을 확인하세요. 작업은 PC에서 계속될 수 있습니다."
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(continuous, running) {
        while (continuous && running) {
            delay(1000)
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                result?.id != lastSent && System.currentTimeMillis() - lastSendTime >= 10000) submit()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            status = "시야 연결 중"
            running = true
        } else status = "카메라 권한이 필요합니다 · 메뉴에서 다시 연결하세요"
    }
    fun connectCamera() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            status = "시야 연결 중"
            running = true
        } else permission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { connectCamera() }
    BackHandler(enabled = !hud) { hud = true }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { reply ->
        if (reply.resultCode == Activity.RESULT_OK) {
            val spoken = reply.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                goal = spoken
                submit(spoken)
                hud = true
            } else {
                steps = emptyList()
                response = "음성을 인식하지 못했습니다. 다시 말씀해 주세요."
            }
        }
    }
    fun listen() {
        // Keep background submissions from overtaking the spoken request.
        continuous = false
        try {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "궁금한 점이나 원하는 작업을 자유롭게 말씀해 주세요")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: android.content.ActivityNotFoundException) {
            steps = emptyList()
            response = "음성 인식 서비스가 없습니다. 기기에 음성 인식 앱을 설치하거나 버튼을 사용하세요."
        } catch (_: SecurityException) {
            response = "음성 인식 서비스의 마이크 권한을 확인하세요."
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            network.shutdown() // Already submitted sends finish with bounded network timeouts.
        }
    }
    DisposableEffect(running) {
        var cancelled = false
        var provider: ProcessCameraProvider? = null
        var analyzer: VisionAnalyzer? = null
        var analysis: ImageAnalysis? = null
        if (running) {
            val future = ProcessCameraProvider.getInstance(activity)
            future.addListener({
                if (!cancelled) {
                    try {
                        val cameras = future.get()
                        provider = cameras
                        // Prefer outward-facing cameras; if binding fails, try the next available camera.
                        val infos = cameras.availableCameraInfos.sortedBy {
                            when (Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)) {
                                CameraCharacteristics.LENS_FACING_BACK -> 0
                                CameraCharacteristics.LENS_FACING_EXTERNAL -> 1
                                CameraCharacteristics.LENS_FACING_FRONT -> 3
                                else -> 2
                            }
                        }
                        check(infos.isNotEmpty()) { "사용 가능한 카메라가 없습니다" }
                        var lastError: Exception? = null
                        for (info in infos) {
                            val cameraInfo = Camera2CameraInfo.from(info)
                            val selected = cameraInfo.cameraId
                            val selector = CameraSelector.Builder().addCameraFilter { candidates ->
                                candidates.filter { Camera2CameraInfo.from(it).cameraId == selected }
                            }.build()
                            val rotation = activity.window.decorView.display?.rotation ?: Surface.ROTATION_0
                            val realtime = cameraInfo.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                            val forwardCamera = cameraInfo.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT
                            val handTracker = HandTracker(activity.applicationContext, mainExecutor,
                                enabled = { handInputAllowed.get() }, session = { handInputSession.get() },
                                onGesture = { gesture -> if (hud) gestureEvent = GestureEvent(++gestureSequence, gesture, SystemClock.uptimeMillis()) },
                                onStatus = { gestureStatus = it },
                                previewEnabled = { previewAllowed.get() },
                                onPreview = { bitmap, detail -> handPreviewBitmap = bitmap; handPreviewDetail = detail },
                                onObservation = { handObservation = it })
                            val worker = VisionAnalyzer(threshold, interval.toLongOrNull()?.coerceIn(250L, 60000L) ?: 1500L, mainExecutor,
                                onResult = { result = it; status = "시야 인식 중"; cameraConnected = true },
                                onError = { status = it; cameraConnected = false },
                                poseAt = { timestamp -> if (realtime && forwardCamera && tracker.displayMatches(rotation)) tracker.at(timestamp) else null },
                                hands = handTracker,
                                diagnostics = diagnostics,
                            )
                            val useCase = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .setTargetRotation(rotation)
                                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                                    ResolutionStrategy(android.util.Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                                ).build()).build()
                            useCase.setAnalyzer(worker.executor, worker)
                            try {
                                // Analysis works without any Preview surface: optical HUD keeps the real view clear.
                                cameras.bindToLifecycle(activity, selector, useCase)
                                diagnostics.cameraInfo(selected, realtime)
                                analyzer = worker
                                analysis = useCase
                                status = "시야 연결됨 · 첫 프레임 대기"
                                break
                            } catch (e: Exception) {
                                useCase.clearAnalyzer()
                                cameras.unbind(useCase)
                                worker.close()
                                lastError = e
                            }
                        }
                        check(analysis != null) { lastError?.message ?: "카메라 연결 실패" }
                    } catch (_: Exception) {
                        status = "카메라 연결 실패 · 메뉴에서 다시 연결하세요"
                        cameraConnected = false
                        running = false
                    }
                }
            }, mainExecutor)
        }
        onDispose {
            cancelled = true
            diagnostics.stop("카메라 중지")
            cameraConnected = false
            analysis?.clearAnalyzer()
            analysis?.let { provider?.unbind(it) }
            analyzer?.close()
        }
    }
    if (hud) {
        SpatialHud(
            tracker = tracker,
            text = if (steps.isEmpty()) response.ifBlank {
                if (address.isBlank()) "메뉴에서 에이전트를 연결하세요.\n시야는 기기 안에서 인식합니다."
                else "보고 있는 문제를 말씀해 주세요."
            } else steps[stepIndex],
            focus = focus, calibration = calibration, calibrated = calibrated, spatialEnabled = spatial,
            cameraReady = cameraConnected, cameraRequested = running, cameraStatus = status,
            agentBusy = sending || pendingId != null, agentConfigured = address.isNotBlank(),
            canSpeak = !sending && pendingId == null, stepIndex = stepIndex, stepCount = steps.size,
            continuous = continuous,
            gestureEvent = gestureEvent, gestureStatus = if (gesturesEnabled) gestureStatus else "손 조작 꺼짐",
            recenterEpoch = recenterEpoch,
            handObservation = handObservation,
            showHandOverlay = showHandOverlay && gesturesEnabled && wearing && foreground && running,
            onRecovered = { focus = emptyList(); pendingPose = null },
            onSpeak = { listen() }, onPrevious = { if (stepIndex > 0) stepIndex-- },
            onNext = { if (stepIndex < steps.lastIndex) stepIndex++ },
            onSettings = { hud = false },
            onCameraToggle = {
                if (running) { continuous = false; running = false; status = "시야 일시 정지" }
                else connectCamera()
            },
            onShare = { if (continuous) continuous = false else if (running && address.isNotBlank()) continuous = true },
        )
        return
    }
    val settingsInsetX = LocalConfiguration.current.screenWidthDp.dp * .10f
    val settingsInsetY = LocalConfiguration.current.screenHeightDp.dp * .08f
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = settingsInsetX, vertical = settingsInsetY).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { hud = true }) { Text("‹ HUD로 돌아가기") }
            Text("에이전트 연결", style = MaterialTheme.typography.titleLarge)
            Row { Checkbox(gesturesEnabled, { gesturesEnabled = it }); Text("손 제스처 조작") }
            Text("핀치 0.35초 유지: 메뉴/선택 · 펼친 손 좌우 이동: 이동 · 리셋 버튼: 정면 재설정")
            Row { Checkbox(showHandOverlay, { showHandOverlay = it }); Text("HUD 손 관절 오버레이") }
            Text("녹색 관절: 손 검출 · 흰색 점멸: 제스처 판정. 실제 손과의 정렬은 고급 설정의 카메라/화면 시야각과 오프셋을 조정하세요. 가까운 손은 시차가 남습니다.")
            Row { Checkbox(handPreview, { handPreview = it; if (!it) handPreviewBitmap = null }); Text("손 인식 진단 화면") }
            if (handPreview) {
                Text("실제 추론 영상·관절을 표시합니다. 이 화면에서는 손 동작으로 명령을 실행하지 않습니다.")
                Text("$gestureStatus · $handPreviewDetail")
                handPreviewBitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), "손 인식 카메라와 관절", Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
            }
            Text("카메라는 자동으로 연결됩니다. 이 화면은 최초 연결 및 보정용입니다.")
            OutlinedTextField(address, { address = it }, label = { Text("에이전트 HTTPS URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Bearer 토큰 (선택 · 저장하지 않음)") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row { Checkbox(includeImage, { includeImage = it }); Text("OCR에 사용한 흑백 이미지도 전송") }
            OutlinedTextField(goal, { goal = it }, label = { Text("해결할 문제 / 요청") },
                modifier = Modifier.fillMaxWidth())
            Row { Checkbox(allowPc, { allowPc = it }); Text("PC 도구 허용 (서버에서 허용한 작업만)") }
            Row { Checkbox(continuous, { continuous = it }, enabled = running); Text("시야 연속 전송 (최소 10초 간격)") }
            Text("말하기를 마치면 인식된 문장과 마지막 시야가 모델에 바로 전송됩니다. 시야가 없으면 음성 인식 문장만 전송합니다.")
            Button(enabled = result != null && !sending && pendingId == null, onClick = { submit() }) {
                Text(if (sending || pendingId != null) "에이전트 처리 중…" else "현재 시야로 도움 요청")
            }
            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "고급 설정 닫기" else "고급 설정 · 화면 보정") }
            if (showAdvanced) {
                Text(status)
                Text(gestureStatus)
                Text(tracker.trackingMode)
                Text(diagnosticStatus)
                Text("진단은 15초 동안 카메라 영상과 원시 IMU를 기기에 기록합니다. 기록 중 OCR·손 인식·연속 전송은 쉽니다. VIO 보정용 완성 데이터가 아닙니다.")
                Button(enabled = running && !exporting, onClick = {
                    if (diagnostics.recording) diagnostics.stop() else {
                        continuous = false; result = null; focus = emptyList(); pendingPose = null
                        diagnostics.start()
                    }
                    diagnosticStatus = diagnostics.status()
                }) { Text(if (diagnostics.recording) "진단 기록 중지" else "15초 입력 진단") }
                Button(enabled = !diagnostics.recording && diagnostics.hasData() && !exporting, onClick = {
                    try { exportDiagnostics.launch("rv101-tracking-${System.currentTimeMillis()}.zip") }
                    catch (_: android.content.ActivityNotFoundException) { exportStatus = "이 기기에 파일 저장 앱이 없습니다" }
                }) { Text(if (exporting) "저장 중…" else "진단 ZIP 저장") }
                if (exportStatus.isNotBlank()) Text(exportStatus)
                OutlinedTextField(interval, { interval = it }, label = { Text("인식 간격 (250–60000 ms)") },
                    enabled = !running, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row { Checkbox(threshold, { threshold = it }, enabled = !running); Text("문서 이진화") }
                TextButton(onClick = { if (running) { running = false; continuous = false } else connectCamera() }) {
                    Text(if (running) "카메라 일시 정지" else "카메라 자동 연결")
                }
                Row { Checkbox(spatial, { spatial = it }); Text("앱 내 3DoF 안내 화면") }
                Text("센서: ${tracker.sensorName} · 회전만 보정합니다. 이동/물체 추적은 지원하지 않습니다.")
                Text("시야각 보정 (기본값은 예시이며 RV101 실측값이 아닙니다)")
                val calibrationLabels = listOf("디스플레이 가로 FOV", "디스플레이 세로 FOV", "회전 보정된 카메라 가로 FOV", "회전 보정된 카메라 세로 FOV", "카메라 yaw 오프셋", "카메라 pitch 오프셋")
                calibrationLabels.forEachIndexed { index, label ->
                    OutlinedTextField(calibrationFields[index], { value ->
                        calibrationFields = calibrationFields.toMutableList().also { it[index] = value }
                        calibrated = false
                        focus = emptyList()
                    }, label = { Text("$label (도)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = {
                    val values = calibrationFields.map { it.toDoubleOrNull() }
                    val candidate = if (values.all { it != null }) SpatialCalibration(values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!, values[5]!!) else null
                    if (candidate != null && candidate.valid()) {
                        calibration = candidate
                        calibrated = true
                        focus = emptyList()
                        calibrationError = "보정 적용됨 · 새 시야를 전송하면 관심 영역을 표시합니다"
                    } else calibrationError = "FOV는 5–150도, 오프셋은 -90–90도로 입력하세요"
                }) { Text("실측 보정값 적용") }
                if (calibrationError.isNotBlank()) Text(calibrationError)
                Text("관심 영역은 이미지 첨부·보정 적용·촬영 시각과 동기화된 자세가 모두 있어야 표시됩니다.")
                Text(if (result?.headPose != null) "마지막 프레임: 촬영 자세 동기화됨" else "마지막 프레임: 촬영 자세 없음 (관심 영역 표시 불가)")
            }
            Button(onClick = { hud = true }) { Text("HUD로 돌아가기") }
            if (pendingId != null) TextButton(onClick = { pendingId = null; continuous = false }) {
                Text("결과 조회 중단 (PC 작업은 취소되지 않음)")
            }
            if (response.isNotBlank()) Text(response)
        }
    }
}
