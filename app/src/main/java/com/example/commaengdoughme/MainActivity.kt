package com.example.commaengdoughme

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.SystemClock
import android.content.Intent
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    val requestAllowed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("시야 연결 중") }
    var result by remember { mutableStateOf<VisionResult?>(null) }
    val credentials = remember { CredentialStore(activity.applicationContext) }
    var selectedProvider by remember { mutableStateOf(credentials.selected()) }
    val initialConfig = remember { runCatching { credentials.load(selectedProvider) } }
    var activeConfig by remember { mutableStateOf(initialConfig.getOrNull()) }
    var model by remember { mutableStateOf(activeConfig?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf(activeConfig?.apiKey.orEmpty()) }
    var configStatus by remember { mutableStateOf(if (initialConfig.isFailure) "저장한 키를 복원할 수 없습니다. 다시 등록하세요" else "") }
    var scanning by remember { mutableStateOf(false) }
    val scanEnabled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var pendingConfig by remember { mutableStateOf<ProviderConfig?>(null) }
    var buttonDiagnostic by remember { mutableStateOf("촬영 버튼: 아직 신호 없음") }
    var threshold by remember { mutableStateOf(false) }
    var interval by remember { mutableStateOf("1500") }
    var cameraConnected by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("현재 시야의 컴퓨터 문제를 설명하고 따라할 수 있는 해결 방법을 짧게 안내해 주세요") }
    var continuous by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf(true) }
    var lastSent by remember { mutableStateOf("") }
    var lastSendTime by remember { mutableLongStateOf(0L) }
    var steps by remember { mutableStateOf(listOf<String>()) }
    var stepIndex by remember { mutableIntStateOf(0) }
    val tracker = remember { HeadTracker(activity) { activity.window.decorView.display?.rotation ?: Surface.ROTATION_0 } }
    var recenterEpoch by remember { mutableLongStateOf(0) }
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
                    "0" -> { continuous = false; result = null }
                    "1" -> { recenterEpoch++; result = null }
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
    DisposableEffect(hud) {
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
            if (event == Lifecycle.Event.ON_PAUSE) { requestAllowed.set(false); continuous = false; result = null; if (scanning) running = false; scanning = false; scanEnabled.set(false) }
            if (event == Lifecycle.Event.ON_RESUME) recenterEpoch++
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); tracker.stop() }
    }

    fun saveConfig(config: ProviderConfig) {
        try {
            credentials.save(config)
            selectedProvider = config.provider; activeConfig = config; model = config.model; apiKey = config.apiKey
            continuous = false
            configStatus = "${config.provider.label} 설정 저장됨 · 연결은 아직 확인하지 않았습니다. 시야 보내기로 확인하세요"
        } catch (_: Exception) { configStatus = "설정을 안전하게 저장하지 못했습니다. 기기 보안 저장소를 확인하세요" }
    }
    fun submit() {
        val frame = result ?: return
        val config = activeConfig ?: return
        if (!running || scanning || sending || System.currentTimeMillis() - frame.capturedAtMs !in 0..10000) return
        sending = true; lastSent = frame.id; lastSendTime = System.currentTimeMillis()
        steps = emptyList(); response = "${config.provider.label} 분석 중"
        val requestGoal = goal
        requestAllowed.set(true)
        network.execute {
            val reply = runCatching { DirectVisionClient.send(config, frame, requestGoal,
                allowed = { requestAllowed.get() },
                progress = { message -> mainExecutor.execute {
                    if (!disposed && requestAllowed.get()) response = "${config.provider.label} / ${config.model}\n$message"
                } }) }
            mainExecutor.execute {
                if (!disposed) {
                    sending = false
                    reply.onSuccess { steps = it; stepIndex = 0; response = it.firstOrNull().orEmpty() }
                        .onFailure { continuous = false; response = if (it is IllegalStateException) it.message ?: "모델 요청 실패" else "모델 연결 실패 · 네트워크와 API 설정을 확인하세요" }
                }
            }
        }
    }
    val shutterAction by rememberUpdatedState(newValue = { ordered: Boolean ->
        when {
            !ordered -> {
                configStatus = "촬영 버튼 신호 수신 · 이 펌웨어의 기본 촬영 동작은 차단할 수 없습니다"
                if (hud) response = configStatus
            }
            !hud || scanning || pendingConfig != null || !activity.hasWindowFocus() -> {
                configStatus = "촬영 버튼 수신 · 설정·메뉴를 닫고 HUD에서 사용하세요"
            }
            sending -> Unit
            activeConfig == null -> response = "촬영 버튼 수신 · 먼저 Provider 설정을 등록하세요"
            !running || result == null || System.currentTimeMillis() - (result?.capturedAtMs ?: 0L) !in 0..10000 ->
                response = "촬영 버튼 수신 · 카메라를 연결하고 새 시야를 기다려주세요"
            else -> submit()
        }
    })
    DisposableEffect(activity) {
        // Official Glass3 CLICK broadcast. Never change global button settings.
        var registered = false
        var lastClick = -1000L
        val signals = linkedMapOf("CLICK" to 0, "DOWN" to 0, "UP" to 0)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
                val signal = when (intent?.action) {
                    "com.rokid.glass3.action.button.CLICK" -> "CLICK"
                    "com.android.action.ACTION_SPRITE_BUTTON_DOWN" -> "DOWN"
                    "com.android.action.ACTION_SPRITE_BUTTON_UP" -> "UP"
                    else -> return
                }
                signals[signal] = signals.getValue(signal) + 1
                buttonDiagnostic = "촬영 버튼: " + signals.entries.joinToString(" · ") { "${it.key} ${it.value}" } +
                    "\n최근 $signal: " + if (isOrderedBroadcast) "순서 있는 신호" else "차단 불가 신호"
                // DOWN/UP are diagnostic only: they must not trigger duplicate sends or intercept long presses.
                if (signal != "CLICK") return
                val ordered = isOrderedBroadcast
                if (ordered) abortBroadcast()
                val now = SystemClock.elapsedRealtime()
                if (now - lastClick < 500L) return
                lastClick = now
                shutterAction(ordered)
            }
        }
        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(activity, receiver,
                    IntentFilter("com.rokid.glass3.action.button.CLICK").apply {
                        addAction("com.android.action.ACTION_SPRITE_BUTTON_DOWN")
                        addAction("com.android.action.ACTION_SPRITE_BUTTON_UP")
                        priority = 100
                    },
                    ContextCompat.RECEIVER_EXPORTED)
                registered = true
            }
        }
        fun unregister() {
            if (registered) { activity.unregisterReceiver(receiver); registered = false }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose { activity.lifecycle.removeObserver(observer); unregister() }
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
    BackHandler(enabled = !hud) { if (scanning) running = false; scanning = false; scanEnabled.set(false); result = null; hud = true }
    DisposableEffect(Unit) {
        onDispose {
            scanEnabled.set(false)
            disposed = true
            requestAllowed.set(false)
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
                            val worker = VisionAnalyzer(threshold, interval.toLongOrNull()?.coerceIn(250L, 60000L) ?: 1500L, mainExecutor,
                                onResult = { if (running && !scanning) { result = it; status = "시야 인식 중"; cameraConnected = true } },
                                onError = { status = it; cameraConnected = false },
                                setupScan = { scanEnabled.get() },
                                onSetup = { raw ->
                                    runCatching { ProviderConfig.parse(raw) }.onSuccess {
                                        scanEnabled.set(false); scanning = false; running = false; result = null; continuous = false
                                        pendingConfig = it
                                        status = "설정 QR을 화면에서 치운 뒤 카메라를 다시 연결하세요"
                                    }.onFailure { configStatus = "지원하는 설정 QR이 아닙니다. 로컬 설정 도구로 생성하세요" }
                                },
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
            cameraConnected = false
            analysis?.clearAnalyzer()
            analysis?.let { provider?.unbind(it) }
            analyzer?.close()
        }
    }
    if (hud) {
        SpatialHud(
            tracker = tracker, mvp = true,
            text = if (steps.isEmpty()) response.ifBlank {
                if (activeConfig == null) "메뉴에서 Provider와 API 키를 등록하세요.\n설정 QR로 입력할 수 있습니다."
                else "시야 보내기를 눌러 현재 화면의 조언을 받으세요."
            } else steps[stepIndex],
            focus = emptyList(), calibration = SpatialCalibration(), calibrated = false, spatialEnabled = false,
            cameraReady = cameraConnected, cameraRequested = running, cameraStatus = status,
            agentBusy = sending, agentConfigured = activeConfig != null,
            canSpeak = running && cameraConnected && result != null && activeConfig != null && !sending, stepIndex = stepIndex, stepCount = steps.size,
            continuous = continuous,
            recenterEpoch = recenterEpoch,
            onSpeak = { submit() }, onPrevious = { if (stepIndex > 0) stepIndex-- },
            onNext = { if (stepIndex < steps.lastIndex) stepIndex++ },
            onSettings = { continuous = false; hud = false },
            onCameraToggle = {
                if (running) { continuous = false; running = false; status = "시야 일시 정지" }
                else connectCamera()
            },
            onShare = { if (continuous) continuous = false else if (running && activeConfig != null) continuous = true },
        )
        return
    }
    val settingsInsetX = LocalConfiguration.current.screenWidthDp.dp * .08f
    val settingsInsetY = LocalConfiguration.current.screenHeightDp.dp * .08f
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = settingsInsetX, vertical = settingsInsetY).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (scanning) running = false; scanning = false; scanEnabled.set(false); result = null; hud = true }) { Text("‹ HUD로 돌아가기") }
            Text("Provider 설정", style = MaterialTheme.typography.titleLarge)
            Text(buttonDiagnostic)
            Text("현재 시야의 이미지와 읽은 글자를 LLM에 전달하고, 조언을 HUD에 표시합니다.")
            Text("PC 중계 없이 선택한 Provider로 직접 전송합니다.")
            ModelProvider.entries.forEach { provider ->
                TextButton(enabled = !sending && !scanning, onClick = {
                    selectedProvider = provider; continuous = false
                    val loaded = runCatching { credentials.load(provider) }
                    activeConfig = loaded.getOrNull(); model = activeConfig?.model ?: if (provider == ModelProvider.OPENROUTER) "google/gemma-4-26b-a4b-it:free" else ""; apiKey = activeConfig?.apiKey.orEmpty()
                    configStatus = when {
                        loaded.isFailure -> "저장된 키 복원 실패 · 다시 등록하세요"
                        activeConfig == null -> "${provider.label} 선택됨 · 이 Provider의 키와 모델을 등록하세요"
                        else -> "${provider.label} / ${activeConfig?.model} 선택됨 · 시야 보내기로 연결을 확인하세요"
                    }
                    runCatching { credentials.select(provider) }
                }) { Text("${if (selectedProvider == provider) "●" else "○"} ${provider.label}") }
            }
            Button(enabled = !sending, onClick = {
                continuous = false; result = null
                if (scanning) { scanEnabled.set(false); scanning = false; running = false }
                else { scanning = true; scanEnabled.set(true); configStatus = "로컬에서 만든 설정 QR을 카메라에 보여주세요"; connectCamera() }
            }) { Text(if (scanning) "QR 읽기 중지" else "설정 QR 읽기") }
            if (scanning) Text("키가 포함된 QR입니다. 읽기가 끝나면 QR 화면을 치우세요. 스캔 중 시야는 모델에 전송되지 않습니다.")
            if (selectedProvider == ModelProvider.OPENROUTER) Text("OpenRouter API 키가 필요합니다. 무료 모델만 사용하며, 시야는 OpenRouter와 해당 모델 제공자에게 전달됩니다.")
            if (configStatus.isNotBlank()) Text(configStatus)
            OutlinedTextField(model, { model = it }, label = { Text("이미지 입력 지원 모델 ID") },
                enabled = !sending && !scanning, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API 키 (직접 입력 또는 QR)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                enabled = !sending && !scanning, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !sending && !scanning, onClick = {
                runCatching { ProviderConfig(selectedProvider, model.trim(), apiKey.trim()) }
                    .onSuccess { saveConfig(it) }.onFailure { configStatus = "모델 ID와 API 키 형식을 확인하세요" }
            }) { Text("Provider 설정 저장") }
            TextButton(enabled = !sending && !scanning, onClick = {
                runCatching { credentials.delete(selectedProvider) }.onSuccess {
                    activeConfig = null; apiKey = ""; continuous = false; configStatus = "이 Provider의 키를 삭제했습니다"
                }.onFailure { configStatus = "키 삭제 실패" }
            }) { Text("저장한 키 삭제") }
            OutlinedTextField(goal, { goal = it }, label = { Text("해결할 문제 / 요청") },
                modifier = Modifier.fillMaxWidth())
            Text("이미지가 함께 전송됩니다. 메뉴에서 시야 공유를 켜면 최소 10초 간격으로 새 시야를 보냅니다. 앱을 벗어나면 공유가 중지됩니다.")
            Button(enabled = result != null && running && activeConfig != null && !scanning && !sending, onClick = { submit() }) {
                Text(if (sending) "에이전트 처리 중…" else "현재 시야로 도움 요청")
            }
            Button(onClick = { if (scanning) running = false; scanEnabled.set(false); scanning = false; result = null; hud = true }) { Text("HUD로 돌아가기") }
            if (response.isNotBlank()) Text(response)
        }
    }
    pendingConfig?.let { config ->
        AlertDialog(onDismissRequest = { pendingConfig = null },
            title = { Text("Provider 설정 가져오기") },
            text = { Text("${config.provider.label}\n모델: ${config.model}\nAPI 키 포함 (내용은 표시하지 않음)\nQR 화면을 치운 뒤 카메라를 다시 연결하세요.") },
            confirmButton = { TextButton(onClick = { saveConfig(config); pendingConfig = null }) { Text("암호화 저장") } },
            dismissButton = { TextButton(onClick = { pendingConfig = null }) { Text("취소") } })
    }

}
