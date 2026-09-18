# PC 에이전트 서버

Python 3.10 이상, 추가 패키지 없음. 안경의 JPEG + OCR + 별도 사용자 요청을 비전 모델에 전달하고, 모델의 도구 호출을 PC에서 실행한 뒤 안경용 한국어 안내를 반환합니다. 각 요청은 독립적이며 이전 대화는 모델에 전달하지 않습니다.

## 실행

아래 환경 변수를 로컬 셸에 설정합니다. 실제 비밀 값은 Git에 저장하지 마세요.

```sh
export AGENT_TOKEN='<24자 이상의 임의 토큰>'
export MODEL_API_KEY='<모델 API 키>'
export MODEL_NAME='<이미지·function calling·JSON 응답을 지원하는 모델 ID>'
export MODEL_URL='https://api.openai.com/v1/chat/completions'
export AGENT_WORKSPACE='/absolute/path/to/dedicated-support-folder'
python3 companion/server.py
```

기본은 `127.0.0.1:8765`. Android는 HTTPS만 허용하므로 신뢰할 수 있는 인증서가 있는 HTTPS reverse proxy를 통해 `/vision`과 `/vision/{id}`를 이 포트에 연결하세요. 또는 `TLS_CERT`, `TLS_KEY`, `AGENT_HOST=0.0.0.0`을 설정하여 직접 TLS로 실행할 수 있습니다. 인증서 이름과 앱에서 입력한 호스트가 일치해야 합니다. TLS 검증을 우회하지 않습니다.

앱에 `https://<PC 서버 호스트>/vision`과 같은 `AGENT_TOKEN`을 입력합니다. 모델 API 키는 PC에만 둡니다. 앱 진입 시 카메라가 자동 연결됩니다. HUD에서 말하기 또는 메뉴를 사용하며, 텍스트 요청은 연결 설정의 요청 입력 → 현재 시야로 도움 요청 순서로 사용합니다. 음성 요청은 말하기가 끝나면 자동 전송되고 안내 화면으로 이동합니다. 연속 전송은 켠 상태에서 앱이 전경에 있을 때만 최소 10초 간격으로 수행하며, 이전 작업이 끝나야 다음 시야를 전송합니다. 처리 지연에 따라 실제 간격은 더 깁니다. 이미지 첨부를 끄면 OCR만 전달됩니다.

## PC 작업 범위

앱의 **PC 도구 허용**을 켜면 OS/디스크 진단, 지정 폴더의 파일 목록과 텍스트 읽기가 가능합니다. 서버에 `AGENT_ALLOW_CREATE=1`도 설정하면 해당 폴더에 해결 안내나 수정안 파일을 새로 생성할 수 있습니다. 기존 파일 덮어쓰기, 임의 셸 실행, 마우스/키보드 조작, 프로그램 설치는 구현하지 않았습니다. 전용 폴더에는 에이전트가 읽어도 되는 파일만 두세요. 모델이 도구를 선택하며 실제 실행 결과를 다음 모델 호출에 전달합니다.

## 자유로운 음성 요청

**에이전트에게 말하기**를 누르고 “이 오류는 왜 뜨는 거야?”, “이걸 해결할 방법을 찾아줘”처럼 자유롭게 말하세요. 인식이 끝나면 문장을 `goal`로 설정하고 마지막 시야와 함께 바로 전송합니다. 시야가 없으면 문장만 전송합니다. 고정 명령어 분류나 추가 전송 확인은 없습니다. “다음”, “중지” 등도 모델에 전달되며 앱 조작은 화면의 버튼으로 수행합니다.

현재는 Android 음성 인식 결과인 **텍스트**를 모델에 보내며 원본 오디오를 전송하는 방식은 아닙니다. 상시 청취/웨이크워드 방식도 아닙니다. 음성 화면을 열면 연속 시야 전송을 중단하고, 에이전트가 처리 중일 때는 말하기 버튼을 비활성화하여 요청 누락/중복을 방지합니다. 모델 요청은 여전히 각 요청 단위로 독립적입니다.

서비스가 없는 기기는 안내 오류와 버튼 조작을 제공합니다. 음성 처리의 네트워크 사용 여부는 설치된 인식 서비스에 따릅니다. [Android RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)를 사용합니다. RV101의 음성 서비스 및 실제 디스플레이 동작은 실기기 검증이 필요합니다.

## 앱 내 3DoF UI

머리 회전에 대해 문제 카드와 이미지 관심 영역을 고정하는 안내 화면을 추가했습니다. [보정·센서 요구 사항·제약](SPATIAL_UI.md)을 확인하세요.

## HTTP 계약과 보관

기존 schema_version 1 입력에 `goal: string`, `allow_pc: boolean`을 추가했습니다. 이미지/텍스트는 관찰 데이터이며 사용자 요청과 구분됩니다.

- `POST /vision`: Bearer 인증 및 `Idempotency-Key == request_id` 필수. 최대 3 MB. 202 응답 `{request_id,status:"queued"}`.
- `GET /vision/{request_id}`: 같은 인증. `queued`, `running`, `completed`, `failed` 중 하나.
- 완료 응답: `{request_id,status:"completed",advice:"요약",steps:["첫 단계"],actions:[{tool:"diagnostics",status:"completed"}]}`.
- 실패 응답은 오류 안내를 포함하며 자동으로 같은 작업을 다시 실행하지 않습니다.
- 같은 ID/내용은 기존 작업을 반환합니다. ID가 같고 내용이 다르면 400입니다. 큐가 차면 400을 반환합니다. 앱에서 조회만 중단해도 이미 접수된 PC 작업은 계속됩니다.

SQLite `agent-jobs.sqlite3`에 ID, 요청 해시, 최종 응답을 보관하여 재시작 후에도 중복 실행을 막습니다. 이미지와 원문 요청은 DB에 저장하지 않지만 모델 제공자에 전송됩니다. 응답에는 관찰 내용이 포함될 수 있습니다. DB는 자동 삭제되지 않습니다. 서버가 재시작될 때 미완료 작업은 실패로 표시하며 재실행하지 않습니다. 이 서버는 개인용 단일 PC/공유 토큰 구성입니다. OS 사용자 권한과 전용 작업 폴더를 사용하세요.

모델 연동은 [Chat Completions](https://developers.openai.com/api/reference/cli/resources/chat/subresources/completions)의 이미지 입력 및 function tool 계약을 사용합니다. 호환 제공자는 이 기능들과 JSON object 응답을 모두 지원해야 합니다.

## 테스트

```sh
python3 -m unittest discover -s companion -v
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

서버 테스트는 실제 localhost HTTP 서버와 모의 모델 응답을 사용합니다. 실제 API 키·HTTPS endpoint·RV101 없이 모델의 인식 정확도 및 실기기 종단 통신은 확인할 수 없습니다.
