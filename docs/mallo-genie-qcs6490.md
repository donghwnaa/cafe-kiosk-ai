# MALLO Genie 온디바이스 검증

## Android 연결

Qualcomm의 텍스트 입력, 주문 화면 버튼과 DEBUG 텍스트 입력은
`MainActivity.submitOrder → KioskSessionViewModel → LlmNluEngine → GenieLlmEngine`으로 들어간다.
Genie 응답을 검증한 뒤 기존 `ConversationManager → OrderManager`가 초안, 확인, 장바구니 반영을 처리한다.
Genie를 사용하는 기기에서 초기화·추론 오류는 대화에 표시된다. 모델 준비에 실패하면
텍스트 입력 패널의 **초기화 재시도**로 모델 준비만 다시 실행한다. **원본 출력·상세**에서 실패 단계를 확인한다.

## 에뮬레이터

Android Studio의 기본 `debug` 빌드를 선택해 실행한다. 기본 빌드에는 `assets/llm/`와
Genie/QNN 네이티브 라이브러리가 포함되지 않으며 CMake를 실행하지 않는다.
기본 DEBUG 주문에서는 Fake NLU가 동작하며, 기존 SenseVoice·Silero VAD·Supertonic TTS를 사용한다.
Fake NLU 코드는 DEBUG 앱과 호스트 테스트에만 포함된다.
Pixel_5에서 **Extended Controls → Microphone → Enable Host Microphone Access**를 켜고,
Windows 마이크 권한과 앱의 녹음 권한을 확인한다. 에뮬레이터는 Android 기본 마이크에 맡긴다.
가상 마이크가 오디오를 반환하지 않거나 무음만 반환하면 주문 화면에서 각각의 원인을 확인할 수 있다.
ARM 명령어 변환을 제공하는 에뮬레이터도 Genie를 초기화하지 않는다.
`malloQairtSdk` 경로를 설정해 두어도 보드용 빌드는 `malloGenie=true`로 활성화한다.

모델은 QAIRT `2.40.0.251030` 빌드 `v2.40.0.251030114326_189385`를 기록한
QCS6490용 HTP 컨텍스트다. manifest와 HTP 확장 설정에서 SoC 93, v68, 컨텍스트 2048,
AR128 프리필과 AR1 생성 그래프가 일치한다. 호스트에서 모델의 1,527,758,848바이트 및
manifest SHA-256을 비교했다. 제공된 HTP 본체와 v68 stub·skel에서 동일한 QNN 빌드 문자열을 확인했다.
manifest는 `Llama_MALLO_v9_Merged`를 원본으로, `team6/full_calibration/quantized`를 양자화 체크포인트로,
`w8a16`을 캘리브레이션 정밀도로 기록한다. 학습 데이터의 SHA-256도 기록되어 있다.
원본 학습 데이터와 체크포인트 파일을 확보하면 이 기록까지 독립 검증할 수 있다.

- 기본 보드용 APK의 `assets/llm/`에 모델을 무압축으로 넣는다. 앱에서 모델을 초기화할 때
  `npu_manifest.json`의 크기와 SHA-256을 설치된 모델에 대조한다. 불일치하면 `no_backup/llm/`에
  임시 파일을 쓰고 크기와 해시를 검사한 뒤 이름을 바꾼다. 모델을 교체할 때 export manifest도 함께 갱신한다.
- Genie에 넘기는 JSON에서 tokenizer, `ctx-bins`, backend `extensions`를 앱 내부 절대 경로로 바꾼다.
  HTP 설정과 모델 컨텍스트의 그래프 구성은 원본을 따른다.
- SDK를 지정한 빌드에서는 ARM64 Genie·QNN 호스트 라이브러리 여섯 개를
  `app/build/generated/genie/jniLibs/arm64-v8a/`에서 APK의 `lib/arm64-v8a/`로 패키징한다.
  JNI를 로드한 다음 DSP 검색 경로를 설정하고 `libGenie.so`를 로드한다.
- `libQnnHtpV68Skel.so`는 Hexagon ELF다. `assets/llm/`에서
  `no_backup/llm/dsp/`로 복사하며 DSP가 `ADSP_LIBRARY_PATH`를 통해 찾는다.
  FastRPC의 `libcdsprpc.so`는 보드 펌웨어에서 제공해야 한다.
- `prompt_format.json`의 기존 시스템 메시지와 Llama 3 채팅 구분자를 사용하며, 시스템 메시지가
  `MALLO_system_prompt.txt`와 일치하는지 확인한다.
  앱의 최상위 JSON 필드를 프롬프트에 명시하고 현재 질문, 초안 및 장바구니 항목을 전달한다.
  `bias_correction.json`, `npu_manifest.json`은 빌드 출처와 검증용 메타데이터다.
  `genie-t2t-run`, `run_npu.sh`, 빈 `prompt.txt`는 보드에서 별도로 실행할 때 사용한다.

기본 주문 JSON 예시:

```json
{"function":"add_menu","menu":"아메리카노","quantity":2,"temperature":"ICE"}
```

두 메뉴는 `multi_action.actions`에 평면 호출을 한 개씩 넣는다. 수량 변경은 최종 수량 `quantity`
또는 증감량 `delta`를 사용한다. 주문 옵션이 빠져 있으면 기존 확인 흐름에서 추가로 묻는다.
상품 삭제도 확인 카드에 표시된 뒤에 장바구니에 반영한다. “아니요”를 답한 초안은 수정 안내 상태로
돌아가며, 수정 후 새 확인을 받아야 한다. Genie가 확인 또는 결제로 분류해도 사용자의 명시적 확인 또는
결제 발화가 필요하다. 여러 요청을 합쳐 동일 메뉴·옵션 한 줄이 99잔을 넘으면 전체 변경을 거부한다.

SDK와 보드 없이 파일 무결성과 주문 로직을 확인할 수 있다.

```powershell
.\gradlew.bat --offline :app:verifyGenieModel :app:testDebugUnitTest :app:assembleDebug
```

Genie 주문 단위 테스트는 한국어 입력과 미리 작성한 Function Call JSON을 사용한다.

## 보드용 APK 만들기

1. Android Studio의 SDK Manager에서 **NDK (Side by side) 28.2.13676358**과 **CMake 3.22.1**을 설치한다.
2. **QAIRT 2.40.0.251030**의 전체 `include/Genie/`가 있는 SDK 루트를 준비한다. 핵심 헤더는
   `include/Genie/GenieDialog.h`다. `GenieCommon.h`, `GenieEngine.h`, `GenieLog.h`,
   `GenieProfile.h`, `GenieSampler.h` 등 그 버전의 모든 종속 헤더가 필요하다.
   모델과 ARM64 런타임은 제공된 파일을 그대로 사용한다.
3. PowerShell에서 보드용 패키징을 활성화하고 SDK 경로를 넘긴다.

   ```powershell
   .\gradlew.bat -PmalloGenie=true "-PmalloQairtSdk=C:/path/to/qairt/2.40.0.251030" :app:packageGenieDebug :app:testDebugUnitTest
   ```

   `packageGenieDebug`는 APK 내부의 실제 JSON, tokenizer, DSP skel, ARM64 JNI/Genie/QNN과
   내장 모델의 저장 방식을 확인한다. 성공한 보드용 APK는
   `app/build/outputs/mallo/app-qcs6490-embedded-debug.apk`에 있다. 기본 에뮬레이터 빌드는
   `app/build/outputs/apk/debug/app-debug.apk`를 사용한다.
   현재 개발 PC에서 공식 배포본의 Genie 헤더만 따로 받은 경로는
   `.gradle/qairt-2.40.0.251030/include/Genie/`다. 이 구조라면 SDK 루트 인자는
   `-PmalloQairtSdk=.gradle/qairt-2.40.0.251030`이다.

### 모델 분리형 APK

APK 크기를 줄이려면 빌드 명령에 `-PmalloEmbedGenieModel=false`를 추가한다.
출력은 `app/build/outputs/mallo/app-qcs6490-split-debug.apk`다.
토크나이저, Genie 설정, 호스트 라이브러리와 DSP skel은 앱에 포함되며
`llama32_hf_v68.bin`을 아래처럼 따로 전송한다. 기기에 앱을 설치해 한 번 시작한 뒤 진행한다.
다른 저장소 볼륨을 쓰는 보드에서는 앱의 외부 파일 디렉터리를 기준으로 경로를 조정한다.

```powershell
adb shell mkdir -p /sdcard/Android/data/com.example.cafe_kiosk_ai/files/llm
adb push app/src/main/assets/llm/llama32_hf_v68.bin /sdcard/Android/data/com.example.cafe_kiosk_ai/files/llm/
```

첫 실제 추론에서 앱이 파일 크기 및 SHA-256을 확인하고 앱 내부 저장소로 복사한다.
이때 전송 파일과 검증된 내부 복사본을 함께 저장할 공간이 필요하다. 이후 실행은 내부 복사본을 사용한다.
보드의 ADB 정책이 앱 외부 파일 디렉터리에 쓰기를 허용하지 않으면 모델 내장 APK를 사용한다.

### 버튼으로 전체 예시 대화 실행

보드용 DEBUG 앱에서 **주문하기 → 예시 대화 자동 실행 → 전체 시나리오 → 예시 대화 자동 실행**을 누른다.
A~F 시나리오의 42개 발화가 순서대로 Genie를 호출한다. 보통 크기, 온도 및 확인 발화를 필요한 곳에
넣어 실제 주문 정책을 따른다. 각 시나리오는 빈 테스트 장바구니에서 시작한다.
**중지**로 현재 실행을 취소하거나, 선택 메뉴에서 특정 시나리오만 다시 실행할 수 있다.
앱 안내문과 모델 원문은 구분된다. **원본 JSON·상세**에서 요청 ID별 실제 응답과 시간을 확인한다.
`MALLO_LLM`의 `INFERENCE_START`, `INFERENCE_COMPLETE`, `DEMO_RESULT`, `DEMO_RAW`를 같은 요청 ID로 대조한다.
예외 시나리오에서 잘못된 주문이 거부되면 JSON은 거부로 표시되고, 장바구니를 지킨 시나리오는 통과로 표시된다.
진단 중에는 일반 주문의 마이크와 TTS를 잠시 멈추고, 테스트 세션의 결제 진입을 차단한다.

## QCS6490에서 확인할 순서

1. USB 디버깅을 활성화하고 보드를 연결한다. 여러 기기가 표시되면 각 ADB 명령에 `-s <serial>`을 붙인다.

   ```powershell
   adb devices -l
   adb shell getprop ro.product.cpu.abilist
   adb shell getprop ro.board.platform
   adb shell getconf PAGE_SIZE
   adb shell df -h /data
   adb shell ls -l /vendor/lib64/libcdsprpc.so
   ```

   ARM64, 펌웨어의 FastRPC 공개 여부 및 여유 공간을 확인한다. 설치된 APK 외에 첫 실행에서
   약 1.55 GB의 모델·토크나이저·DSP 파일이 추가로 필요하다. 제공된 `libGenie.so`의 ELF
   LOAD 정렬은 4 KiB다. 16 KiB 보드에서는 해당 펌웨어의 호환 모드에서 동작하는지 검증하고,
   로더가 거부하면 공급처의 16 KiB 대응 런타임이 필요하다.

2. APK를 설치하고 앱을 연다. 네트워크를 끄고 USB ADB는 유지한다.

   ```powershell
   adb install -r app/build/outputs/mallo/app-qcs6490-embedded-debug.apk
   adb shell setprop log.tag.MALLO/GenieJson DEBUG
   adb shell am start -n com.example.cafe_kiosk_ai/.MainActivity
   adb logcat -s MALLO_LLM:D MALLO/GenieJson:D AndroidRuntime:E
   ```

   첫 주문에서 모델 검사, 설정, 초기화, 추론, 파싱 및 주문 상태가 순서대로 표시되는지 확인한다.
   `MALLO/GenieJson`은 DEBUG에서 속성을 켜면 검증된 주문 필드만 기록한다.
   테스트 후 `adb shell setprop log.tag.MALLO/GenieJson INFO`로 끌 수 있다.

3. **주문하기**를 누르면 Qualcomm 대화 화면 아래에 **주문 내용을 입력하세요**, **전송**,
   **입력 지우기**, **예시 발화**가 표시된다. Qualcomm에서는 이 화면을 사용하는 동안 STT와 TTS를
   시작하지 않는다. **예시 발화**에서 문장을 고르면 입력창에 들어간다. **전송**을 누르면 같은 Genie 주문 경로가 실행된다.
   **아이스 아메리카노 두 잔 주세요. → 보통 크기로 주세요. → 맞아요.**를 각 응답을 기다리며 입력한다.
   앱에서 크기 질문과 마지막 장바구니의 두 잔을 확인한다. **원본 출력·상세**에서 모델 원문 또는 오류 원인을 확인한다.
   `ASSET_CHECK`, `MODEL_COPY`, `CONFIG_VALIDATION`, `NATIVE_LIBRARY_LOAD`, `GENIE_INIT_SUCCESS`,
   `INFERENCE_START`, `INFERENCE_COMPLETE`, `JSON_VALIDATION_PASS` 또는 `JSON_VALIDATION_REJECTED`,
   `ORDER_APPLY`를 로그에서 확인한다. `INFERENCE_COMPLETE`의 시간은 Genie 질의 시간이다.

   Android Studio의 **Running Devices**에서 보드 미러링을 열고 앱 입력창을 클릭한다.
   하드웨어 입력 전달은 Running Devices의 **Hardware input**에서 켤 수 있다.
   Windows 한글 IME 조합 전달은 기기 입력기와 미러링 설정에 따라 다를 수 있다.
   미러링에서 한글 입력 또는 붙여넣기가 되지 않으면 보드의 한글 입력기를 사용하거나,
   각 응답이 끝난 뒤 PowerShell에서 아래 명령을 실행한다. `-Text`를 생략하면 문장을 대화식으로 입력할 수 있다.
   이 경로는 DEBUG 앱의 전면 주문 화면에서만 접수하며 UTF-8/Base64와 `android.permission.DUMP`로 보호된다.

   ```powershell
   .\tools\mallo-test-input.ps1 -Serial '<adb-serial>' -Text '아이스 아메리카노 두 잔 주세요.'
   ```

4. 다음 입력으로 생성 품질과 실제 장바구니 동작을 확인한다. 변경 카드가 나오면 `맞아요`로 확정한다.

   | 입력 | 확인 |
   | --- | --- |
   | 아이스 아메리카노 한 잔 더 주세요 | 해당 항목 수량 3잔 |
   | 아메리카노를 큰 크기로 바꿔 주세요 | 확인 후 같은 항목이 TALL로 변경 |
   | 아메리카노 한 잔 빼고 따뜻한 카페라떼 한 잔 보통 크기로 주세요 | 한 번의 확인으로 두 변경 반영 |
   | 장바구니 보여 주세요 | 현재 장바구니만 표시 |
   | 망고주스 0잔 주세요 | 명확화 안내와 함께 장바구니 보존 |
   | 전체 주문 취소해 주세요 → 맞아요 | 두 번째 입력에서 전체 취소 |
   | 새 주문에서 추론 중 홈 버튼 누르기 | 이전 추론이 새 세션에 반영되지 않음. 활성 호출에 abort 요청 |
   | 앱을 다시 실행하고 주문 | 모델 검사 로그가 `cached=true` |

5. Genie 초기화에서 실패하면 Logcat의 `dlopen` 및 Genie 상태를 확인한다.
   FastRPC 라이브러리의 앱 네임스페이스 공개, DSP 펌웨어의 skel 서명 정책과 SELinux 로그,
   보드 메모리, 컨텍스트 역직렬화 로그를 확인한다. 초기화 또는 프리필 중 취소는 SDK가
   제어를 반환할 때까지 백그라운드에서 지연될 수 있다. 세션에는 취소된 응답을 반영하지 않는다.
   기존 음성 흐름은 에뮬레이터용 DEBUG 빌드에서 확인할 수 있다.

원본 CLI로 원인을 좁힐 때는 `assets/llm`의 파일들을 `/data/local/tmp/mallo-genie/`에
`adb push`하고 UTF-8 테스트 프롬프트를 `prompt.txt`에 넣는다. 보드에서
`adb shell 'cd /data/local/tmp/mallo-genie && chmod 755 genie-t2t-run && sh run_npu.sh'`를 실행한다.
CLI는 셸에서 실행되므로, 앱 패키징과 FastRPC 네임스페이스는 APK에서도 확인해야 한다.

## 팀원의 GitHub 체크아웃과 모델 배치

수정이 GitHub에 반영된 뒤 해당 브랜치를 업데이트한다. `npu_manifest.json`, tokenizer,
설정 JSON, Genie/QNN 라이브러리는 Git에 있다. 별도로 받은 원본 모델을 이 경로에 둔다.

수정이 `add_llm_fix`에 올라간 경우 기존 체크아웃에서 아래 명령을 실행한다.

```powershell
git switch add_llm_fix
git pull --ff-only origin add_llm_fix
```

`app/src/main/assets/llm/llama32_hf_v68.bin`

모델은 Git에서 제외되어 있다. 분리형 APK도 빌드 PC에서 원본을 먼저 검증한다.
아래 결과가 1,527,758,848바이트 및
`b9d75c9508fe4eb46431ec38a84e6292fc86ac022b01c4864204624aeecea4d3`인지 확인하고 APK를 빌드한다.

```powershell
git ls-files app/src/main/assets/llm
git check-ignore -v app/src/main/assets/llm/llama32_hf_v68.bin
(Get-Item app/src/main/assets/llm/llama32_hf_v68.bin).Length
Get-FileHash app/src/main/assets/llm/llama32_hf_v68.bin
```

## 팀원 보드에서 원래 오류를 확인하는 방법

기존 앱을 덮어쓰기 전에 오류를 재현하고 설치 APK 및 로그를 보관한다. 기기 한 대를 연결한 PowerShell 기준이다.

```powershell
adb devices -l
adb logcat -d -v threadtime -s 'MALLO/Genie:D' 'MALLO_LLM:D' 'AndroidRuntime:E' > mallo-before.log
$base = adb shell pm path com.example.cafe_kiosk_ai |
    Where-Object { $_ -match '^package:.*/base\.apk\s*$' } |
    Select-Object -First 1
if ($base) { adb pull $base.Trim().Substring(8) mallo-before.apk }
tar -tf mallo-before.apk | Select-String 'assets/llm/|lib/arm64-v8a/libmallo_genie'
adb shell run-as com.example.cafe_kiosk_ai ls -lR no_backup/llm
```

`run-as`는 DEBUG 설치에서 사용할 수 있다. 기존 앱의 오류가 파일을 열 때 발생했다면 새 빌드에서
`ASSET_CHECK_FAILED`와 원래 예외의 스택 트레이스가 나온다. 경로가 APK에 있어도 실패하면 해당 APK가
실제로 설치된 앱의 APK인지 확인하고 원래 예외를 대조한다. `GENIE_INIT_FAILED`가 나오면
`GenieDialogConfig_createFromJson` 또는 `GenieDialog_create`의 반환 상태와 인접 QNN/FastRPC 로그를 수집한다.
새 빌드에서 오류를 재현한 직후 `adb logcat -d -v threadtime > mallo-after.log`를 실행하면
앱 태그 외의 벤더 로그도 함께 수집된다.

새 APK 설치 후 아래 문장을 실제 Genie로 실행한다. 독립 테스트는 **홈 → 주문하기**로 시작하고
멀티턴 문장은 같은 세션에 순서대로 입력한다. 옵션·확인 질문에는 답변한 뒤 장바구니를 확인한다.

| 테스트 | 발화 |
| --- | --- |
| 기본 | 아아 두 잔 주세요 / 뜨아 한 잔 주세요 / 녹차 두 잔 주세요 |
| 복수 | 아이스 아메리카노 두 잔이랑 따뜻한 카페라떼 한 잔 주세요 / 아아 한 잔 큰 크기로랑 뜨아 한 잔 보통 크기로 주세요 |
| 멀티턴 | 아아 세 잔 보통 크기로 주세요 → 아아 하나 빼고 레몬에이드 한 잔 주세요 → 지금 주문한 거 보여주세요 |
| 재확인 | 아아 따뜻하게 한 잔 주세요 / 큰 걸로 바꿔 주세요 |

각 테스트에서 발화, 원본 JSON, 검증 결과, 추론 시간과 최종 주문 상태를 저장한다. 멀티턴 수정은 첫 주문의
확인을 마친 뒤 진행한다. 미러링으로 한글 조합과 붙여넣기를 각각 확인하고, 미러링 입력이 어려우면
위의 DEBUG UTF-8 입력으로 같은 주문 경로를 검증한다.

## 검증 환경

개발 PC에서는 APK 빌드, 정적 검사 및 단위 테스트를 수행한다. Qualcomm 실기기는 팀원이 가지고 있으므로
설치, Genie 초기화, 미러링 한글 입력과 실제 추론부터 장바구니 반영까지는 팀원이 위 절차로 확인한다.
실기기 결과를 수집하기 전 상태는 `NOT TESTED`다.
