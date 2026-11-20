package com.example.cafe_kiosk_ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordedPromptPlaybackTest {
    @Test
    fun bundledPromptsPlayToCompletion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyRecordedPrompts") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (prompt in RecordedPrompt.entries) {
            val completed = CountDownLatch(1)
            val events = mutableListOf<TtsPlaybackEvent>()
            lateinit var player: RecordedPromptPlayer
            instrumentation.runOnMainSync {
                player = RecordedPromptPlayer(instrumentation.targetContext)
                player.play(prompt) { event ->
                    events.add(event)
                    if (event != TtsPlaybackEvent.Started) completed.countDown()
                }
            }
            try {
                assertTrue("${prompt.name} did not finish", completed.await(20, TimeUnit.SECONDS))
                assertEquals(prompt.name, listOf(TtsPlaybackEvent.Started, TtsPlaybackEvent.Completed), events)
            } finally {
                instrumentation.runOnMainSync { player.release() }
            }
        }
    }
}
