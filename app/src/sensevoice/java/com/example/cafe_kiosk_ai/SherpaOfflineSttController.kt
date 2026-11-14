package com.example.cafe_kiosk_ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import java.util.concurrent.Executors

internal data class OfflineSttConfig(
    val threshold: Float = 0.5f,
    val minSpeechSeconds: Float = 0.16f,
    val minSilenceSeconds: Float = 1.2f,
    val maxSpeechSeconds: Float = 15f,
    val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
    private val config: OfflineSttConfig = OfflineSttConfig(),
    private val onEvent: (VoiceRecognitionEvent) -> Unit
) : VoiceController {
    private val assets = context.applicationContext.assets
    private val main = Handler(Looper.getMainLooper())
    private var currentTurn: Turn? = null
    private var released = false

    // Models and native streams belong to the worker. They are reused during one conversation.
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null

    override fun startListening() {
        if (released || currentTurn != null) return
        val turn = Turn()
        currentTurn = turn
        worker.execute { recognize(turn) }
    }

    override fun stopListening() {
        val turn = currentTurn ?: return
        currentTurn = null
        turn.cancel()
    }

    override fun release() {
        if (released) return
        released = true
        stopListening()
        worker.execute {
            vad?.release()
            vad = null
            recognizer?.release()
            recognizer = null
        }
    }

    private fun recognize(turn: Turn) {
        if (turn.cancelled) return
        val result = try {
            val detector = vad ?: createVad().also { vad = it }
            val decoder = recognizer ?: createRecognizer().also { recognizer = it }
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

    @SuppressLint("MissingPermission") // MainActivity requests RECORD_AUDIO before starting a turn.
    private fun capture(turn: Turn, detector: Vad): FloatArray? {
        val channels = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, channels, format)
        check(minBufferBytes > 0) { "16 kHz mono PCM input is unavailable ($minBufferBytes)" }
        val record = AudioRecord(
            config.audioSource, SAMPLE_RATE, channels, format, maxOf(minBufferBytes, WINDOW_SIZE * 4)
        )
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize AudioRecord" }
            if (!turn.startRecording(record)) return null
            Log.d(TAG, "Offline microphone started")
            post(turn, VoiceRecognitionEvent.Listening)
            return readUtterance(turn, record, detector)
        } finally {
            turn.releaseRecording(record)
        }
    }

    private fun readUtterance(turn: Turn, record: AudioRecord, detector: Vad): FloatArray? {
        val pcm = ShortArray(WINDOW_SIZE)
        val window = FloatArray(WINDOW_SIZE)
        val idleLimit = SAMPLE_RATE * IDLE_SECONDS
        val speechLimit = (SAMPLE_RATE * config.maxSpeechSeconds).toInt()
        var filled = 0
        var received = 0
        var speechStart = 0
        while (!turn.cancelled) {
            val count = record.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
            if (turn.cancelled) return null
            check(count > 0) { "AudioRecord read failed ($count)" }
            filled += count
            if (filled < pcm.size) continue
            filled = 0
            for (i in pcm.indices) window[i] = pcm[i] / 32768.0f
            detector.acceptWaveform(window)
            received += window.size
            if (!detector.empty()) {
                Log.d(TAG, "Silero endpoint after silence")
                return detector.front().samples
            }
            if (speechStart == 0 && detector.isSpeechDetected()) {
                speechStart = received
                Log.d(TAG, "Silero speech detected")
            }
            if (speechStart == 0 && received >= idleLimit) return FloatArray(0)
            if (speechStart != 0 && received - speechStart >= speechLimit) {
                // sherpa's maxSpeechDuration changes VAD sensitivity. Flush also caps continuous speech.
                Log.d(TAG, "Silero endpoint at speech limit")
                detector.flush()
                return if (detector.empty()) FloatArray(0) else detector.front().samples
            }
        }
        return null
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
            VoiceRecognitionEvent.Error(VoiceRecognitionError.NO_SPEECH)
        } else {
            VoiceRecognitionEvent.Result(text)
        }
    }

    private fun post(turn: Turn, event: VoiceRecognitionEvent, terminal: Boolean = false) {
        main.post deliver@{
            if (currentTurn !== turn) return@deliver
            if (terminal) currentTurn = null
            if (BuildConfig.DEBUG && event is VoiceRecognitionEvent.Result) {
                Log.d(TAG, "SenseVoice final: ${event.text}")
            }
            onEvent(event)
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

    private class Turn {
        @Volatile
        var cancelled = false
            private set
        private var recording: AudioRecord? = null

        @Synchronized
        fun startRecording(record: AudioRecord): Boolean {
            if (cancelled) return false
            record.startRecording()
            recording = record
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            return true
        }

        @Synchronized
        fun cancel() {
            cancelled = true
            stopRecording()
        }

        @Synchronized
        fun releaseRecording(record: AudioRecord) {
            try {
                stopRecording()
            } finally {
                record.release()
            }
        }

        private fun stopRecording() {
            val active = recording ?: return
            recording = null
            if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                active.stop()
                Log.d(TAG, "Offline microphone stopped")
            }
        }
    }

    private companion object {
        const val TAG = "MalloStt"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
        const val IDLE_SECONDS = 30

        // Serializes a retiring controller's cleanup with its replacement after navigation.
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "MalloOfflineStt") }
    }
}
