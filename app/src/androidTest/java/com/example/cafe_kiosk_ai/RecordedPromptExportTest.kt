package com.example.cafe_kiosk_ai

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in authoring task that uses the same bundled Korean voice as kiosk playback. */
@RunWith(AndroidJUnit4::class)
class RecordedPromptExportTest {
    @Test
    fun exportBundledKoreanWaveFiles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("exportRecordedPrompts") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.cacheDir, "recorded-prompt-export").apply { mkdirs() }
        val initialized = CountDownLatch(1)
        val initStatus = AtomicInteger(TextToSpeech.ERROR)
        lateinit var engine: TextToSpeech
        instrumentation.runOnMainSync {
            engine = TextToSpeech(context, { status ->
                initStatus.set(status)
                initialized.countDown()
            }, context.packageName)
        }
        try {
            assertTrue("TTS engine initialization timed out", initialized.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initStatus.get())
            val config = TtsConfig()
            val locale = Locale.KOREA
            assertTrue(engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE)
            val voice = engine.voices.single { it.name == "ko-KR" && it.locale == locale && !it.isNetworkConnectionRequired }
            assertEquals(TextToSpeech.SUCCESS, engine.setVoice(voice))
            assertEquals(TextToSpeech.SUCCESS, engine.setSpeechRate(config.speechRate))
            assertEquals(TextToSpeech.SUCCESS, engine.setPitch(config.pitch))
            for (prompt in RecordedPrompt.entries) {
                val name = prompt.fileName
                val completed = CountDownLatch(1)
                val outcome = AtomicInteger(TextToSpeech.ERROR)
                val target = File(output, "$name.wav")
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit
                    override fun onDone(utteranceId: String) {
                        outcome.set(TextToSpeech.SUCCESS)
                        completed.countDown()
                    }
                    @Deprecated("Use onError(utteranceId, errorCode)")
                    override fun onError(utteranceId: String) {
                        completed.countDown()
                    }
                    override fun onError(utteranceId: String, errorCode: Int) {
                        outcome.set(errorCode)
                        completed.countDown()
                    }
                })
                assertEquals(TextToSpeech.SUCCESS, engine.synthesizeToFile(prompt.text, Bundle(), target, name))
                assertTrue("Synthesis timed out for $name", completed.await(45, TimeUnit.SECONDS))
                assertEquals(name, TextToSpeech.SUCCESS, outcome.get())
                assertTrue("Empty WAV for $name", target.length() > 44)
            }
        } finally {
            engine.shutdown()
        }
    }
}
