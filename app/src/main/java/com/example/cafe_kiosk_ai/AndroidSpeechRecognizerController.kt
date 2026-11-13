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
    data object Listening : VoiceRecognitionEvent
    data object Processing : VoiceRecognitionEvent
    data class Result(val text: String) : VoiceRecognitionEvent
    data class Error(val reason: VoiceRecognitionError) : VoiceRecognitionEvent
}

internal enum class VoiceRecognitionError(val canRetry: Boolean) {
    NO_SPEECH(true), TEMPORARY(true), PERMISSION(false), UNAVAILABLE(false), SERVICE(false)
}

/** Each start owns a recognizer and listener so callbacks from a stopped turn stay isolated. */
@MainThread
internal class AndroidSpeechRecognizerController(
    context: Context,
    private val onEvent: (VoiceRecognitionEvent) -> Unit
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

    override fun startListening() {
        if (released || currentTurn != null) return
        val error = try {
            if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                val turn = RecognitionTurn(SpeechRecognizer.createSpeechRecognizer(appContext))
                currentTurn = turn
                turn.recognizer.setRecognitionListener(turn)
                handler.postDelayed(turn.timeout, 30_000L)
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
            onEvent(VoiceRecognitionEvent.Error(error))
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

    private inner class RecognitionTurn(val recognizer: SpeechRecognizer) : RecognitionListener {
        val timeout = Runnable { finish(VoiceRecognitionEvent.Error(VoiceRecognitionError.TEMPORARY)) }

        override fun onReadyForSpeech(params: Bundle?) = report(VoiceRecognitionEvent.Listening)
        override fun onBeginningOfSpeech() = report(VoiceRecognitionEvent.Listening)
        override fun onEndOfSpeech() = report(VoiceRecognitionEvent.Processing)

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            val event = if (text.isEmpty()) {
                VoiceRecognitionEvent.Error(VoiceRecognitionError.NO_SPEECH)
            } else {
                VoiceRecognitionEvent.Result(text)
            }
            finish(event)
        }

        override fun onError(error: Int) {
            val reason = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceRecognitionError.NO_SPEECH
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceRecognitionError.PERMISSION
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    VoiceRecognitionError.UNAVAILABLE
                SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER,
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
            if (currentTurn === this) onEvent(event)
        }

        private fun finish(event: VoiceRecognitionEvent) {
            if (currentTurn !== this) return
            closeTurn(this)
            onEvent(event)
        }
    }
}
