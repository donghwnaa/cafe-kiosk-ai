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
| 주변 소음 | `VOICE_RECOGNITION` 오디오 소스와 Silero 확률 판정을 사용합니다. 소스별 입력 특성은 기기에 따라 달라집니다. `OfflineSttConfig`에서 오디오 소스, 민감도, 발화 시작 및 종료 기준을 조정할 수 있습니다. |
| SenseVoice | `language=ko`, `useInverseTextNormalization=true`, CPU 2스레드 |

Silero와 SenseVoice는 APK에 포함된 파일을 읽어 기기 CPU에서 실행합니다. 녹음은 발화가 끝나면 닫히고 SenseVoice 결과는 발화당 한 번 전달됩니다. Android TTS는 기존 엔진을 사용하며 오프라인 재생에는 로컬 한국어 음성이 필요합니다. [Android VAD + 비스트리밍 STT 예제](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1/android/SherpaOnnxVadAsr) · [Silero 모델과 예제](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) · [SenseVoice 모델과 ITN 설정](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)

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
