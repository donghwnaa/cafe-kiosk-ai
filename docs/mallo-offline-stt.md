# MALLO 오프라인 STT 실험

## 설치와 빌드

브랜치를 받은 뒤 Android Studio에서 **Gradle Sync → Run**을 누릅니다. 첫 빌드에서 Gradle이 약 213 MB의 공식 파일을 내려받아 SHA-256을 확인하고 APK에 포함합니다. 빌드 PC에서 첫 다운로드를 할 때 인터넷에 연결하고 Gradle 오프라인 모드를 끕니다. 이후에는 Git에서 제외된 `app/offline-stt/downloads/`의 캐시를 사용합니다. 빌드 PC가 GitHub 다운로드에 접근할 수 없다면 아래 파일 세 개를 해당 폴더에 넣고 빌드합니다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

모델만 미리 준비하려면 `./tools/setup-offline-stt.ps1` 또는 `:app:prepareOfflineStt`를 실행합니다. 이 브랜치의 기본 엔진은 SenseVoice입니다. 로컬 Gradle 사용자 설정에 `malloStt=android`가 있으면 지워야 기본값이 적용됩니다. 비교용 SpeechRecognizer는 `-PmalloStt=android`로 빌드합니다. 두 빌드의 앱 ID는 동일하므로 APK를 교체해서 비교할 수 있습니다.

| 공식 다운로드 | 크기와 내용 |
| --- | --- |
| [sherpa-onnx-2.0.1.aar](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar) | 49,441,341 B. Kotlin API와 Android JNI 라이브러리입니다. |
| [sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2) | 163,002,883 B. `model.int8.onnx` 239,233,841 B와 `tokens.txt` 315,894 B를 포함합니다. |
| [silero_vad.onnx](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx) | 643,854 B. k2-fsa의 Silero v4 계열 16 kHz 전용 export입니다. |

공식 AAR은 Android API 21 이상이며 `x86_64`, `arm64-v8a`, `x86`, `armeabi-v7a` JNI를 포함합니다. MALLO는 minSdk 24, compile/target SDK 36, AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21을 사용합니다. Android Studio JBR 21로 빌드했으며, 공식 AAR을 사용하므로 NDK 설치가 필요하지 않습니다. Pixel 5 AVD에는 `x86_64`, QCS6490에는 `arm64-v8a` 라이브러리가 로드됩니다. 두 ABI의 ELF LOAD와 APK의 16 KiB ZIP 정렬을 확인했습니다.

sherpa-onnx는 Apache-2.0, ONNX Runtime과 Silero VAD는 MIT입니다. SenseVoice INT8 아카이브의 `LICENSE`는 FunASR 라이선스 안내를 가리킵니다. 원본 SenseVoiceSmall 가중치에는 FunASR Model Open Source License Agreement v1.1이 적용되며 모델명 표기 등 조건이 있습니다. 팀 배포는 버전과 체크섬을 고정한 이 스크립트나 승인된 사내 아티팩트 저장소를 사용하고, 외부 APK 배포 전 변환 가중치의 적용 조건과 라이선스 고지를 확인합니다. [sherpa-onnx 라이선스](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE) · [ONNX Runtime 라이선스](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) · [Silero 라이선스](https://github.com/snakers4/silero-vad/blob/master/LICENSE) · [SenseVoice 모델 라이선스 안내](https://github.com/FunAudioLLM/SenseVoice#license)

## 실험 설정

| 항목 | 초기값 |
| --- | --- |
| PCM | 16 kHz, 모노, 16비트, 512 샘플 창(32 ms) |
| Silero 민감도와 발화 시작 | `threshold=0.5`, `minSpeechDuration=0.16초` |
| 발화 종료 | `minSilenceDuration=1.2초`. 발화가 재개되면 침묵 판정이 초기화됩니다. 실제 종료는 VAD 확률과 처리 창에 따라 달라집니다. |
| 최대 발화와 무발화 대기 | VAD의 `maxSpeechDuration=15초`와 발화 감지 후 15초의 앱 측 강제 종료. 발화 시작을 감지하지 못하면 30초 후 재시도합니다. |
| 오디오 입력 | USB 입력이 연결돼 있으면 지원 레이트의 `MIC`와 `VOICE_RECOGNITION`을 USB에 명시적으로 라우팅합니다. USB가 없으면 시스템의 16 kHz 입력을 사용합니다. 내장 마이크는 진단 화면에서 개별 시험할 수 있습니다. 필요한 경우 모델 앞에서 16 kHz로 변환합니다. |
| SenseVoice | `language=ko`, `useInverseTextNormalization=true`, CPU 2스레드 |

Silero와 SenseVoice는 APK에 포함된 파일을 읽어 기기 CPU에서 실행합니다. 녹음은 발화가 끝나면 닫히고 SenseVoice 결과는 발화당 한 번 전달됩니다. Android TTS는 기존 엔진을 사용하며 오프라인 재생에는 로컬 한국어 음성이 필요합니다. [Android VAD + 비스트리밍 STT 예제](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1/android/SherpaOnnxVadAsr) · [Silero 모델과 예제](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) · [SenseVoice 모델과 ITN 설정](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)

SenseVoice는 APK의 약 239MB 모델과 토큰을 앱 전용 저장소에 한 번 풉니다. 모델 버전이 바뀌면 다시 준비하며, 파일을 준비할 수 없으면 APK 자산으로 로드합니다. 앱은 SenseVoice 파일을 먼저 준비하고, Genie가 초기 로딩을 끝낸 뒤 SenseVoice 인식기를 생성합니다. 이후 발화마다 새 입력 스트림으로 같은 인식기를 씁니다. Android가 저메모리 상태를 보고하거나 가용 메모리가 1GiB 아래로 내려가면 사용 중인 인식이 끝난 후 인식기를 해제합니다. `MalloStt`의 `SENSEVOICE_FILES_READY`, `SENSEVOICE_PRELOAD_READY`, `SENSEVOICE_DECODE reused=true`, `SENSEVOICE_RELEASED`로 상태를 확인할 수 있습니다.

첫 인사와 장바구니 조회(상품 있음·없음), 주문 반영 안내는 보드의 Supertonic 3 `ko-KR` 음색과 앱 기본 속도로 합성한 WAV를 `res/raw`에서 재생합니다. 첫 인사는 모델 준비 중에도 들을 수 있습니다. 첫 인사가 끝나면 Genie 로딩 중에도 마이크를 열 수 있습니다. 녹음이 먼저 끝나면 Genie 초기화와 SenseVoice 인식기 준비가 끝난 뒤 음성을 텍스트로 바꿉니다. 주문마다 달라지는 문구는 TTS로 재생하고, 그 서비스가 재생 시작 전에 응답을 잃으면 한 번 다시 연결합니다. `MalloAudio`의 `RECORDED_PROMPT_STARTED`와 `RECORDED_PROMPT_COMPLETED`, `MalloTts`의 `TTS_PLAYBACK_RETRY`로 확인할 수 있습니다.

Supertonic 3은 첫 일반 안내를 합성할 때 모델을 로드합니다. 일반 안내는 기본 5단계로 합성하고, 앱이 화면에 있는 동안에는 주문 화면을 오가도 같은 모델을 재사용합니다. Android가 메모리 압박을 알리거나 시스템 가용 메모리가 1GiB 아래로 내려가면 현재 합성이 끝난 뒤 모델을 해제합니다. 앱이 백그라운드로 가면 TTS 연결을 닫습니다. `MalloOfflineTts`의 `OFFLINE_TTS_GENERATE steps=5`, `OFFLINE_TTS_MODEL_USED`와 `OFFLINE_TTS_MODEL_RELEASED`로 확인할 수 있습니다.

최초 합성 때 Supertonic 모델 파일 7개를 앱의 백업 제외 저장소에 복사하고 SHA-256을 검증합니다. 이후에는 파일 경로로 모델을 엽니다. 모델 버전이나 파일 크기가 바뀌면 다시 복사하며, 복사 중단은 다음 실행에서 재시도합니다. 파일을 준비할 수 없으면 APK 자산을 사용합니다. `OFFLINE_TTS_MODEL_READY source=file`로 로딩 경로를 확인합니다.

문구나 음색을 갱신할 때는 보드에서 `RecordedPromptExportTest`를 `-e exportRecordedPrompts true`로 실행해 `cache/recorded-prompt-export/`에 WAV를 다시 생성합니다.

기기에서 두 로더를 비교하려면 디버그 앱과 `:app:assembleDebugAndroidTest`의 테스트 APK를 설치합니다. 16kHz 이상의 모노 PCM WAV를 앱의 캐시 디렉터리에 복사한 뒤 아래 계측 테스트를 실행합니다. `withGenie=true`는 Genie도 올려 실제 주문에 가까운 메모리 상태에서 비교합니다. `MalloSttBenchmark`에 인식기 생성 시간, 전체 인식 시간, 프로세스 RSS 피크와 시스템 가용 메모리 최솟값을 남깁니다.

```powershell
adb shell am instrument -w -e class com.example.cafe_kiosk_ai.SenseVoiceLoadingTest -e senseVoiceWav /data/user/0/com.example.cafe_kiosk_ai/cache/mallo-sensevoice-ab.wav -e withGenie true com.example.cafe_kiosk_ai.test/androidx.test.runner.AndroidJUnitRunner
```

## 오프라인 마이크 검증과 비교 방법

1. SenseVoice APK를 기기에 설치하고 Android TTS에 한국어 로컬 음성이 준비돼 있는지 확인합니다. 기기에서 비행기 모드를 켜고 Wi-Fi와 모바일 데이터를 끕니다. 에뮬레이터에 직접 말하려면 Extended Controls → Microphone → **Enable Host Microphone Access**를 켭니다. 버전에 따라 **Virtual microphone uses host audio input**으로 표시됩니다. [Android Emulator 마이크 안내](https://developer.android.com/studio/run/emulator-extended-controls)
2. 주문 화면에서 첫 안내가 끝나고 **듣는 중…**이 표시되면 표의 문장을 말합니다. ‘큰 걸로 주세요’는 크기 재질문 직후, ‘아니요, 보통 크기로 바꿔주세요’는 상품 확인 단계에서 입력합니다.
3. `adb logcat -s MalloStt MalloTts`에서 `SenseVoice final`, `Silero endpoint`, 마이크 시작·정지, TTS 시작·완료를 확인합니다. 최종 인식 문자열은 디버그 빌드에 기록됩니다. 상품 수량, 질문, 확인, 장바구니와 결제는 화면과 대조합니다.
4. 긴 문장 중간을 약 0.8초 쉬고 이어 말합니다. 다음 발화에서는 1.5초 이상 쉬어 발화가 먼저 확정되는지 확인합니다. 연속 발화, 무발화, TTS 출력 중 입력, 홈·결제·백그라운드 이동 후 복귀도 반복합니다.
5. 동일한 마이크, 거리, 발화 및 환경에서 `-PmalloStt=android` APK를 설치해 `Android SpeechRecognizer final` 로그와 비교합니다. 메뉴·수량·온도·크기 정확도, 잘린 발화, 무발화 오탐, 발화 종료부터 최종 결과까지 걸린 시간을 기록합니다.

Windows에서 로컬 Microsoft Heami 합성 WAV로 공식 VAD + ASR 도구를 먼저 확인했습니다. 같은 WAV를 Pixel 5 API 37 AVD의 가상 마이크에 입력했습니다. AVD의 비행기 모드는 켜고 Wi-Fi와 모바일 데이터는 껐으며, IPv4·IPv6 라우팅 테이블이 비어 있음을 확인했습니다.

| 입력 | Windows 공식 도구 | Android AVD Logcat |
| --- | --- | --- |
| 아아 두 잔 주세요 | 아아 둘 잔 주세요. | 아아 둘 잔 주세요. |
| 아메리카노 두 잔하고 카페라떼 한 잔 주세요 | 아메리카노 두 잔 하고 카 페 라떼 한 잔 주세요. | 아메리카노 두 잔 하고 카 라떼 한 잔 주세요. |
| 카푸치노 아이스로 한 잔 주세요 | 카푸치노 아이 스로 한 잔 주세요. | 카푸치노 아이 스로 한 잔 주세요. |
| 큰 걸로 주세요 | 큰 걸로 주세요. | 큰 걸로 주세요. |
| 아니요, 보통 크기로 바꿔주세요 | 아니요, 보통 크 기로 바꿔 어 주세요. | 아니요, 보통 크 기로 바꿔 어 주세요. |
| 주문한 거 보여주세요 | 주 문한 거 보여 주세요. | 주 문한 거 보여 주세요. |

아아 두 잔의 크기 재질문, 큰 사이즈 확인, 보통으로 변경, 음성 확인, 장바구니 조회, 테스트 결제 진입과 성공을 확인했습니다. 두 메뉴 주문에서 SenseVoice가 카페라떼의 일부를 누락했고 기존 주문 로직은 수량을 재질문했습니다. Android TTS가 끝난 다음 녹음이 재개됐고, 앱의 홈 이동 및 Android 홈 버튼으로 백그라운드 진입 시 녹음이 종료됐습니다. 두 메뉴 문장에 0.8초 무음을 추가했을 때 한 발화로 처리됐고, 1.6초 무음을 추가했을 때 첫 구간이 먼저 확정됐습니다. 이번 AVD에서 기록한 SenseVoice 디코딩은 43–124 ms였으며 침묵 대기, 초기 모델 로딩 및 TTS 시간은 별도입니다. 물리 마이크로 사람의 음성을 입력하는 시험은 남아 있습니다.

같은 오프라인 AVD에서 Android SpeechRecognizer 빌드는 안내 TTS 뒤에 ‘이 기기에서 한국어 음성 인식을 사용할 수 없습니다.’를 표시했습니다. 따라서 두 엔진의 정확도와 발화 종료 시간을 같은 조건으로 수치화할 수 없었습니다. 가상 마이크 재생 중 Windows QEMU가 종료되는 문제가 있었고, `-qt-hide-window -audio none`으로 부팅한 AVD에서 SenseVoice 음원 테스트를 수행했습니다.

QCS6490에서는 arm64 APK 설치와 실제 마이크 입력, 고령 화자의 느린 발화와 카페 소음, 스피커 재유입, 첫 모델 로딩 시간, 발화 후 응답 시간, 최대 메모리와 반복 주문 시 발열을 확인합니다.
## TTS 이후 최초 발화 수동 검증

`gradlew.bat assembleDebug`로 기본 SenseVoice 빌드를, `gradlew.bat -PmalloStt=android assembleDebug`로 Android SpeechRecognizer 빌드를 만듭니다. 같은 앱 ID를 사용하므로 각 빌드를 설치해 순서대로 확인합니다. 일반 Android 기기와 실제 배포 대상 Qualcomm 단말에서 반복합니다.

1. `adb logcat -v time -s MalloAudio:D MalloStt:D MalloTts:D`를 실행합니다. 테스트에 사용할 TTS 엔진과 단말, OS 버전을 기록합니다.
2. 주문 화면에 처음 진입하고 ‘듣는 중…’이 나타나는 즉시 “아메리카노 두 잔 주세요”라고 말합니다. 온도 질문 후 “차가운 걸로 주세요”, 크기 질문 후 “큰 걸로 주세요”라고 답합니다. 각 턴에서 화면과 주문 결과가 일치하는지 확인합니다.
3. “맞아요”로 상품을 확정한 뒤 추가 주문과 긴 안내 뒤의 답변을 확인합니다. “큰 걸로” 같은 짧은 발화도 같은 방법으로 확인합니다. 지원하는 기기에서 마이크 차단 및 해제, 음성이 없는 경우, 한국어 TTS를 사용할 수 없는 경우도 확인합니다.
4. TTS 또는 STT 준비·인식 중 홈으로 이동하고 새 주문을 시작합니다. 결제로 이동하고 다시 돌아오는 경우도 확인합니다. 이전 턴에서 주문이 추가되거나 인식 상태가 바뀌는지 로그의 `session`과 `turn`으로 확인합니다.
5. 엔진별로 20~30회 반복해 첫 시도 성공, 준비 중 재시도, 준비 후 무음 또는 결과 없음, 인식 결과 오해석 횟수를 기록합니다. `STT_READY`부터 첫 주문의 `STT_RESULT`까지 같은 턴인지 확인합니다.

정상 전환은 `TTS_COMPLETED` → `STT_PREPARING` → `STT_START_REQUESTED` → `STT_READY` 순서입니다. SenseVoice에서는 실제 `AUDIO_RECORDING_STARTED`와 `FIRST_AUDIO_BUFFER_RECEIVED`가 `STT_READY` 전에 나타나며, `VAD_SPEECH_DETECTED`는 음성을 감지했을 때 기록됩니다. Android SpeechRecognizer의 `STT_READY`는 `onReadyForSpeech`에 대응합니다. `STT_ERROR`에는 준비 오류, 오디오 오류, 서비스 사용 중, 무음, 결과 없음 등을 구분하는 `reason`이 나옵니다.

## Qualcomm 오디오 단독 검증

디버그 APK에 설치된 패키지 ID로 진단 화면을 엽니다. 기본 패키지의 APK와 서명이 다른 앱이 이미 설치돼 있다면 기존 서명으로 빌드한 APK 또는 별도 ID의 검증 APK를 사용합니다.

```powershell
$device = 'b18c64c6'
$package = 'com.example.cafe_kiosk_ai'
adb -s $device install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $device shell am start -n "$package/com.example.cafe_kiosk_ai.AudioDiagnosticsActivity"
adb -s $device logcat -v threadtime -s MalloMic:D MalloAudioTest:D MalloAudio:D MalloStt:D MalloTts:I MalloOfflineTts:I
```

1. **마이크 5초 녹음**에서 USB의 레이트, 소스, 읽기 모드를 선택합니다. 잠깐 조용히 있다가 마이크에 가까이 말하며 `MIC_TEST_STATS`에서 실제 입력 장치, `totalSamples`, 구간별 RMS, `peak`, `nonZero`의 변화를 확인합니다. `NO_FRAMES`는 0바이트 수신, `READ_TIMEOUT`은 블로킹 읽기의 기한 초과, `SILENT_PCM`은 전부 0인 샘플, `CONSTANT_PCM`은 일정한 샘플입니다. 변화가 있는 PCM은 `cache/mallo-microphone.wav`에 저장합니다. **녹음 재생**으로 실제 목소리가 담겼는지 확인합니다. 말하면서도 USB 입력이 실패하면 같은 마이크와 USB 카드 조합을 다른 기기에서 확인하고, Qualcomm에 정상 동작이 확인된 다른 USB 마이크를 연결해 비교합니다.
2. **스피커 테스트**에서 440 Hz 시험음이 들리는지 직접 확인합니다. `OUTPUT_FOCUS`에서 미디어 볼륨과 포커스, `OUTPUT_TEST_RESULT`에서 재생 프레임과 실제 출력 장치를 확인합니다.
3. **SenseVoice 테스트**에서 확인하려는 입력을 선택하고 `DIAGNOSTIC_STT_READY` 뒤 한국어를 말합니다. `DIAGNOSTIC_STT_RESULT`와 화면의 인식 문구를 확인합니다. 주문 화면에서는 `AUDIO_CAPTURE_PROFILE`, `AUDIO_CAPTURE_STATS`, `AUDIO_INPUT_ERROR`, `AUDIO_RECORDING_RELEASED`로 입력 구성과 재시도를 확인합니다.
4. **한국어 TTS 테스트**는 “안녕하세요. 무엇을 주문하시겠어요?”를 재생합니다. **한국어 TTS → SenseVoice**에서는 `DIAGNOSTIC_TTS_COMPLETED` 뒤 STT가 시작됩니다. 한국어 데이터 설치 후 재시작하거나 설정에서 돌아오면 앱이 엔진을 다시 조사합니다.

ADB로 단일 캡처 프로필을 반복 시험할 때는 `--es test mic --ei profile 0`을 추가합니다. 블로킹 읽기 비교에는 `--es read_mode blocking`도 추가합니다. PowerShell 7.4 이상에서는 다음 명령으로 새로 저장된 WAV를 내보낼 수 있습니다.

```powershell
adb -s $device exec-out run-as $package cat cache/mallo-microphone.wav > mallo-microphone.wav
```

`FIRST_AUDIO_BUFFER_RECEIVED`는 모델용 PCM 버퍼를 받았음을 뜻합니다. USB에서는 `STT_AWAITING_SPEECH` 안내가 나오면 말할 수 있으며, VAD가 음성을 감지한 뒤에 `STT_READY`가 표시됩니다. 첫 발화의 샘플은 이미 VAD에 전달됩니다. `AUDIO_PCM_VARIATION`은 샘플 변화, `VAD_SPEECH_DETECTED`는 음성 구간의 시작, `STT_RESULT`는 인식 결과를 뜻합니다. 음성이 감지되기 전에 대기 시간이 끝나고 PCM 값이 일정했거나 마지막 5초가 전부 0이면 `MIC_ZERO_SIGNAL`로 종료합니다. 작은 진폭 자체에 실패 임계값을 적용하지 않습니다.

### MALLO와 분리된 USB AudioRecord 검사

`usbAudioProbe`는 별도 패키지의 Android 앱입니다. Android SDK의 `AudioRecord.Builder`로 직접 PCM을 읽습니다. 아래 예제는 48 kHz, `MIC`, 논블로킹 모드이며, 진단 앱 화면에서도 비교 조건을 바꿀 수 있습니다.

```powershell
.\gradlew.bat :usbAudioProbe:assembleDebug
$device = 'b18c64c6'
adb -s $device install -r -g usbAudioProbe/build/outputs/apk/debug/usbAudioProbe-debug.apk
adb -s $device shell am force-stop com.example.cafe_kiosk_ai
adb -s $device shell am force-stop com.example.cafe_kiosk_ai.sttcheck
adb -s $device shell am start -S -n com.example.usbaudioprobe/.ProbeActivity --ez run true --ei rate 48000 --ei source 1
adb -s $device logcat -v threadtime -s UsbAudioProbe:I AudioRecord:V AudioFlinger:V AudioPolicyManager:V audio_hw_primary:V
```

- 블로킹 비교에는 실행 명령에 `--ez blocking true`를 추가합니다. 두 모드의 읽기 요청은 20ms로 동일합니다. 별도 스레드가 5초 기한에 `stop()`을 호출하고, 녹음 워커는 읽기와 중지를 마친 후 레코더를 해제합니다. USB 경로를 확인한 상태에서 중지된 블로킹 읽기가 일부 PCM을 반환하면 수신한 샘플을 포함합니다.
- `--ei rate 44100`과 `--ei source 6`으로 44.1 kHz와 `VOICE_RECOGNITION`을 비교합니다. `--ez system_route true`는 Android 기본 라우팅을 사용합니다. 이 비교의 실제 장치도 `PROBE_STARTED`와 `PROBE_READ`에서 USB인지 확인합니다.
- `PROBE_READ`는 양수 반환 구간의 RMS, peak, nonZero를 전체 누적값과 함께 기록합니다. `PROBE_RESULT`에서 `capturedMs`와 `requestedMs`를 비교하고 `positiveReads`, `zeroOnlyReads`, `firstPcmMs`, `lastPcmMs`로 한 번의 버퍼와 지속적인 입력을 구분합니다. 프레임이 있으면 전부 0이라도 원본 확인용 WAV를 저장하며, 0바이트 수신은 `NO_FRAMES`로 남깁니다. `route`는 `getRoutedDevice()`, `configRoute`와 포맷은 해당 `AudioRecordingConfiguration`의 값입니다.

Windows에서 원본 WAV를 가져오고 1초 구간별 값을 비교한 뒤 재생합니다. 앞 1초는 조용히 있고, 그다음 3초 동안 마이크 가까이에서 말하세요. 수신 길이가 5초에 가깝고 말한 구간의 RMS·peak·nonZero가 증가하는지 확인한 뒤 재생음과 대조합니다.

```powershell
.\tools\inspect-usb-probe-wav.ps1 -Device $device -OutputPath usb-capture.wav -Play
```

이미 가져온 파일에는 `-Path usb-capture.wav -Play`를 사용합니다. ADB가 PATH에 없다면 `-Adb`로 `adb.exe` 경로를 지정합니다. 48 kHz에서 960샘플만 받아 저장한 WAV는 약 20ms이며, 나머지 녹음 시간을 무음으로 채우지 않습니다.

별도 터미널에서 **녹음이 진행 중일 때** 아래 덤프를 저장합니다. 시작 전후에 `pidof audioserver`를 비교하면 서버 프로세스의 변경 여부를 확인할 수 있습니다.

```powershell
adb -s $device shell dumpsys media.audio_flinger > audio-flinger.txt
adb -s $device shell dumpsys media.audio_policy > audio-policy.txt
adb -s $device shell dumpsys audio > audio-service.txt
adb -s $device shell dumpsys usb > usb-host.txt
adb -s $device shell cat /proc/asound/cards /proc/asound/pcm
adb -s $device shell cat /proc/asound/card0/stream0
adb -s $device shell pidof audioserver
```

AudioFlinger의 `RecordThread`/활성 트랙에서 `PROBE_STARTED session=`과 같은 세션 및 HAL 프레임 위치를 찾습니다. AudioPolicy에서는 해당 입력 포트와 활성 audio patch가 `AUDIO_DEVICE_IN_USB_HEADSET`의 카드·장치로 연결되는지 봅니다. AudioService에서는 해당 패키지와 세션의 활성·음소거 상태를 봅니다. 이 상태들이 정상이어도 프레임 수와 위치가 증가하는지를 별도로 확인합니다.

`restoreRecord_l`은 네이티브 AudioRecord가 녹음 객체를 다시 여는 경로입니다. 장치 지정으로도 이 경로에 들어갈 수 있습니다. `setInputDevice`에서 발생했는지, 새 객체 생성 오류나 서버 PID 변경이 동반됐는지, Java `read()`가 `ERROR_DEAD_OBJECT(-6)`를 받았는지를 나눠 봅니다. Java에 `-6`이 전달되면 이전 객체를 해제하고 같은 프로필로 한 번 다시 엽니다. [AOSP AudioRecord 구현](https://android.googlesource.com/platform/frameworks/av/+/master/media/libaudioclient/AudioRecord.cpp) · [Android ERROR_DEAD_OBJECT](https://developer.android.com/reference/android/media/AudioRecord#ERROR_DEAD_OBJECT)

Lahaina 실측에서는 독립 앱의 48 kHz `MIC` 세션 745가 AudioPolicy에서 활성이고, USB 장치 포트 226(`card=0;device=0`)을 입력 I/O 526에 연결했습니다. AudioService는 `silenced=false`를 보고했습니다. AudioFlinger 트랙과 HAL의 `Frames read`는 모두 0이었으며 ALSA USB `Capture` 상태는 `Stop`이었습니다. 명시적 USB 지정 시 `start()`에서 `restoreRecord_l`이 발생했고, Android 기본 라우팅을 쓴 세션 809는 같은 USB에 연결되어 경고 없이 480번 연속으로 0을 반환했습니다. 두 시험 사이에 `audioserver` PID는 1069로 유지됐습니다.

후속 시험의 세션 1033은 5초 동안 16,320샘플을 받았으나 Windows로 추출한 48 kHz WAV의 0.340초가 전부 0이었습니다. 같은 시점의 AudioFlinger 스냅샷에서 RecordThread는 3,840프레임, HAL 덤프는 0프레임을 보고했습니다. 포트를 수동으로 옮긴 뒤에는 같은 48 kHz `MIC` 조건에서 논블로킹 `NO_FRAMES`, 블로킹 `READ_TIMEOUT`을 다시 확인했습니다. `ROUTE_LOST`는 캡처 중 USB 경로를 잃은 경우이며, 이때까지 확인한 USB 샘플이 있으면 부분 WAV로 보존합니다.

### Lahaina에서 ALSA는 성공하고 Android 녹음은 실패하는 경우

`RB3-Gen2-QSSI-v1_5-0.0.10` 실기기에서 GeneralPlus ALSA `card 0, device 0`을 직접 녹음한 WAV는 48 kHz mono PCM16, 241,664프레임(5.035초)이고, Windows 재생에서 실제 음성이 확인됐습니다. MALLO와 기본 녹음 앱을 종료한 뒤 일반 앱 UID로 독립 진단을 실행했습니다. 48 kHz mono PCM16에서 기본 경로와 명시적 USB 경로는 각각 논블로킹 `read()` 479회 모두 0이었고, 명시적 USB 블로킹 읽기도 5초 기한의 중지 요청 후 0을 반환했습니다. 모두 USB `card=0;device=0;`에 연결됐고 활성 세션은 `clientSilenced=false`였습니다. 명시적 지정에서만 `restoreRecord_l`이 관측됐으며, `audioserver`와 HAL 프로세스 PID는 세 시험에서 같았습니다. `stop()`은 5.3~5.4초, `release()`는 3~12ms였습니다.

기본 경로의 세션 385는 AudioFlinger I/O 190에서 활성 상태였지만 RecordThread와 HAL 모두 `Frames read: 0`이었습니다. AudioPolicy의 입력 패치는 USB Headset In 포트 41이었지만, Qualcomm primary HAL은 `hifi-record`와 `usb-headset-mic`을 적용하고 ALSA `pcmC1D1c`(`MultiMedia2`)를 열었습니다. 같은 시점에 GeneralPlus `card0/stream0`은 `Capture: Status: Stop`이었고 AudioFlinger의 `USB audio module`은 `No input streams`였습니다. DSP 프런트엔드를 여는 것 자체로 버그를 판정할 수는 없습니다. 이 보드에서는 그 경로가 활성일 때도 USB 캡처와 Android PCM이 시작되지 않았습니다.

`/vendor/etc/audio_policy_configuration.xml`의 `primary`가 USB Device In 및 USB Headset In을 소유하며 `hifi_input` 등으로 연결합니다. 같은 파일의 인라인 `usb` 모듈에는 액세서리 출력만 있습니다. 별도 `/vendor/etc/usb_audio_policy_configuration.xml`에는 `usb_device input` 및 입력 경로가 정의돼 있지만, 현재 메인 파일은 이 정의를 포함하지 않습니다. 제조사에는 먼저 primary의 `hifi-record`/`audio-record` → `usb-headset-mic` 입력 믹서, DSP/AFE 백엔드 바인딩과 캡처 시작을 점검하도록 요청합니다. 펌웨어 시험 이미지에서 별도 실험이 필요하면 기존 USB 출력 경로를 보존하고, **USB 입력만** `usb` 모듈의 `usb_device input`으로 연결합니다. primary의 중복 USB 입력 선언 및 경로를 정리한 뒤 활성 모듈과 `card=0;device=0;` 캡처 프레임을 확인합니다. [Android 오디오 정책 구성](https://source.android.com/docs/core/audio/implement-policy) · [Android USB 오디오](https://source.android.com/docs/core/audio/usb)

```powershell
adb -s $device shell cmd appops get com.example.cafe_kiosk_ai RECORD_AUDIO
adb -s $device shell cmd appops get com.example.usbaudioprobe RECORD_AUDIO
adb -s $device shell dumpsys audio
adb -s $device shell dumpsys sensor_privacy
adb -s $device shell settings get secure usb_audio_automatic_routing_disabled
adb -s $device shell am start -S -n com.example.usbaudioprobe/.ProbeActivity --ez run true --ei rate 48000 --ei source 1 --ei usb_card 0 --ei usb_device 0
```

주소 지정은 Android 9 이상에서 사용하며 정확히 일치하는 USB 입력만 선택합니다. 카드 번호는 재연결 시 바뀔 수 있으므로 `/proc/asound/pcm`과 AudioPolicy의 주소를 먼저 확인합니다. 앞 절의 세 가지 `dumpsys`와 `/proc/asound/card0/stream0`을 녹음 중 다시 수집합니다. 권한이 있는 OEM 진단 쉘에서는 오디오 HAL 프로세스의 `/proc/<pid>/fd`에 열려 있는 `/dev/snd` 노드도 대조할 수 있습니다. 수정된 펌웨어에서 두 읽기 모드 모두 프레임이 계속 늘고 WAV에 발화가 담긴 뒤, MALLO에서 `FIRST_AUDIO_BUFFER_RECEIVED`, `AUDIO_PCM_VARIATION`, `VAD_SPEECH_DETECTED`, `STT_READY`와 한국어 주문 발화 인식을 확인합니다.

### 녹음 시작·취소·해제 지연 확인

제품 SenseVoice 경로는 논블로킹 읽기를 사용합니다. 워커가 이전 녹음을 해제하고 새 턴의 작업을 시작한 시점에 `STT_PREPARATION_STARTED`와 12초 준비 타이머가 시작됩니다. 작업이 대기 중인 상태로 12초를 넘기면 `STT_CAPTURE_QUEUE_TIMEOUT`과 `MIC_CLEANUP_TIMEOUT`을 기록하고 자동 재시도를 종료합니다. 음성 없는 USB 입력은 `STT_READY`로 전환하지 않습니다.

```powershell
adb -s $device logcat -v threadtime -s MalloAudio:D MalloStt:D UsbAudioProbe:I AudioRecord:V AudioFlinger:V AudioPolicyManager:V audio_hw_primary:V
adb -s $device logcat -b events -d -s am_anr
```

1. MALLO에서 주문을 시작한 뒤 `AUDIO_READ_STARTED`를 확인하고 홈으로 이동합니다. `AUDIO_CANCEL_REQUESTED` → `AUDIO_RECORDING_STOP_BEGIN` → `AUDIO_RECORDING_STOP_END` → `AUDIO_RECORDING_RELEASE_BEGIN` → `AUDIO_RECORDING_RELEASED`의 간격과 스레드 ID를 확인합니다. 다음 주문의 `STT_PREPARATION_STARTED`는 이전 녹음의 `AUDIO_RECORDING_RELEASED` 뒤에 나와야 합니다.
2. 독립 앱의 블로킹 시험에서 `PROBE_STOP cause=READ_DEADLINE`, `PROBE_FIRST_READ_RETURNED`, `PROBE_STOP_END`, `PROBE_RELEASED`를 비교합니다. 첫 읽기부터 중지 요청까지의 대기, 중지 호출 자체의 지연, 해제 지연을 각각 분리할 수 있습니다.
3. 제조사에 전달할 자료에는 보드 빌드 지문(`adb -s $device shell getprop ro.build.fingerprint`), USB VID:PID와 포트, 입력 형식, 세션·I/O 번호, 녹음 중 AudioFlinger/HAL 프레임 카운터, AudioPolicy 입력 패치, USB Host 로그, 5초 진단 결과 및 중지·해제 시간대를 포함합니다. Windows에서 녹음이 되는 동일 장치 조합과 Qualcomm에서 다른 정상 USB 녹음 장치를 비교한 결과도 함께 전달합니다.

최신 실측에서 48 kHz `MIC` 독립 앱은 논블로킹으로 478회 모두 0샘플을 받았습니다. 블로킹의 첫 읽기는 5초 기한에 별도 스레드가 중지를 요청하자 0을 반환했습니다. 해당 `stop()`은 5,217ms, `release()`는 10ms였으며, 활성 세션 1265의 AudioFlinger와 HAL 프레임 카운터는 모두 0이었습니다. 최종 MALLO의 화면 이동 취소 시험에서는 워커가 취소 직후 중지를 시작했고, `stop()` 약 9.8초와 해제 4ms 뒤에 다음 턴을 준비했습니다. 같은 실기기의 USB 출력에서는 시험음 48,000프레임 재생을 확인했습니다.

기본 SenseVoice 빌드는 Google의 설치된 로컬 한국어 음성을 우선 사용하며, 적합한 음성이 없으면 앱에 포함된 Supertonic 3 한국어 합성을 사용할 수 있습니다. 첫 Gradle 빌드에서 해시가 고정된 공식 아카이브를 `app/offline-tts/downloads/`에 받아 APK에 포함합니다. 변환 아카이브의 MIT 고지와 원본 모델의 BigScience Open RAIL-M 라이선스 사본을 APK의 `assets/tts/`에 포함합니다. [공식 모델 아카이브](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2) · [원본 모델 라이선스](https://huggingface.co/Supertone/supertonic-3/blob/3cadd1ee6394adea1bd021217a0e650ede09a323/LICENSE)
