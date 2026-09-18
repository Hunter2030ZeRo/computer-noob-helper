# 컴맹도우미 — RV101 MVP

사용자의 시야를 LLM에 전달하고, 사용자가 따라 할 수 있는 조언을 안경 화면에 표시합니다. **안경 앱에서 OpenAI·Gemini API 또는 OpenRouter에 직접 연결하며, 실행 중 PC 중계 서버는 필요하지 않습니다.** ChatGPT 계정 로그인은 제공하지 않습니다.

## API 키를 입력하지 않고 등록하기

PC에서 한 번 설정 QR을 생성합니다. Python 3.10 이상이 필요합니다.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r companion/requirements-setup.txt
.venv/bin/python companion/make_setup_qr.py
```

1. 도구에 `openai`, `gemini` 또는 `openrouter`, **이미지 입력과 JSON 응답을 지원하는 모델 ID**, 해당 Provider의 API 키를 입력합니다. 키 입력은 화면에 표시되지 않습니다.
2. 생성된 `setup-qr.html`을 로컬 브라우저에서 엽니다. 다른 기기로 파일을 옮기면 휴대폰 화면에서도 표시할 수 있습니다.
3. 안경 앱에서 **메뉴 → Provider 설정 → 설정 QR 읽기**를 선택하고 QR을 바라봅니다.
4. 인식된 Provider·모델을 확인하고 **암호화 저장**을 선택합니다.
5. QR 창을 닫고 파일을 삭제한 다음, HUD 메뉴에서 카메라를 다시 연결합니다.

QR에는 실제 API 키가 들어 있습니다. 외부 QR 생성 사이트에 키를 붙여넣거나 QR 파일을 공유하지 마세요. 생성 도구는 설치 후 네트워크 없이 동작합니다. 기존 `setup-qr.html`이 있으면 덮어쓰지 않습니다. 스캔 중 OCR·시야 전송은 중지되고, 스캔 완료/취소 시 카메라도 중지됩니다.

설정 화면에서 직접 입력할 수도 있습니다. Provider별 키와 모델은 Android Keystore의 키로 암호화해 기기에 저장하며, **저장한 키 삭제**로 지울 수 있습니다. API 키는 APK에 포함하지 않습니다. 모델 ID는 자동 선택하지 않으므로 사용 가능한 모델 ID를 지정해야 합니다. API 이용 요금은 선택한 Provider의 API 계정에 따릅니다.

## 사용 흐름

1. 앱 실행 → 카메라 자동 연결. 최초 한 번 Provider를 등록합니다.
2. **시야 보내기** → 현재 이미지와 OCR 내용을 선택한 Provider에 전달합니다.
3. LLM의 요약과 해결 단계를 중앙 고정형 HUD에서 읽습니다. 긴 안내는 페이지/단계 버튼으로 넘깁니다.
4. 메뉴의 **시야 공유 시작 / 중지**로 주기적인 공유를 켜고 끕니다.

주기적 공유는 실시간 영상 스트리밍이 아니라 **최소 10초 간격의 스냅샷 전송**입니다. 이전 분석이 끝나야 다음 시야를 전송합니다. 카메라 중지, 설정 진입, 앱 일시 정지, 지원되는 탈착 이벤트에서 공유가 꺼지며 앱 복귀 후 자동으로 다시 공유하지 않습니다.

현재 영상은 회전 보정된 **흑백 JPEG + OCR 텍스트**입니다. 10초보다 오래된 시야는 새 요청에 사용하지 않습니다. 요청은 각각 독립적이고 대화 기억은 없습니다. 설정 화면에서 해결할 문제를 별도로 적을 수 있습니다.

## 물리 촬영 버튼 (펌웨어별 확인 필요)

[Rokid 공식 Glass3 문서](https://x-docs.rokid.com/docs/faq/应用自启动与按键拦截自定义.html)의 `com.rokid.glass3.action.button.CLICK` 브로드캐스트를 사용합니다. 앱이 RESUMED 상태일 때만 동적으로 수신하며, 앱을 벗어나면 수신기를 해제합니다. 시스템 전역 설정을 변경하지 않습니다.

- HUD에서 짧게 한 번 누르기: 기본 촬영 동작을 중단하고 현재 시야를 전송합니다. 순서 있는 브로드캐스트로 전달되고 앱 수신기가 먼저 실행되어야 합니다.
- 설정·QR 등록·모달 메뉴에서는 전송하지 않습니다. 분석 중 중복 요청도 만들지 않습니다.
- 두 번 누르기와 길게 누르기는 변경하지 않습니다.
- 순서 없는 신호가 오면 차단 불가 안내를 표시하고 시야를 자동 전송하지 않습니다.

설정 화면의 **촬영 버튼** 진단에는 CLICK/DOWN/UP 수신 횟수와 최근 신호의 순서 여부가 표시됩니다. DOWN/UP은 관찰만 하며 전송이나 기본 동작 차단에 사용하지 않습니다.

RV101 실기기에서 기존 CLICK 차단을 적용해도 시스템 촬영과 촬영 실패 음성이 발생한다는 보고가 있습니다. 카메라 점유 충돌 가능성은 있지만 확정되지 않았습니다. 펌웨어가 다른 이벤트를 보내거나 시스템 촬영을 먼저 실행하면 이 방식으로 대체되지 않을 수 있습니다. ADB 없이 새 APK로 HUD에서 버튼을 한 번 눌러 **사진 대신 분석이 시작되는지**, 홈으로 나간 뒤 **기본 촬영으로 돌아오는지** 확인해야 합니다. 사진만 촬영되고 앱 안내도 없다면 버튼 이벤트가 앱에 전달되지 않았거나 다른 Action일 가능성이 있습니다.

## OpenRouter 무료 이미지 모델 시험

2026-09-18 [공식 모델 API](https://openrouter.ai/api/v1/models)에서 이미지 입력·무료 요금·JSON 응답 지원을 확인한 `google/gemma-4-26b-a4b-it:free`를 기본 시험 모델로 사용합니다. 무료 모델의 제공 여부와 호출 한도는 변경될 수 있습니다.

1. OpenRouter에서 발급받은 API 키를 준비합니다. Gemini API 키와는 다릅니다.
2. QR 생성 도구에서 Provider에 `openrouter`를 입력합니다.
3. 모델 ID 질문에서는 Enter를 눌러 `google/gemma-4-26b-a4b-it:free`를 선택합니다.
4. OpenRouter 키를 입력하고 생성된 QR을 앱에서 읽어 저장합니다. 기존 QR 파일이 있으면 먼저 삭제하거나 다른 곳으로 옮겨야 합니다.
5. QR 화면을 닫고 카메라를 다시 연결한 뒤 **시야 보내기**로 시험합니다.

앱 설정에서도 **OpenRouter 무료**를 선택할 수 있습니다. `:free` 모델 ID 또는 `openrouter/free`만 허용합니다. 요청에는 입력·출력 토큰, 요청, 이미지의 가격 상한 0을 지정합니다. OpenRouter에서는 제공자별 호환성을 위해 `response_format`을 보내지 않고 프롬프트로 JSON을 요청한 뒤 앱에서 형식을 검증합니다. 무료 경로가 없으면 오류로 종료하며 유료 모델로 전환하지 않습니다. 시야는 OpenRouter와 해당 모델 제공자에게 전달됩니다.

현재 OpenRouter의 `nvidia/nemotron-3-ultra-550b-a55b:free`는 **텍스트 입력 전용**이므로 이 앱의 이미지 요청에 맞지 않습니다. `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`와 `qwen/qwen3.8-27b:free`는 이미지 입력을 지원합니다. 두 모델은 조회한 목록에 `response_format` 지원이 없어 이를 강제하지 않습니다. 실제 응답이 앱에서 요구하는 JSON 형식인지 실기기 확인이 필요합니다.

502/503/504는 대기 시간을 늘려 최대 2회 재시도합니다. 앱을 벗어나면 예정된 재시도를 중지합니다. 인증 실패·429·통신 시간 초과는 자동 재시도하지 않습니다.

## 연결 문제 확인

키 등록 성공은 기기에 설정을 저장했다는 뜻이며, API 인증 성공을 뜻하지 않습니다. **시야 보내기**로 요청하면 실패 시 Provider·모델과 오류 종류가 표시됩니다.

- DNS/접속 실패/시간 초과: 안경의 인터넷 연결을 확인합니다.
- TLS 실패: 안경의 날짜·시간과 네트워크를 확인합니다. 인증서 검증은 우회하지 않습니다.
- HTTP 401/403: 해당 Provider의 키와 API 사용 권한을 확인합니다.
- HTTP 400/404/422: 모델 ID와 이미지·JSON 응답 지원 여부를 확인합니다.
- HTTP 429: API 결제·할당량·요청 빈도를 확인합니다.

오류를 전달할 때는 Provider·모델 ID·오류 문구만 공유하고 API 키는 공유하지 마세요.

## 구성과 범위

- CameraX 자동 카메라 연결, OpenCV 전처리, ML Kit OCR
- ML Kit QR 인식과 Provider별 암호화 설정 보관
- OpenAI Chat Completions / Gemini generateContent / OpenRouter Chat Completions HTTPS 직접 요청
- 조언과 해결 단계 표시, 작은 화면용 중앙 고정형 HUD와 모달 메뉴

전송 주소는 `api.openai.com`, `generativelanguage.googleapis.com`, `openrouter.ai`로 고정되어 있으며 QR로 임의 서버를 지정할 수 없습니다.

**음성 입력, 손 제스처, 3DoF/VIO/SLAM, 손 관절 진단, PC 자동 조작은 현재 MVP에서 제외합니다.** 이전 실험 코드와 테스트는 보존하지만 앱 진입 경로에서는 실행하지 않습니다. [기존 PC 서버 문서](companion/README.md)는 이전 구현의 기록이며 현재 앱 연결에는 사용하지 않습니다.

## 빌드 및 검증

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
.venv/bin/python -m unittest discover -s companion -p test_setup_qr.py -v
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

실제 RV101에서의 QR 인식·키 저장·모델 API 요청은 별도 검증이 필요합니다. ADB 연결 없이 APK를 설치해 시험하는 환경을 전제로 합니다. Android 계측 테스트는 실행 기기가 없으면 APK 컴파일까지만 검증합니다.
