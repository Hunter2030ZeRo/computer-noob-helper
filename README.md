# 컴맹도우미 — RV101 MVP

사용자의 시야를 LLM에 전달하고, 사용자가 따라 할 수 있는 조언을 안경 화면에 표시합니다. **안경 앱에서 OpenAI 또는 Gemini API에 직접 연결하며, 실행 중 PC 중계 서버는 필요하지 않습니다.** ChatGPT 계정 로그인은 제공하지 않습니다.

## API 키를 입력하지 않고 등록하기

PC에서 한 번 설정 QR을 생성합니다. Python 3.10 이상이 필요합니다.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r companion/requirements-setup.txt
.venv/bin/python companion/make_setup_qr.py
```

1. 도구에 `openai` 또는 `gemini`, **이미지 입력과 JSON 응답을 지원하는 모델 ID**, 해당 Provider의 API 키를 입력합니다. 키 입력은 화면에 표시되지 않습니다.
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

## 구성과 범위

- CameraX 자동 카메라 연결, OpenCV 전처리, ML Kit OCR
- ML Kit QR 인식과 Provider별 암호화 설정 보관
- OpenAI Chat Completions / Gemini generateContent HTTPS 직접 요청
- 조언과 해결 단계 표시, 작은 화면용 중앙 고정형 HUD와 모달 메뉴

전송 주소는 `api.openai.com`과 `generativelanguage.googleapis.com`으로 고정되어 있으며 QR로 임의 서버를 지정할 수 없습니다.

**음성 입력, 손 제스처, 3DoF/VIO/SLAM, 손 관절 진단, PC 자동 조작은 현재 MVP에서 제외합니다.** 이전 실험 코드와 테스트는 보존하지만 앱 진입 경로에서는 실행하지 않습니다. [기존 PC 서버 문서](companion/README.md)는 이전 구현의 기록이며 현재 앱 연결에는 사용하지 않습니다.

## 빌드 및 검증

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
.venv/bin/python -m unittest discover -s companion -p test_setup_qr.py -v
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

실제 RV101에서의 QR 인식·키 저장·모델 API 요청은 별도 검증이 필요합니다. ADB 연결 없이 APK를 설치해 시험하는 환경을 전제로 합니다. Android 계측 테스트는 실행 기기가 없으면 APK 컴파일까지만 검증합니다.
