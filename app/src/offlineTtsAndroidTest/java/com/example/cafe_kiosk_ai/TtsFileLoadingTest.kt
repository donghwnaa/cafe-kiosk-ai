package com.example.cafe_kiosk_ai

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.GenerationConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsFileLoadingTest {
    @Test
    fun compareAssetAndFileLoading() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("compareTtsFileLoading") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = BoardGenieLlmEngine(context.cacheDir)
        val rows = JSONArray()
        var reference: String? = null
        try {
            runBlocking {
                engine.preload()
                preloadVoiceRecognizer(context)
            }
            val prepareStarted = SystemClock.elapsedRealtime()
            val files = BundledTtsModelFiles.prepare(context)
            val extractMs = SystemClock.elapsedRealtime() - prepareStarted
            val reused = BundledTtsModelFiles.prepare(context)
            assertEquals(files.path("voice.bin"), reused.path("voice.bin"))
            for ((trial, source) in listOf(null, files, files, null, null, files).withIndex()) {
                val rss = File("/proc/self/status")
                val meminfo = File("/proc/meminfo")
                val initialRss = readKiB(rss, "VmRSS:")
                var peakRss = initialRss
                var minAvailable = readKiB(meminfo, "MemAvailable:")
                val running = AtomicBoolean(true)
                val sampler = thread(name = "tts-file-loading-memory", isDaemon = true) {
                    while (running.get()) {
                        peakRss = maxOf(peakRss, readKiB(rss, "VmRSS:"))
                        minAvailable = minOf(minAvailable, readKiB(meminfo, "MemAvailable:"))
                        SystemClock.sleep(20)
                    }
                }
                val started = SystemClock.elapsedRealtime()
                var model: com.k2fsa.sherpa.onnx.OfflineTts? = null
                try {
                    val active = createBundledTts(context, source).also { model = it }
                    val initMs = SystemClock.elapsedRealtime() - started
                    val initRss = readKiB(rss, "VmRSS:")
                    var firstPcmMs = -1L
                    val config = GenerationConfig(
                        speed = TtsConfig().speechRate,
                        numSteps = 5,
                        extra = mapOf("lang" to "ko", "seed" to "314")
                    )
                    fun collect(samples: FloatArray): Int {
                        if (firstPcmMs < 0 && samples.isNotEmpty()) {
                            firstPcmMs = SystemClock.elapsedRealtime() - started
                        }
                        return 1
                    }
                    val audio = active.generateWithConfigAndCallback(TEXT, config, ::collect)
                    val totalMs = SystemClock.elapsedRealtime() - started
                    peakRss = maxOf(peakRss, readKiB(rss, "VmRSS:"))
                    running.set(false)
                    sampler.join()
                    assertTrue("TTS returned no waveform", audio.samples.isNotEmpty() && firstPcmMs >= 0)
                    val bytes = ByteBuffer.allocate(audio.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asFloatBuffer().put(audio.samples)
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array())
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (reference == null) reference = hash
                    assertEquals("TTS output changed with loading source", reference, hash)
                    val row = JSONObject()
                        .put("trial", trial + 1)
                        .put("source", if (source == null) "asset" else "file")
                        .put("initialRssKiB", initialRss)
                        .put("initRssKiB", initRss)
                        .put("peakRssKiB", peakRss)
                        .put("minAvailableKiB", minAvailable)
                        .put("initMs", initMs)
                        .put("firstPcmMs", firstPcmMs)
                        .put("totalMs", totalMs)
                        .put("samples", audio.samples.size)
                        .put("waveformSha256", hash)
                    rows.put(row)
                    Log.i("MalloTtsFileBench", row.toString())
                } finally {
                    running.set(false)
                    sampler.join()
                    model?.release()
                }
            }
            File(context.cacheDir, "tts-file-loading.json").writeText(JSONObject()
                .put("text", TEXT)
                .put("prepareFilesMs", extractMs)
                .put("trials", rows)
                .toString(2))
        } finally {
            runBlocking {
                releaseIdleVoiceRecognizer()
                engine.releaseIdleWorker { true }
            }
        }
    }

    private fun readKiB(file: File, label: String): Long = file.useLines { lines ->
        lines.first { it.startsWith(label) }.removePrefix(label).trim().substringBefore(' ').toLong()
    }

    private companion object {
        const val TEXT = "아이스 아메리카노 두 잔과 따뜻한 카페라떼 한 잔을 장바구니에 담았습니다."
    }
}
