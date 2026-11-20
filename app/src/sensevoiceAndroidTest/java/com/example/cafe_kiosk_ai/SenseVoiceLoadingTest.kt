package com.example.cafe_kiosk_ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SenseVoiceLoadingTest {
    @Test
    fun fileAndAssetDecodeTheSameRecording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = loadSamples()
        val engine = BoardGenieLlmEngine(context.cacheDir)
        val files = SenseVoiceModelFiles.prepare(context)
        assertEquals(files, SenseVoiceModelFiles.prepare(context))
        try {
            if (InstrumentationRegistry.getArguments().getString("withGenie") == "true") {
                runBlocking { engine.preload() }
            }
            var baseline: String? = null
            // Alternate order to reduce page-cache and thermal bias.
            listOf(null, files, files, null, null, files).forEachIndexed { index, source ->
                val result = measure(context, source, index + 1, samples)
                assertTrue("The recording must produce nonblank recognition", result.isNotBlank())
                if (baseline == null) baseline = result
                assertEquals(baseline, result)
            }
        } finally {
            runBlocking { engine.releaseIdleWorker { true } }
        }
    }

    @Test
    fun preloadedRecognizerHandlesTwoStreamsAndReloadsAfterRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = loadSamples()
        val engine = BoardGenieLlmEngine(context.cacheDir)
        try {
            runBlocking { preloadVoiceRecognizer(context) }
            if (InstrumentationRegistry.getArguments().getString("withGenie") == "true") {
                runBlocking { engine.preload() }
            }
            var previous: OfflineRecognizer? = null
            val transcripts = (1..2).map { turn ->
                ResidentSenseVoiceRecognizer.use(context, useItn = true) { recognizer, reused ->
                    assertTrue(reused)
                    previous?.let { assertSame(it, recognizer) }
                    previous = recognizer
                    val started = SystemClock.elapsedRealtime()
                    transcribe(recognizer, samples).also {
                        Log.i("MalloSttBenchmark", "residentTurn=$turn decodeMs=${SystemClock.elapsedRealtime() - started}")
                    }
                }
            }
            assertTrue(transcripts.first().isNotBlank())
            assertEquals(transcripts[0], transcripts[1])
            runBlocking { releaseIdleVoiceRecognizer() }
            ResidentSenseVoiceRecognizer.use(context, useItn = true) { recognizer, reused ->
                assertFalse(reused)
                assertEquals(transcripts.first(), transcribe(recognizer, samples))
            }
        } finally {
            runBlocking {
                releaseIdleVoiceRecognizer()
                engine.releaseIdleWorker { true }
            }
        }
    }

    private fun loadSamples(): FloatArray {
        val fixture = InstrumentationRegistry.getArguments().getString("senseVoiceWav")
        assumeTrue("Pass -e senseVoiceWav with a mono speech WAV to compare loaders", !fixture.isNullOrBlank())
        val wave = WaveReader.readWave(requireNotNull(fixture))
        assertTrue("The recording must contain PCM samples", wave.samples.isNotEmpty())
        if (wave.sampleRate == SENSEVOICE_SAMPLE_RATE) return wave.samples
        val pcm = ShortArray(wave.samples.size) { i ->
            (wave.samples[i] * 32768).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        val resampled = FloatArray(pcm.size)
        val count = PcmResampler(wave.sampleRate).convert(pcm, pcm.size, resampled)
        return resampled.copyOf(count)
    }

    private fun transcribe(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SENSEVOICE_SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private fun measure(
        context: Context,
        files: SenseVoiceModelFiles?,
        trial: Int,
        samples: FloatArray
    ): String {
        val rss = File("/proc/self/status")
        val memory = File("/proc/meminfo")
        val initialRss = readKiB(rss, "VmRSS:")
        val initialAvailable = readKiB(memory, "MemAvailable:")
        var peakRss = initialRss
        var minAvailable = initialAvailable
        val running = AtomicBoolean(true)
        val sampler = thread(name = "sensevoice-memory", isDaemon = true) {
            while (running.get()) {
                peakRss = maxOf(peakRss, readKiB(rss, "VmRSS:"))
                minAvailable = minOf(minAvailable, readKiB(memory, "MemAvailable:"))
                SystemClock.sleep(25)
            }
        }
        val source = if (files == null) "asset" else "file"
        try {
            val started = SystemClock.elapsedRealtime()
            val recognizer = createSenseVoiceRecognizer(context.assets, useItn = true, files = files)
            val init = SystemClock.elapsedRealtime() - started
            val result = try {
                transcribe(recognizer, samples)
            } finally {
                recognizer.release()
            }
            val total = SystemClock.elapsedRealtime() - started
            running.set(false)
            sampler.join()
            Log.i("MalloSttBenchmark", "trial=$trial source=$source initMs=$init totalMs=$total " +
                "initialRssKiB=$initialRss peakRssKiB=$peakRss " +
                "initialAvailableKiB=$initialAvailable minAvailableKiB=$minAvailable")
            return result
        } finally {
            running.set(false)
            sampler.join()
        }
    }

    private fun readKiB(file: File, label: String): Long = file.useLines { lines ->
        lines.first { it.startsWith(label) }.removePrefix(label).trim().substringBefore(' ').toLong()
    }
}
