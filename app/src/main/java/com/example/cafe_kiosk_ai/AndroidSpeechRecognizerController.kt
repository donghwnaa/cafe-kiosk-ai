package com.example.cafe_kiosk_ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread

internal sealed interface VoiceRecognitionEvent {
    data object PreparationStarted : VoiceRecognitionEvent
    data object AwaitingSpeech : VoiceRecognitionEvent
    data object Listening : VoiceRecognitionEvent
    data object Processing : VoiceRecognitionEvent
    data class Result(val text: String) : VoiceRecognitionEvent
    data class Error(val reason: VoiceRecognitionError) : VoiceRecognitionEvent
}

internal fun audioLog(engine: String, session: String?, turn: Long, state: String, event: String, detail: String = "") {
    if (BuildConfig.DEBUG) android.util.Log.d("MalloAudio",
        "$event elapsedMs=${android.os.SystemClock.elapsedRealtime()} session=$session turn=$turn engine=$engine state=$state $detail")
}

internal enum class VoiceRecognitionError(val canRetry: Boolean) {
    NO_SPEECH(true), NO_RESULT(true), PREPARATION(true), MIC_NO_DATA(true), AUDIO(true), BUSY(true), TEMPORARY(true),
    MIC_ZERO_SIGNAL(false), MIC_CLEANUP_TIMEOUT(false), PERMISSION(false), UNAVAILABLE(false), SERVICE(false)
}

/** Each start owns a recognizer and listener so callbacks from a stopped turn stay isolated. */
@MainThread
internal class AndroidSpeechRecognizerController(
    context: Context,
    private val onEvent: (Long, VoiceRecognitionEvent) -> Unit
) : VoiceController {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }
    private var currentTurn: RecognitionTurn? = null
    private var released = false

    override fun startListening(turnId: Long) {
        if (released || currentTurn != null) return
        onEvent(turnId, VoiceRecognitionEvent.PreparationStarted)
        val error = try {
            if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                val turn = RecognitionTurn(turnId, SpeechRecognizer.createSpeechRecognizer(appContext))
                currentTurn = turn
                turn.recognizer.setRecognitionListener(turn)
                turn.recognizer.startListening(intent)
                null
            } else {
                VoiceRecognitionError.UNAVAILABLE
            }
        } catch (_: SecurityException) {
            VoiceRecognitionError.PERMISSION
        } catch (_: RuntimeException) {
            VoiceRecognitionError.SERVICE
        }
        if (error != null) {
            stopListening()
            onEvent(turnId, VoiceRecognitionEvent.Error(error))
        }
    }

    override fun stopListening() {
        currentTurn?.let(::closeTurn)
    }

    override fun release() {
        released = true
        stopListening()
    }

    private fun closeTurn(turn: RecognitionTurn) {
        currentTurn = null
        handler.removeCallbacks(turn.timeout)
        turn.recognizer.destroy()
    }

    private inner class RecognitionTurn(val id: Long, val recognizer: SpeechRecognizer) : RecognitionListener {
        private var ready = false
        val timeout = Runnable { finish(VoiceRecognitionEvent.Error(VoiceRecognitionError.TEMPORARY)) }

        override fun onReadyForSpeech(params: Bundle?) {
            if (currentTurn !== this || ready) return
            ready = true
            handler.postDelayed(timeout, 30_000L)
            report(VoiceRecognitionEvent.Listening)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onEndOfSpeech() {
            if (currentTurn !== this) return
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 15_000L)
            report(VoiceRecognitionEvent.Processing)
        }

        override fun onResults(results: Bundle?) {
            if (currentTurn !== this) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            val event = if (text.isBlank()) {
                VoiceRecognitionEvent.Error(VoiceRecognitionError.NO_RESULT)
            } else {
                VoiceRecognitionEvent.Result(text.trim())
            }
            finish(event)
        }

        override fun onError(error: Int) {
            val reason = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> if (ready) VoiceRecognitionError.NO_RESULT else VoiceRecognitionError.PREPARATION
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (ready) VoiceRecognitionError.NO_SPEECH else VoiceRecognitionError.PREPARATION
                SpeechRecognizer.ERROR_AUDIO -> VoiceRecognitionError.AUDIO
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceRecognitionError.BUSY
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceRecognitionError.PERMISSION
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    VoiceRecognitionError.UNAVAILABLE
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceRecognitionError.TEMPORARY
                else -> VoiceRecognitionError.SERVICE
            }
            finish(VoiceRecognitionEvent.Error(reason))
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun report(event: VoiceRecognitionEvent) {
            if (currentTurn === this) onEvent(id, event)
        }

        private fun finish(event: VoiceRecognitionEvent) {
            if (currentTurn !== this) return
            closeTurn(this)
            onEvent(id, event)
        }
    }
}
