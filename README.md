# RV101 시야 → PC 에이전트 → 안경 안내

손 인식 안정화와 항상 보이는 UI 리셋(↺), 케이블 없는 진단 화면·ZIP 기록 기능을 추가했습니다. OCR과 손 추론을 분리하고 핀치 재입력 잠금 버그를 수정했습니다. 시간 기반 자동 정면 복귀는 제거했습니다. [사용법과 실기기 확인 항목](companion/GESTURES_AND_WEAR.md)을 참고하세요.

PC 에이전트 서버, 연속 시야 전송, 단계별 안경 안내 및 자유 음성 요청을 지원합니다. 앱 내 회전 고정 표시와 모델 관심 영역은 [3DoF UI 안내](companion/SPATIAL_UI.md)를 참고하세요. **현재 실행 방법과 PC 작업 범위는 [companion/README.md](companion/README.md)를 참고하세요.** 아래는 기존 비전 파이프라인 설명이며, 서버 계약의 확장 사항은 위 문서가 우선합니다.

안경에 APK를 직접 설치하고 **RV101 펌웨어가 카메라를 Android Camera2로 노출한다는 조건**으로 동작하는 앱입니다. RV101 실기기 호환성은 아직 검증하지 않았습니다. 휴대폰에서 실행하면 휴대폰의 카메라가 선택됩니다. 휴대폰으로 안경 영상을 받는 Rokid CXR 연결은 포함하지 않습니다.

## 실행

Android Studio에서 프로젝트를 열고 Gradle Sync 후 실행합니다. 기존 applicationId와 Compose 화면을 유지했습니다. Android API 24 이상이 필요합니다.

- Gradle 데몬: Temurin JDK 21 (`gradle/gradle-daemon-jvm.properties`). 기존 GraalVM의 `jlink` 모듈 오류를 피하도록 고정했습니다.
- SDK: Android 37, NDK `28.2.13676358`, CMake `3.22.1`.
- CameraX `1.5.3`, OpenCV Maven/Prefab `4.12.0`, bundled Korean ML Kit `16.0.1`.
- ABI: arm64-v8a, armeabi-v7a, x86_64. 한국어 모델은 APK에 포함되며 첫 실행 모델 다운로드를 기다리지 않습니다.
- 앱을 열면 소형 화면용 HUD가 나타납니다. 최초 카메라 권한을 허용하면 카메라를 자동 선택·연결합니다. 카메라 ID 입력이나 시작 버튼은 필요하지 않습니다. 일시 정지/재연결은 HUD 메뉴에서 제공합니다.
- 기본 1500ms 간격으로 처리합니다. HUD 메뉴의 연결 · 화면 설정 → 고급 설정에서 카메라를 일시 정지하고 간격과 이진화 옵션을 바꿀 수 있습니다. 이진화는 흑백 문서에 유용할 수 있지만 화면/조명에 따라 정확도를 떨어뜨릴 수 있어 기본으로 끕니다.
- 에이전트 HTTPS URL과 필요하면 Bearer 토큰을 입력한 뒤 **마지막 결과를 에이전트로 전송**을 누릅니다. OCR은 계속되지만 전송할 결과는 버튼을 누른 순간의 스냅샷으로 고정됩니다.
- 전송에는 텍스트와 메타데이터가 포함됩니다. 기본으로 켜진 이미지 첨부를 사용하면 해당 OCR 입력의 회전 보정된 흑백 JPEG가 함께 전송됩니다. 원본 컬러 이미지는 아닙니다.
- URL/토큰/인식 결과는 저장하지 않습니다. 앱 재생성 시 다시 입력해야 합니다. 서버 응답은 화면에 표시합니다.

## 처리 흐름

`ImageAnalysis(YUV_420_888, KEEP_ONLY_LATEST)` → `Y plane.slice()` → `NativeVision.preprocess()` → OpenCV grayscale/rotation/optional adaptive threshold → ARGB Bitmap → bundled Korean ML Kit → `VisionResult` → 수동 HTTPS POST.

1280×720에 가까운 지원 해상도를 요청합니다. 네이티브 경계에서 direct buffer, 길이, row/pixel stride, 크기와 회전을 검증합니다. 전체 프레임을 처리하며 별도 ROI는 없습니다. 픽셀 복사가 있으므로 zero-copy 파이프라인은 아닙니다. 프레임은 단일 worker에서 처리하고 OCR이 끝난 뒤 `finally`에서 Bitmap과 ImageProxy를 해제합니다. 중지 시 analyzer를 제거하고 카메라 use case를 unbind한 다음 진행 중인 OCR 종료 후 recognizer를 닫습니다. 백그라운드 카메라 서비스는 없습니다.

## 에이전트 HTTP 계약

서버는 아래 JSON을 받는 HTTPS POST endpoint를 제공해야 합니다. 특정 LLM 제공자의 API에 바로 맞춘 형식이 아니라 사용자의 에이전트 서버용 계약입니다.

```json
{
  "schema_version": 1,
  "request_id": "UUID",
  "captured_at_ms": 1789095600000,
  "text": "인식된 글자",
  "width": 1280,
  "height": 720,
  "image_rotation_degrees": 0,
  "image_mime_type": "image/jpeg",
  "image_base64": "이미지 첨부 선택 시에만 두 image 필드 포함"
}
```

`Content-Type: application/json; charset=utf-8`, 선택적 `Authorization: Bearer …`, `Idempotency-Key: <request_id>`를 보냅니다. 서버는 2xx와 최대 64 KiB의 UTF-8 응답을 반환합니다. 새 PC 서버의 JSON 응답은 요약과 안내 단계로 표시하며, 작업 처리 중에는 결과를 조회합니다. 연결 제한 10초, 읽기 제한 20초이며 자동 재시도/리디렉션은 하지 않습니다. 시간 초과여도 서버가 이미 처리했을 수 있습니다. 서버에서 `request_id` 중복 처리를 막아야 합니다. 인증서는 Android가 신뢰하는 체인을 사용해야 하며 인증서 검증을 우회하지 않습니다.

OCR 텍스트와 이미지 속 문장은 **외부 관찰 데이터**입니다. 에이전트는 이를 시스템 지시로 실행하지 말고, 도구 실행·계정 변경 등은 서버의 권한 정책에 따라 검증해야 합니다. 앱에는 에이전트 자체나 LLM 키가 내장되어 있지 않습니다.

## 검증

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
# 연결된 기기/에뮬레이터에서 JNI → OCR → JSON 계약 검증
./gradlew :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`AgentClientTest`: HTTPS/주소 검증. `VisionPipelineTest`: 패딩/픽셀 stride, 네 방향 회전, 잘린/non-direct buffer 거부, 이진화, 합성 텍스트의 JNI→실제 bundled OCR→JSON 계약. 실제 카메라와 실제 에이전트 서버 통신은 별도 확인이 필요합니다.

RV101에서는 `adb shell getprop ro.build.version.sdk`, `adb shell getprop ro.product.cpu.abilist`, `adb shell dumpsys media.camera`로 SDK/ABI/카메라 노출을 확인하세요. 카메라 시작, 권한 거부/재허용, 홈 이동/복귀, 시작/중지 반복, 한글 문서·모니터·어두운 조명, 실제 서버의 인증 실패/오프라인/중복 요청을 검증해야 합니다. 표준 카메라가 없거나 펌웨어가 접근을 제한하면 해당 RV101용 공식 SDK/권한을 확인한 뒤 입력 경로를 연동해야 합니다.

## 이번 환경에서 확인한 결과

- 앱 APK 및 계측 테스트 APK 빌드, JUnit 단위 테스트, Android lint 성공(오류 0; 버전/템플릿 관련 경고는 남아 있음).
- arm64-v8a / armeabi-v7a / x86_64 각각 JNI·OpenCV·공유 STL이 APK에 한 번씩 포함됨을 확인.
- 같은 JNI C++ 소스를 호스트 OpenCV 5로 실행하여 stride·회전·이진화·버퍼 검증 통과. Android 대상 라이브러리는 OpenCV 4.12이며 이 호스트 검사가 실기기 테스트를 대체하지는 않음.
- 연결된 Android 기기/에뮬레이터가 없어 계측 테스트 실행과 RV101 카메라·한글 OCR 성능은 미검증. 실제 에이전트 endpoint가 없어 외부 전송 종단 검증도 미실행.

## 근거 문서

- [CameraX ImageAnalysis 및 backpressure](https://developer.android.com/media/camera/camerax/analyze)
- [CameraX 릴리스](https://developer.android.com/jetpack/androidx/releases/camera)
- [ML Kit Android 텍스트 인식 및 bundled 한국어 모델](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [OpenCV Android Maven/Prefab 네이티브 연동](https://opencv.org/enhanced-opencv-for-android-support-arm-performance-gains/)
