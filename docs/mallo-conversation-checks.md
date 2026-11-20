# MALLO 통합 대화 검증

Android Studio에서 `app`을 실행하고 **음성으로 주문하기**를 누릅니다. 메뉴와 함께 표시되는 “무엇을 주문하시겠어요?”가 한 번 재생됩니다. Android가 마이크 권한을 요청하면 허용하고, 하단에 **듣는 중…**이 표시될 때 아래 문장을 말합니다. AI 안내가 재생될 때 하단에 **말로가 안내하고 있습니다.**가 표시되고, 마지막 안내가 끝나면 음성 인식이 자동으로 재개됩니다.

마이크 없이 주문 로직을 검증할 때는 `debug` 앱의 주문 화면을 열고 안내가 끝난 뒤 `./tools/mallo-test-input.ps1 -Text "아아 두 잔 주세요"`를 실행합니다.

| 순서 | 입력 또는 동작 | 확인할 결과 |
|---|---|---|
| 1 | `아아 두 잔 주세요` → `메뉴 다시 보여줘` → `큰 걸로 주세요` | 크기 질문이 유지됩니다. 처음 인사, 메뉴 10종, 사용자·AI 발화, 아메리카노 확인 이미지가 한 스크롤에 있습니다. |
| 2 | `맞아요`를 두 번 입력 | 장바구니에 ICE TALL 두 잔, 7,000원이 한 번만 반영됩니다. |
| 3 | `다른 메뉴 추가해줘` → `카푸치노 차가운 걸로 하나 더 주세요` → `보통으로` → `맞아요` | 기존 아아와 ICE REGULAR 카푸치노가 함께 남고, 최신 총액은 11,000원입니다. 이전 카드는 당시 총액을 표시합니다. |
| 4 | `아까 그거 하나 빼줘` → `첫 번째 음료` | 대상 질문에 답한 뒤 아아만 한 잔 줄어듭니다. |
| 5 | `카페라떼 얼마야` → `전부 취소할게요` → `아니요` → `주문한 거 보여줘` | 가격 안내와 취소 질문 후에도 현재 장바구니가 유지됩니다. |
| 6 | `카푸치노 한 잔 그리고 유자차 두 잔`을 입력하고 각각 온도와 크기 선택 | 옵션별 질문과 최종 확인 메시지가 줄바꿈됩니다. 두 상품 이미지와 수량이 같은 확인 카드 안에 보이고, 확정 전에 장바구니는 유지됩니다. |
| 7 | 위로 스크롤해 예전 메시지를 읽은 뒤 `주문한 거 보여줘` 말하기 | 읽는 동안에는 위치가 유지됩니다. 음성 입력이 수락되면 최신 사용자 메시지와 장바구니 카드로 이동합니다. |
| 8 | 화면 글자 크기를 키우고 6번 확인, 회전 후 돌아오기 | AI 문장과 가격이 가로·세로로 잘리지 않으며, 대화와 주문 상태가 중복 없이 남습니다. |
| 9 | 임시 주문을 확정하거나 취소 → `계산할게요` → **테스트 실패** → **테스트 성공** → 홈 | 결제 화면이 따로 표시되고 성공 뒤에는 주문 수정이 차단됩니다. 홈에서 새 주문을 열면 첫 인사와 메뉴부터 시작합니다. |

Windows에서 상태 관리, Fake NLU와 JSON 어댑터 테스트는 다음 명령으로 실행합니다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

## 모델과 음성 연동 지점

Qualcomm 보드에서 Genie 모델을 연속 주문에 재사용하려면 기존 Genie 번들에 ARM64 워커를 배포합니다. Windows용 Android NDK 경로와 보드의 ADB 시리얼을 지정합니다.

```powershell
.\tools\build-board-genie.ps1 -NdkPath C:\path\to\android-ndk -Serial 192.168.0.8:5555 -AdbPath C:\path\to\adb.exe
```

`adb logcat -s MalloGenie MalloGenieNative`에서 시작 시 `GENIE_PRELOAD_READY`와 `GENIE_WORKER`의 `reused`, `initMs`, `queryMs`, `firstMs`를 확인할 수 있습니다. 워커가 설치된 보드에서는 앱 시작 시 Genie를 백그라운드에서 로드합니다. 첫 요청은 입력 전체를 처리합니다. 이후에는 매번 전체 입력을 Genie에 전달하고, Genie의 KV rewind가 직전 완료 요청과 일치하는 접두부의 계산을 재사용합니다. 재사용에 실패하면 해당 워커에서 기존 전체 입력 경로로 처리합니다. `GENIE_PREFIX_REUSE`와 `GENIE_PREFIX_FALLBACK`을 확인할 수 있습니다.

v3 워커는 주문 화면을 나갈 때 실행 중인 추론을 취소하고 응답을 정리한 뒤 모델을 재사용합니다. 취소 직후 요청은 대화 상태를 초기화하고 전체 입력을 처리합니다. 응답을 정리하지 못하면 워커를 다시 시작합니다. 유휴 상태에서 가용 메모리가 1GiB 아래로 내려가면 모델을 해제하고 다음 주문에서 필요한 경우 다시 로드합니다. 워커가 없거나 시작 핸드셰이크에 실패하면 `GENIE_CLI` 경로를 사용합니다.

- `AndroidSpeechRecognizerController`는 `VoiceController`를 구현하고 최종 인식 텍스트와 상태를 `VoiceRecognitionEvent`로 전달합니다. 최종 텍스트, 카드 버튼과 디버그 입력은 `KioskSessionViewModel.submit(text)`를 거쳐 `ConversationManager.handleUserInput(text)`로 들어갑니다.
- `NluEngine.analyze(text, context)`에 현재 세션 ID, 질문 대상, 대화, 임시 주문과 장바구니 항목 ID를 전달합니다. `LlmNluEngine`을 사용하려면 실제 모델 호출을 구현하는 `LlmEngine`과 배포된 v9 형식에 맞춘 `FunctionCallAdapter`를 주입합니다.
- 현재 테스트 JSON의 한 예는 `{"function":"change_quantity","target_id":"<cart item ID>","quantity":2}`입니다. 앱이 같은 ID의 항목을 검증하고, 수량은 사용자가 확인할 때 반영합니다. 가격과 판매 옵션은 `MenuCatalog`에서 결정합니다.
- `AiResponseOutput`은 대화에 새로 추가된 AI 응답을 전달합니다. `KioskSessionViewModel`이 이를 발화 대기열에 넣고 `MainActivity`가 화면 표시 후 `TtsEngine`에 한 문장씩 전달합니다. QCS6490에서 별도 음성 엔진을 사용하려면 `MainActivity`의 `TtsEngine` 생성 위치에 새 구현체를 연결합니다.
- `adb logcat -s MalloTts`에서 한국어 음성 이름, 로캘, 품질, 설치 상태, 네트워크 필요 여부와 발화 시작·종료를 확인할 수 있습니다. 오프라인 검증은 기기의 Wi-Fi와 모바일 데이터를 끈 상태에서 새 주문을 열어 확인합니다.
