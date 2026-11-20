package com.example.cafe_kiosk_ai

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.media.AudioFormat
import android.os.Build
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/** Android owns the playback queue. The service lazily keeps one synthesizer for subsequent requests. */
class BundledKoreanTtsService : TextToSpeechService() {
    private val cancellation = AtomicLong()
    private val modelLock = Any()
    private val maintenance = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var model: OfflineTts? = null
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(ActivityManager::class.java)
        if (manager != null) {
            maintenance.scheduleWithFixedDelay({
                if (model != null) {
                    val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
                    if (memory.lowMemory || memory.availMem < IDLE_MODEL_AVAILABLE_BYTES) releaseModel("low_memory")
                }
            }, 2, 2, TimeUnit.SECONDS)
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!destroyed && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            maintenance.execute { releaseModel("trim_memory") }
        }
    }

    override fun onDestroy() {
        destroyed = true
        cancellation.incrementAndGet()
        maintenance.execute { releaseModel("service_destroyed") }
        maintenance.shutdown()
        super.onDestroy()
    }

    private fun releaseModel(reason: String) = synchronized(modelLock) {
        val previous = model ?: return@synchronized
        model = null
        runCatching { previous.release() }
            .onSuccess { Log.i(TAG, "OFFLINE_TTS_MODEL_RELEASED reason=$reason") }
            .onFailure { Log.w(TAG, "Could not release the TTS model ($reason)", it) }
    }

    private fun createModel(): OfflineTts {
        val prepareStarted = SystemClock.elapsedRealtime()
        val files = try {
            BundledTtsModelFiles.prepare(this)
        } catch (error: IOException) {
            Log.w(TAG, "TTS file preparation failed; using bundled assets", error)
            null
        }
        val started = SystemClock.elapsedRealtime()
        return createBundledTts(this, files).also {
            val source = if (files == null) "asset" else "file"
            Log.i(TAG, "OFFLINE_TTS_MODEL_READY model=supertonic-3-int8 source=$source " +
                "prepareMs=${started - prepareStarted} elapsedMs=${SystemClock.elapsedRealtime() - started}")
        }
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
            val lease = ModelMemoryLease.acquire(cacheDir, "TTS") { destroyed || token != cancellation.get() } ?: return
            lease.use {
                synchronized(modelLock) {
                    if (destroyed || token != cancellation.get()) return
                    val prepareStarted = SystemClock.elapsedRealtime()
                    val existing = model
                    val synthesizer = existing ?: createModel().also { model = it }
                    val reuse = existing != null
                    Log.i(TAG, "OFFLINE_TTS_MODEL_USED reused=$reuse prepareMs=${SystemClock.elapsedRealtime() - prepareStarted}")
                    var reusable = false
                    try {
                        val result = synthesize(synthesizer, request, callback, token)
                        reusable = true
                        if (result == null) return
                        Log.i(TAG, "OFFLINE_TTS_SYNTHESIZED lang=ko sampleRate=${result.first} samples=${result.second} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    } finally {
                        if (!reusable) releaseModel("synthesis_error")
                    }
                }
            }
            if (token == cancellation.get()) callback.done()
        } catch (error: Exception) {
            synthesisError(token, callback, error)
        } catch (error: LinkageError) {
            synthesisError(token, callback, error)
        }
    }

    private fun synthesize(
        synthesizer: OfflineTts,
        request: SynthesisRequest,
        callback: SynthesisCallback,
        token: Long
    ): Pair<Int, Int>? {
        if (token != cancellation.get()) return null
        val rate = synthesizer.sampleRate()
        if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return null
        val buffer = ByteArray(callback.maxBufferSize)
        var total = 0
        var failed = false
        val benchmarkSteps = if (BuildConfig.DEBUG) {
            when (val steps = request.params.getInt("$packageName.ttsBenchmarkSteps")) {
                5, 6, 8 -> steps
                else -> null
            }
        } else null
        val extras = if (benchmarkSteps == null) mapOf("lang" to "ko") else {
            val seed = request.params.getInt("$packageName.ttsBenchmarkSeed").toString()
            Log.i(TAG, "OFFLINE_TTS_BENCHMARK steps=$benchmarkSteps seed=$seed")
            mapOf("lang" to "ko", "seed" to seed)
        }
        val config = GenerationConfig(
            speed = (request.speechRate / 100f).coerceIn(0.5f, 2f),
            numSteps = benchmarkSteps ?: 5, extra = extras
        )
        Log.i(TAG, "OFFLINE_TTS_GENERATE steps=${config.numSteps}")
        var onsetMarked = false
        fun send(samples: FloatArray): Int {
            if (benchmarkSteps != null && !onsetMarked && Build.VERSION.SDK_INT >= 26) {
                // 0.01 of full scale is -40 dBFS. Mark the first frame above that level.
                val onset = samples.indexOfFirst { abs(it) >= 0.01f }
                if (onset >= 0) {
                    callback.rangeStart(total + onset, 0, request.charSequenceText.length)
                    onsetMarked = true
                }
            }
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
        if (token != cancellation.get()) return null
        if (failed || total == 0) {
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return null
        }
        return rate to total
    }

    private fun synthesisError(token: Long, callback: SynthesisCallback, error: Throwable) {
        Log.e(TAG, "OFFLINE_TTS_ERROR", error)
        if (token == cancellation.get()) callback.error(TextToSpeech.ERROR_SYNTHESIS)
    }

    private companion object { const val TAG = "MalloOfflineTts" }
}
