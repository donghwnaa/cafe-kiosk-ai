package com.example.cafe_kiosk_ai

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.annotation.MainThread
import java.util.Locale
import java.util.UUID

internal data class TtsConfig(
    val voiceName: String? = null,
    val speechRate: Float = 0.9f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f
)

/** The caller sends one utterance at a time. Platform callbacks are dispatched on the main thread. */
@MainThread
internal class AndroidTtsEngine(
    context: Context,
    private val config: TtsConfig = TtsConfig()
) : TtsEngine {
    private data class Utterance(
        val id: String,
        val text: String,
        val onEvent: (TtsPlaybackEvent) -> Unit
    )

    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale.KOREA
    private var platform: TextToSpeech? = null
    override var isInitializing = true
        private set
    private var initializationError: TtsError? = null
    private var released = false
    private var current: Utterance? = null
    private val initTimeout = Runnable { failInitialization(TtsError.INITIALIZATION, "Initialization timed out") }
    private val playbackTimeout = Runnable {
        val id = current?.id ?: return@Runnable
        stopPlatform()
        complete(id, TtsPlaybackEvent.Error(TtsError.PLAYBACK), "Playback timed out")
    }

    init {
        try {
            platform = TextToSpeech(context.applicationContext) { status ->
                // Initialization can report failure even before the constructor returns.
                handler.post { initialize(status) }
            }
            handler.postDelayed(initTimeout, 12_000L)
        } catch (error: RuntimeException) {
            failInitialization(TtsError.INITIALIZATION, error.toString())
        }
    }

    override fun speak(text: String, onEvent: (TtsPlaybackEvent) -> Unit) {
        if (released || current != null) {
            handler.post { onEvent(TtsPlaybackEvent.Error(TtsError.PLAYBACK)) }
            return
        }
        current = Utterance(UUID.randomUUID().toString(), text, onEvent)
        if (!isInitializing) scheduleCurrent()
    }

    override fun stop() {
        val interrupted = current ?: return
        current = null
        handler.removeCallbacks(playbackTimeout)
        stopPlatform()
        Log.d(TAG, "Stopped utterance ${interrupted.id}")
        handler.post { interrupted.onEvent(TtsPlaybackEvent.Stopped) }
    }

    override fun release() {
        released = true
        stop()
        handler.removeCallbacks(initTimeout)
        shutdownPlatform()
    }

    private fun initialize(status: Int) {
        val engine = platform ?: return
        if (released || !isInitializing) return
        try {
            val engines = engine.engines.map { it.name }
            Log.i(TAG, "Installed TTS engines: $engines, system default: ${engine.defaultEngine}")
            if (status != TextToSpeech.SUCCESS) {
                val reason = if (engines.isEmpty()) TtsError.NO_ENGINE else TtsError.INITIALIZATION
                failInitialization(reason, "Android initialization status: $status")
                return
            }

            val availability = engine.isLanguageAvailable(locale)
            val koreanVoices = engine.voices.orEmpty().filter { it.locale.language == locale.language }
            for (voice in koreanVoices) {
                Log.i(TAG, "Korean TTS voice: ${describeVoice(voice)}")
            }
            val installed = koreanVoices.filter(::isInstalled)
            if (availability < TextToSpeech.LANG_AVAILABLE && installed.isEmpty()) {
                failInitialization(TtsError.KOREAN_UNAVAILABLE, "Korean language status: $availability")
                return
            }
            if (availability >= TextToSpeech.LANG_AVAILABLE) engine.setLanguage(locale)
            val defaultVoice = engine.voice?.takeIf { it.locale.language == locale.language && isInstalled(it) }
            val candidates = (installed + listOfNotNull(defaultVoice)).distinctBy(Voice::getName)
                .sortedWith(compareBy<Voice>(
                    { it.name != config.voiceName },
                    { it.isNetworkConnectionRequired },
                    { it.locale != locale },
                    { it.name != defaultVoice?.name }
                ).thenByDescending { it.quality }.thenBy { it.name })
            val selected = candidates.firstOrNull { engine.setVoice(it) == TextToSpeech.SUCCESS }
            if (selected == null) {
                failInitialization(TtsError.KOREAN_UNAVAILABLE, "No usable Korean voice (language status: $availability)")
                return
            }
            if (selected.isNetworkConnectionRequired) {
                Log.w(TAG, "Selected Korean TTS voice requires a network connection: ${selected.name}")
            }
            Log.i(TAG, "Selected Korean TTS voice: ${describeVoice(selected)}")

            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            if (engine.setAudioAttributes(audio) != TextToSpeech.SUCCESS ||
                engine.setSpeechRate(config.speechRate) != TextToSpeech.SUCCESS ||
                engine.setPitch(config.pitch) != TextToSpeech.SUCCESS ||
                engine.setOnUtteranceProgressListener(progressListener) != TextToSpeech.SUCCESS
            ) {
                failInitialization(TtsError.INITIALIZATION, "Could not configure Korean speech playback")
                return
            }
        } catch (error: RuntimeException) {
            failInitialization(TtsError.INITIALIZATION, error.toString())
            return
        }
        isInitializing = false
        handler.removeCallbacks(initTimeout)
        scheduleCurrent()
    }

    private fun isInstalled(voice: Voice): Boolean =
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()

    private fun describeVoice(voice: Voice): String =
        "${voice.name}, locale=${voice.locale.toLanguageTag()}, quality=${voice.quality}, " +
            "requiresNetwork=${voice.isNetworkConnectionRequired}, installed=${isInstalled(voice)}"

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            handler.post {
                current?.takeIf { it.id == utteranceId }?.let {
                    Log.d(TAG, "Started utterance $utteranceId")
                    it.onEvent(TtsPlaybackEvent.Started)
                }
            }
        }

        override fun onDone(utteranceId: String) {
            handler.post { complete(utteranceId, TtsPlaybackEvent.Completed) }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            handler.post { complete(utteranceId, TtsPlaybackEvent.Stopped) }
        }

        @Deprecated("Use onError(utteranceId, errorCode)")
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) {
            handler.post {
                val reason = if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET)
                    TtsError.KOREAN_UNAVAILABLE else TtsError.PLAYBACK
                complete(utteranceId, TtsPlaybackEvent.Error(reason), "Android playback error: $errorCode")
            }
        }
    }

    private fun scheduleCurrent() {
        val utterance = current ?: return
        // SpeechRecognizer dispatches its destroy request on this same looper.
        handler.post {
            if (current === utterance) start(utterance)
        }
    }

    private fun start(utterance: Utterance) {
        initializationError?.let { reason ->
            complete(utterance.id, TtsPlaybackEvent.Error(reason))
            return
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, config.volume.coerceIn(0f, 1f))
        }
        val status = try {
            platform?.speak(utterance.text, TextToSpeech.QUEUE_FLUSH, params, utterance.id)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not queue Android TTS playback", error)
            TextToSpeech.ERROR
        }
        if (status == TextToSpeech.SUCCESS) {
            // Recover if a service disappears without sending a terminal callback.
            val timeout = maxOf(30_000L, utterance.text.length * 350L)
            handler.postDelayed(playbackTimeout, timeout)
        } else {
            complete(utterance.id, TtsPlaybackEvent.Error(TtsError.PLAYBACK), "Android queue status: $status")
        }
    }

    private fun complete(id: String, event: TtsPlaybackEvent, detail: String? = null) {
        val utterance = current?.takeIf { it.id == id } ?: return
        current = null
        handler.removeCallbacks(playbackTimeout)
        Log.d(TAG, "Finished utterance $id: $event")
        if (detail != null) Log.w(TAG, detail)
        utterance.onEvent(event)
    }

    private fun failInitialization(reason: TtsError, detail: String) {
        isInitializing = false
        initializationError = reason
        handler.removeCallbacks(initTimeout)
        Log.w(TAG, "TTS initialization failed: $reason ($detail)")
        shutdownPlatform()
        scheduleCurrent()
    }

    private fun stopPlatform() {
        runCatching { platform?.stop() }.onFailure { Log.w(TAG, "Could not stop TTS", it) }
    }

    private fun shutdownPlatform() {
        val engine = platform
        platform = null
        runCatching { engine?.shutdown() }.onFailure { Log.w(TAG, "Could not release TTS", it) }
    }

    private companion object {
        const val TAG = "MalloTts"
    }
}
