package com.example.cafe_kiosk_ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsStepBenchmarkTest {
    @Test
    fun compareBundledKoreanVoiceSteps() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("benchmarkTtsSteps") == "true")
        assumeTrue(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 26)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "tts-step-benchmark").apply { mkdirs() }
        val genie = BoardGenieLlmEngine(context.cacheDir)
        val results = JSONArray()
        try {
            runBlocking {
                genie.preload()
                assertTrue("SenseVoice did not preload", preloadVoiceRecognizer(context))
            }
            val ready = CountDownLatch(1)
            val status = AtomicInteger(TextToSpeech.ERROR)
            lateinit var engine: TextToSpeech
            instrumentation.runOnMainSync {
                engine = TextToSpeech(context, {
                    status.set(it)
                    ready.countDown()
                }, context.packageName)
            }
            try {
                assertTrue("TTS initialization timed out", ready.await(15, TimeUnit.SECONDS))
                assertEquals(TextToSpeech.SUCCESS, status.get())
                val voice = engine.voices.single {
                    it.name == "ko-KR" && it.locale == Locale.KOREA && !it.isNetworkConnectionRequired
                }
                val config = TtsConfig()
                assertEquals(TextToSpeech.SUCCESS, engine.setVoice(voice))
                assertEquals(TextToSpeech.SUCCESS, engine.setSpeechRate(config.speechRate))
                assertEquals(TextToSpeech.SUCCESS, engine.setPitch(config.pitch))
                val audio = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                assertEquals(TextToSpeech.SUCCESS, engine.setAudioAttributes(audio))
                measure(engine, context.packageName, folder, 0, 8, 42)
                val rotations = listOf(listOf(8, 6, 5), listOf(6, 5, 8), listOf(5, 8, 6))
                val seeds = listOf(314, 159, 265)
                for ((index, rotation) in rotations.withIndex()) {
                    for (steps in rotation) {
                        results.put(measure(engine, context.packageName, folder, index + 1, steps, seeds[index]))
                    }
                }
            } finally {
                engine.shutdown()
            }
            ResidentSenseVoiceRecognizer.use(context, useItn = true) { recognizer, _ ->
                for (index in 0 until results.length()) {
                    val result = results.getJSONObject(index)
                    val wave = WaveReader.readWave(File(folder, result.getString("file")).path)
                    val pcm = ShortArray(wave.samples.size) { sample ->
                        (wave.samples[sample] * 32768).roundToInt().coerceIn(-32768, 32767).toShort()
                    }
                    val samples = FloatArray(pcm.size)
                    val count = PcmResampler(wave.sampleRate).convert(pcm, pcm.size, samples)
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples.copyOf(count), SENSEVOICE_SAMPLE_RATE)
                        recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text
                        assertTrue("SenseVoice returned an empty transcription", text.isNotBlank())
                        result.put("transcript", text)
                    } finally {
                        stream.release()
                    }
                }
            }
            File(folder, "trials.json").writeText(JSONObject()
                .put("text", TEXT)
                .put("voice", "ko-KR")
                .put("trials", results)
                .toString(2))
        } finally {
            runBlocking {
                releaseIdleVoiceRecognizer()
                genie.releaseIdleWorker { true }
            }
        }
    }

    private fun measure(
        engine: TextToSpeech,
        packageName: String,
        folder: File,
        round: Int,
        steps: Int,
        seed: Int
    ): JSONObject {
        val id = "steps-$steps-round-$round"
        val completion = CountDownLatch(1)
        val marker = CountDownLatch(1)
        val outcome = AtomicInteger(TextToSpeech.ERROR)
        val rate = AtomicInteger()
        val firstPcm = AtomicLong()
        val firstPlayback = AtomicLong()
        val done = AtomicLong()
        val markerFrame = AtomicInteger(-1)
        val pcm = ByteArrayOutputStream(524288)
        assertEquals(TextToSpeech.SUCCESS, engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (utteranceId == id && audioFormat == AudioFormat.ENCODING_PCM_16BIT && channelCount == 1) {
                    rate.set(sampleRateInHz)
                }
            }

            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                if (utteranceId == id && audio.isNotEmpty()) {
                    firstPcm.compareAndSet(0, SystemClock.elapsedRealtime())
                    pcm.write(audio)
                }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                if (utteranceId == id) {
                    markerFrame.set(frame)
                    firstPlayback.compareAndSet(0, SystemClock.elapsedRealtime())
                    marker.countDown()
                }
            }

            override fun onDone(utteranceId: String) {
                if (utteranceId == id) {
                    done.set(SystemClock.elapsedRealtime())
                    outcome.set(TextToSpeech.SUCCESS)
                    completion.countDown()
                }
            }

            @Deprecated("Use onError(utteranceId, errorCode)")
            override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String, errorCode: Int) {
                if (utteranceId == id) {
                    outcome.set(errorCode)
                    completion.countDown()
                }
            }
        }))
        val options = Bundle().apply {
            putInt("$packageName.ttsBenchmarkSteps", steps)
            putInt("$packageName.ttsBenchmarkSeed", seed)
        }
        val requested = SystemClock.elapsedRealtime()
        assertEquals(TextToSpeech.SUCCESS, engine.speak(TEXT, TextToSpeech.QUEUE_FLUSH, options, id))
        assertTrue("$id playback timed out", completion.await(45, TimeUnit.SECONDS))
        assertEquals(id, TextToSpeech.SUCCESS, outcome.get())
        assertTrue("$id did not deliver PCM", firstPcm.get() >= requested && pcm.size() > 0)
        assertTrue("$id did not reach the playback onset", marker.await(3, TimeUnit.SECONDS))
        assertTrue("$id sample rate missing", rate.get() > 0)
        val file = "$id.wav"
        if (round > 0) writeWave(File(folder, file), rate.get(), pcm)
        val row = JSONObject()
            .put("file", file)
            .put("round", round)
            .put("steps", steps)
            .put("seed", seed)
            .put("requestMs", requested)
            .put("doneMs", done.get())
            .put("firstPcmMs", firstPcm.get() - requested)
            .put("firstPlaybackMs", firstPlayback.get() - requested)
            .put("markerFrame", markerFrame.get())
            .put("sampleRate", rate.get())
            .put("samples", pcm.size() / 2)
        Log.i("MalloTtsStepBench", row.toString())
        return row
    }

    private fun writeWave(file: File, rate: Int, pcm: ByteArrayOutputStream) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size())
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size())
        file.outputStream().use {
            it.write(header.array())
            pcm.writeTo(it)
        }
    }

    private companion object {
        const val TEXT = "아이스 아메리카노 두 잔과 따뜻한 카페라떼 한 잔을 장바구니에 담았습니다."
    }
}
