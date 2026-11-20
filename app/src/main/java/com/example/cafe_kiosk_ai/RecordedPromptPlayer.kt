package com.example.cafe_kiosk_ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RawRes
import java.io.IOException

@MainThread
internal class RecordedPromptPlayer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var timeout: Runnable? = null

    fun play(prompt: RecordedPrompt, onEvent: (TtsPlaybackEvent) -> Unit) {
        release()
        val active = try {
            MediaPlayer()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not create prompt player", error)
            onEvent(TtsPlaybackEvent.Error(TtsError.PLAYBACK))
            return
        }
        player = active
        fun finish(event: TtsPlaybackEvent) {
            if (player !== active) return
            release()
            onEvent(event)
        }

        try {
            active.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            val source = context.resources.openRawResourceFd(prompt.resourceId())
                ?: throw IOException("Could not open ${prompt.fileName}")
            source.use { active.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            active.setOnPreparedListener {
                if (player === active) {
                    try {
                        active.start()
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Could not start ${prompt.fileName}", error)
                        finish(TtsPlaybackEvent.Error(TtsError.PLAYBACK))
                        return@setOnPreparedListener
                    }
                    onEvent(TtsPlaybackEvent.Started)
                }
            }
            active.setOnCompletionListener { finish(TtsPlaybackEvent.Completed) }
            active.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error for ${prompt.fileName}: $what/$extra")
                finish(TtsPlaybackEvent.Error(TtsError.PLAYBACK))
                true
            }
            timeout = Runnable { finish(TtsPlaybackEvent.Error(TtsError.PLAYBACK)) }
                .also { handler.postDelayed(it, 15_000L) }
            active.prepareAsync()
        } catch (error: IOException) {
            Log.w(TAG, "Could not read ${prompt.fileName}", error)
            finish(TtsPlaybackEvent.Error(TtsError.PLAYBACK))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not prepare ${prompt.fileName}", error)
            finish(TtsPlaybackEvent.Error(TtsError.PLAYBACK))
        }
    }

    fun release() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val previous = player
        player = null
        previous?.release()
    }

    @RawRes
    private fun RecordedPrompt.resourceId(): Int = when (this) {
        RecordedPrompt.WELCOME -> R.raw.prompt_welcome
        RecordedPrompt.CART_HAS_ITEMS -> R.raw.prompt_cart_has_items
        RecordedPrompt.CART_EMPTY -> R.raw.prompt_cart_empty
        RecordedPrompt.CART_UPDATED -> R.raw.prompt_cart_updated
    }

    private companion object { const val TAG = "MalloRecordedPrompt" }
}
