package com.example.cafe_kiosk_ai

import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

internal data class OfflineSttConfig(
    val threshold: Float = 0.5f,
    val minSpeechSeconds: Float = 0.16f,
    val minSilenceSeconds: Float = 1.2f,
    val maxSpeechSeconds: Float = 15f,
    val useItn: Boolean = true
) {
    init {
        require(threshold in 0.01f..0.99f)
        require(minSpeechSeconds in 0.032f..1f)
        require(minSilenceSeconds in 0.032f..5f)
        require(maxSpeechSeconds > minSpeechSeconds + minSilenceSeconds && maxSpeechSeconds <= 30f)
    }
}

@MainThread
internal class SherpaOfflineSttController(
    context: Context,
    private val sessionId: String?,
    private val config: OfflineSttConfig = OfflineSttConfig(),
    private val profileOverride: CaptureProfile? = null,
    private val onEvent: (Long, VoiceRecognitionEvent) -> Unit
) : VoiceController {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val main = Handler(Looper.getMainLooper())
    private var currentTurn: Turn? = null
    private var released = false

    // Models and native streams belong to the worker. They are reused during one conversation.
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private var captureProfiles = emptyList<CaptureProfile>()
    private var captureProfileIndex = 0
    private var deadObjectRetryProfile: CaptureProfile? = null

    override fun startListening(turnId: Long) {
        if (released || currentTurn != null) return
        val turn = Turn(turnId)
        currentTurn = turn
        microphoneWorker.execute { recognize(turn) }
    }

    override fun stopListening() {
        val turn = currentTurn ?: return
        currentTurn = null
        turn.cancelled = true
        trace(turn, "STOPPED", "AUDIO_CANCEL_REQUESTED")
    }

    override fun release() {
        if (released) return
        released = true
        stopListening()
        microphoneWorker.execute {
            vad?.release()
            vad = null
            recognizer?.release()
            recognizer = null
        }
    }

    private fun recognize(turn: Turn) {
        if (turn.cancelled) return
        post(turn, VoiceRecognitionEvent.PreparationStarted)
        val result = try {
            val detector = vad ?: createVad().also { vad = it; trace(turn, "PREPARING", "VAD_INITIALIZED") }
            val decoder = recognizer ?: createRecognizer().also { recognizer = it; trace(turn, "PREPARING", "STT_MODEL_INITIALIZED") }
            if (turn.cancelled) return
            detector.reset()
            val samples = capture(turn, detector) ?: return
            if (turn.cancelled) return
            if (samples.isEmpty()) {
                VoiceRecognitionEvent.Error(VoiceRecognitionError.NO_SPEECH)
            } else {
                post(turn, VoiceRecognitionEvent.Processing)
                decode(decoder, samples)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Microphone permission denied", error)
            VoiceRecognitionEvent.Error(VoiceRecognitionError.PERMISSION)
        } catch (error: LinkageError) {
            Log.e(TAG, "Could not load sherpa-onnx JNI", error)
            VoiceRecognitionEvent.Error(VoiceRecognitionError.UNAVAILABLE)
        } catch (error: RuntimeException) {
            if (turn.cancelled) return
            Log.e(TAG, "Offline recognition failed", error)
            VoiceRecognitionEvent.Error(VoiceRecognitionError.SERVICE)
        }
        post(turn, result, terminal = true)
    }

    private fun capture(turn: Turn, detector: Vad): FloatArray? {
        val profiles = profileOverride?.let(::listOf) ?: microphoneProfiles(appContext)
        if (profiles != captureProfiles) {
            captureProfiles = profiles
            deadObjectRetryProfile = null
            val previous = lastWorkingCapture?.takeIf { it.first == profiles }?.second
            captureProfileIndex = profiles.indexOf(previous).coerceAtLeast(0)
        }
        val profile = profiles[captureProfileIndex]
        trace(turn, "PREPARING", "AUDIO_CAPTURE_PROFILE", "${captureProfileIndex + 1}/${profiles.size} $profile")
        var capture: PcmCapture? = null
        try {
            capture = PcmCapture.open(appContext, profile)
            if (turn.cancelled) return null
            trace(turn, "PREPARING", "AUDIO_RECORDING_START_BEGIN")
            trace(turn, "PREPARING", "AUDIO_RECORDING_STARTED", capture.start())
            return readUtterance(turn, capture, detector)
        } catch (error: AudioInputException) {
            if (!turn.cancelled) {
                if (error.readCode == AudioRecord.ERROR_DEAD_OBJECT && deadObjectRetryProfile != profile) {
                    deadObjectRetryProfile = profile
                    trace(turn, "PREPARING", "AUDIO_RECORD_RECREATE_NEXT_TURN", profile.toString())
                } else {
                    deadObjectRetryProfile = null
                    captureProfileIndex = (captureProfileIndex + 1) % profiles.size
                }
            }
            if (profileOverride == null && lastWorkingCapture == (profiles to profile)) lastWorkingCapture = null
            Log.w(TAG, error.message.orEmpty(), error.cause)
            trace(turn, if (turn.receivedAudio) "LISTENING" else "PREPARING", "AUDIO_INPUT_ERROR", error.message.orEmpty())
            // Surface the failure while this worker shuts down the old recorder. Retries queue on this worker.
            if (!turn.cancelled) {
                val reason = error.reason ?: if (turn.receivedAudio) VoiceRecognitionError.AUDIO
                else VoiceRecognitionError.PREPARATION
                post(turn, VoiceRecognitionEvent.Error(reason), terminal = true)
            }
            return null
        } finally {
            capture?.closeWithTrace { event ->
                trace(turn, "STOPPED", event, "cancelled=${turn.cancelled}")
            }
        }
    }

    private fun readUtterance(turn: Turn, capture: PcmCapture, detector: Vad): FloatArray? {
        val inputRate = capture.record.sampleRate
        val resampler = PcmResampler(inputRate)
        val pcm = ShortArray(maxOf(WINDOW_SIZE, inputRate / 32))
        val converted = FloatArray(pcm.size)
        val window = FloatArray(WINDOW_SIZE)
        val idleLimit = SAMPLE_RATE * IDLE_SECONDS
        val zeroSignalSamples = inputRate * 5
        val emulator = AndroidRuntimeEnvironment.isEmulator
        val speechLimit = (SAMPLE_RATE * config.maxSpeechSeconds).toInt()
        var filled = 0
        var received = 0
        var speechStart = 0
        val levels = PcmLevels()
        var interval = PcmLevels()
        var zeroReported = false
        var signalReported = false
        var lastInputAt = SystemClock.elapsedRealtime()
        val preparationStarted = lastInputAt
        var nextStatsAt = lastInputAt

        val readMode = CaptureReadMode.READ_NON_BLOCKING
        trace(turn, "PREPARING", "AUDIO_READ_STARTED", "mode=$readMode requestSamples=${pcm.size}")
        var firstRead = true
        while (!turn.cancelled) {
            val count = capture.read(pcm, levels.samples, readMode)
            if (firstRead) {
                trace(turn, "PREPARING", "AUDIO_FIRST_READ_RETURNED", "readSamples=$count")
                firstRead = false
            }
            if (turn.cancelled) return null
            val now = SystemClock.elapsedRealtime()
            levels.add(pcm, count)
            if (BuildConfig.DEBUG) interval.add(pcm, count)
            if (BuildConfig.DEBUG && (now >= nextStatsAt || (count > 0 && levels.samples == count))) {
                trace(turn, if (turn.receivedAudio) "LISTENING" else "PREPARING", "AUDIO_CAPTURE_STATS",
                    "readSamples=$count totalSamples=${levels.samples} window=$interval ${capture.routeStatus()}")
                interval = PcmLevels()
                nextStatsAt = now + 1_000L
            }
            if (count == 0) {
                if (!turn.receivedAudio && now - preparationStarted >= FIRST_BUFFER_MS) {
                    throw AudioInputException("No complete PCM buffer in $FIRST_BUFFER_MS ms; ${capture.profile}; " +
                            "$levels ${capture.routeStatus()}", reason = VoiceRecognitionError.MIC_NO_DATA)
                }
                if (turn.receivedAudio && now - lastInputAt >= INPUT_STALL_MS) {
                    throw AudioInputException("PCM stopped for $INPUT_STALL_MS ms; $levels ${capture.profile}",
                        reason = VoiceRecognitionError.MIC_NO_DATA)
                }
                SystemClock.sleep(READ_POLL_MS)
                continue
            }
            lastInputAt = now
            val modelsSamples = resampler.convert(pcm, count, converted)
            for (i in 0 until modelsSamples) {
                window[filled++] = converted[i]
                if (filled < window.size) continue
                filled = 0
                if (!turn.receivedAudio) {
                    capture.verifyRoute()
                    turn.receivedAudio = true
                    trace(turn, "PREPARING", "FIRST_AUDIO_BUFFER_RECEIVED",
                        "captureSamples=${levels.samples} modelSamples=${window.size} captureRate=$inputRate")
                    if (emulator || capture.record.routedDevice?.isUsbInput == true) {
                        post(turn, VoiceRecognitionEvent.AwaitingSpeech)
                    } else {
                        markListening(turn)
                    }
                }
                if (!signalReported && levels.varies) {
                    signalReported = true
                    trace(turn, "LISTENING", "AUDIO_PCM_VARIATION", levels.toString())
                }
                if (!zeroReported && levels.trailingZeros >= zeroSignalSamples) {
                    zeroReported = true
                    trace(turn, "LISTENING", "AUDIO_PCM_ZERO_ONLY", "$levels ${capture.routeStatus()}")
                }
                // Android's virtual microphone can supply low-level PCM even without host speech.
                if (emulator && speechStart == 0 && levels.samples >= zeroSignalSamples &&
                    (levels.peak ?: 0) <= 16) {
                    throw AudioInputException("Virtual microphone has no usable signal after five seconds: $levels",
                        reason = VoiceRecognitionError.MIC_ZERO_SIGNAL)
                }
                detector.acceptWaveform(window)
                received += window.size
                if (speechStart == 0 && levels.varies && detector.isSpeechDetected()) {
                    speechStart = received
                    trace(turn, "LISTENING", "VAD_SPEECH_DETECTED")
                    markListening(turn)
                }
                if (!detector.empty() && levels.varies) {
                    markListening(turn)
                    return detector.front().samples.also { confirmCapture(capture, levels) }
                }
                if (speechStart == 0 && received >= idleLimit) {
                    if (!levels.varies || levels.trailingZeros >= zeroSignalSamples) {
                        throw AudioInputException("PCM stayed constant or ended with sustained zero samples; $levels ${capture.routeStatus()}",
                            reason = VoiceRecognitionError.MIC_ZERO_SIGNAL)
                    }
                    trace(turn, "LISTENING", "VAD_NO_SEGMENT")
                    return FloatArray(0)
                }
                if (speechStart != 0 && received - speechStart >= speechLimit) {
                    Log.d(TAG, "Silero endpoint at speech limit")
                    detector.flush()
                    return if (detector.empty()) FloatArray(0) else
                        detector.front().samples.also { confirmCapture(capture, levels) }
                }
            }
        }
        return null
    }

    private fun confirmCapture(capture: PcmCapture, levels: PcmLevels) {
        capture.verifyRoute()
        deadObjectRetryProfile = null
        if (profileOverride == null && levels.varies) lastWorkingCapture = captureProfiles to capture.profile
    }

    private fun markListening(turn: Turn) {
        if (turn.readyPosted) return
        turn.readyPosted = true
        post(turn, VoiceRecognitionEvent.Listening)
    }

    private fun decode(decoder: OfflineRecognizer, samples: FloatArray): VoiceRecognitionEvent {
        val started = SystemClock.elapsedRealtime()
        val stream = decoder.createStream()
        val text = try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            decoder.decode(stream)
            decoder.getResult(stream).text
        } finally {
            stream.release()
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.d(TAG, "SenseVoice decode: ${samples.size * 1000L / SAMPLE_RATE} ms audio, $elapsed ms CPU inference")
        return if (text.isBlank()) {
            VoiceRecognitionEvent.Error(VoiceRecognitionError.NO_RESULT)
        } else {
            VoiceRecognitionEvent.Result(text)
        }
    }

    private fun post(turn: Turn, event: VoiceRecognitionEvent, terminal: Boolean = false) {
        main.post deliver@{
            if (currentTurn !== turn) return@deliver
            if (terminal) currentTurn = null
            onEvent(turn.id, event)
        }
    }

    private fun createVad() = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "stt/silero_vad.onnx",
                threshold = config.threshold,
                minSilenceDuration = config.minSilenceSeconds,
                minSpeechDuration = config.minSpeechSeconds,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = config.maxSpeechSeconds
            ),
            sampleRate = SAMPLE_RATE
        )
    )

    private fun createRecognizer() = OfflineRecognizer(
        assetManager = assets,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "stt/model.int8.onnx",
                    language = "ko",
                    useInverseTextNormalization = config.useItn
                ),
                tokens = "stt/tokens.txt",
                numThreads = 2,
                provider = "cpu"
            )
        )
    )

    private fun trace(turn: Turn, state: String, event: String, detail: String = "") =
        audioLog(voiceEngineName, sessionId, turn.id, state, event, detail)

    private class Turn(val id: Long) {
        var receivedAudio = false
        var readyPosted = false
        @Volatile
        var cancelled = false
    }

    private companion object {
        const val TAG = "MalloStt"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
        const val IDLE_SECONDS = 30
        const val READ_POLL_MS = 10L
        const val INPUT_STALL_MS = 3_000L
        const val FIRST_BUFFER_MS = 2_500L
        // Accessed only by microphoneWorker. A changed device list starts selection again.
        private var lastWorkingCapture: Pair<List<CaptureProfile>, CaptureProfile>? = null
    }
}
