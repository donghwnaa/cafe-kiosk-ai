package com.example.cafe_kiosk_ai

import android.media.AudioFormat
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Android owns the playback queue. The model is loaded lazily on its synthesis thread. */
class BundledKoreanTtsService : TextToSpeechService() {
    private val cancellation = AtomicLong()
    private fun createModel(): OfflineTts {
        val started = SystemClock.elapsedRealtime()
        return OfflineTts(
            assetManager = assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = "tts/duration_predictor.int8.onnx",
                        textEncoder = "tts/text_encoder.int8.onnx",
                        vectorEstimator = "tts/vector_estimator.int8.onnx",
                        vocoder = "tts/vocoder.int8.onnx",
                        ttsJson = "tts/tts.json",
                        unicodeIndexer = "tts/unicode_indexer.bin",
                        voiceStyle = "tts/voice.bin"
                    ),
                    provider = "cpu",
                    numThreads = 2
                )
            )
        ).also { Log.i(TAG, "OFFLINE_TTS_MODEL_READY model=supertonic-3-int8 elapsedMs=${SystemClock.elapsedRealtime() - started}") }
    }

    override fun onGetLanguage() = arrayOf("kor", "KOR", "")

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int {
        if (lang != "kor" && lang != "ko") return TextToSpeech.LANG_NOT_SUPPORTED
        return if (country == "KOR" || country == "KR") TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onStop() {
        cancellation.incrementAndGet()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val token = cancellation.get()
        if (onIsLanguageAvailable(request.language, request.country, request.variant) < TextToSpeech.LANG_AVAILABLE) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val started = SystemClock.elapsedRealtime()
        try {
            val lease = ModelMemoryLease.acquire(cacheDir, "TTS") { token != cancellation.get() } ?: return
            lease.use {
                val synthesizer = createModel()
                try {
                    if (token != cancellation.get()) return
                    val rate = synthesizer.sampleRate()
                    if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
                    val buffer = ByteArray(callback.maxBufferSize)
                    var total = 0
                    var failed = false
                    val config = GenerationConfig(speed = (request.speechRate / 100f).coerceIn(0.5f, 2f),
                        numSteps = 8, extra = mapOf("lang" to "ko"))
                    fun send(samples: FloatArray): Int {
                        var offset = 0
                        while (offset < samples.size && token == cancellation.get()) {
                            val count = minOf(samples.size - offset, buffer.size / 2)
                            for (i in 0 until count) {
                                val sample = (samples[offset + i].coerceIn(-1f, 1f) * 32767).roundToInt()
                                buffer[i * 2] = sample.toByte()
                                buffer[i * 2 + 1] = (sample shr 8).toByte()
                            }
                            if (callback.audioAvailable(buffer, 0, count * 2) != TextToSpeech.SUCCESS) {
                                failed = true
                                return 0
                            }
                            offset += count
                            total += count
                        }
                        return if (token == cancellation.get()) 1 else 0
                    }
                    val generated = synthesizer.generateWithConfigAndCallback(request.charSequenceText.toString(), config, ::send)
                    if (!failed && total == 0 && token == cancellation.get()) send(generated.samples)
                    if (token != cancellation.get()) return
                    if (failed || total == 0) {
                        callback.error(TextToSpeech.ERROR_SYNTHESIS)
                        return
                    }
                    Log.i(TAG, "OFFLINE_TTS_SYNTHESIZED lang=ko sampleRate=$rate samples=$total elapsedMs=${SystemClock.elapsedRealtime() - started}")
                } finally {
                    synthesizer.release()
                }
            }
            callback.done()
        } catch (error: RuntimeException) {
            synthesisError(token, callback, error)
        } catch (error: LinkageError) {
            synthesisError(token, callback, error)
        }
    }

    private fun synthesisError(token: Long, callback: SynthesisCallback, error: Throwable) {
        Log.e(TAG, "OFFLINE_TTS_ERROR", error)
        if (token == cancellation.get()) callback.error(TextToSpeech.ERROR_SYNTHESIS)
    }

    private companion object { const val TAG = "MalloOfflineTts" }
}
