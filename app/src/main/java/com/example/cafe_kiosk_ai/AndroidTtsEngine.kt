package com.example.cafe_kiosk_ai

import android.content.Context
import android.content.Intent
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

internal const val OFFLINE_KOREAN_TTS = "com.k2fsa.sherpa.onnx.tts.engine"
private const val GOOGLE_TTS = "com.google.android.tts"

internal fun openKoreanVoiceSettings(context: Context) {
    val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(GOOGLE_TTS)
    val settings = Intent("com.android.settings.TTS_SETTINGS")
    for (intent in listOf(install, settings, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    Log.w("MalloTts", "TTS_VOICE_SETTINGS_UNAVAILABLE")
}

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
    private val appContext = context.applicationContext
    private val locale = Locale.KOREA
    private var platform: TextToSpeech? = null
    override var isInitializing = true
        private set
    private var initializationError: TtsError? = null
    private var released = false
    private var current: Utterance? = null
    private var engineCandidates = emptyList<String?>()
    private var candidateIndex = 0
    private var generation = 0
    private var selectedEngine: String? = null
    private var retryAfterPlayback = false
    private var playbackStarted = false
    private val initTimeout = Runnable { failInitialization(TtsError.INITIALIZATION, "Initialization timed out") }
    private val playbackTimeout = Runnable {
        val id = current?.id ?: return@Runnable
        stopPlatform()
        complete(id, TtsPlaybackEvent.Error(TtsError.PLAYBACK), "Playback timed out")
    }

    init {
        reload()
    }

    fun refreshIfUnavailable() {
        if (!isInitializing && initializationError != null) reload()
    }

    fun reload() {
        if (released || current != null) return
        val serviceIntent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val installed = appContext.packageManager.queryIntentServices(serviceIntent, 0)
            .map { it.serviceInfo.packageName }.toSet()
        engineCandidates = buildList {
            if (appContext.packageName in installed) add(appContext.packageName)
            if (GOOGLE_TTS in installed) add(GOOGLE_TTS)
            add(null) // System default, including manufacturer engines.
            if (OFFLINE_KOREAN_TTS in installed) add(OFFLINE_KOREAN_TTS)

        }
        Log.i(TAG, "TTS_ENGINES installed=$installed candidates=$engineCandidates")
        candidateIndex = 0
        initializationError = null
        bindCandidate()
    }

    private fun bindCandidate() {
        handler.removeCallbacks(initTimeout)
        shutdownPlatform()
        isInitializing = true
        val token = ++generation
        val requested = engineCandidates[candidateIndex]
        Log.i(TAG, "TTS_ENGINE_BIND engine=${requested ?: "system-default"}")
        try {
            platform = TextToSpeech(appContext, { status ->
                // Initialization can report failure even before the constructor returns.
                handler.post { if (generation == token) initialize(status) }
            }, requested)
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
        retryAfterPlayback = false
        playbackStarted = false
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
        generation++
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
            if (engineCandidates[candidateIndex] == null && engine.defaultEngine == GOOGLE_TTS &&
                engineCandidates.first() == GOOGLE_TTS) {
                failInitialization(initializationError ?: TtsError.KOREAN_UNAVAILABLE, "Google is the system default")
                return
            }

            val availability = engine.isLanguageAvailable(locale)
            Log.i(TAG, "TTS_KOREAN_AVAILABILITY engine=${engineCandidates[candidateIndex] ?: engine.defaultEngine} status=$availability")
            val koreanVoices = engine.voices.orEmpty().filter { it.locale.language == locale.language }
            for (voice in koreanVoices) {
                Log.i(TAG, "Korean TTS voice: ${describeVoice(voice)}")
            }
            val installed = koreanVoices.filter(::isLocalKorean)
            if (availability < TextToSpeech.LANG_AVAILABLE && installed.isEmpty()) {
                failInitialization(TtsError.KOREAN_UNAVAILABLE, "Korean language status: $availability")
                return
            }
            if (availability >= TextToSpeech.LANG_AVAILABLE) engine.setLanguage(locale)
            val defaultVoice = engine.voice?.takeIf(::isLocalKorean)
            val candidates = (installed + listOfNotNull(defaultVoice)).distinctBy(Voice::getName)
                .sortedWith(compareBy<Voice>(
                    { it.name != config.voiceName },
                    { it.locale != locale },
                    { it.name != defaultVoice?.name }
                ).thenByDescending { it.quality }.thenBy { it.name })
            val selected = candidates.firstOrNull { engine.setVoice(it) == TextToSpeech.SUCCESS }
            if (selected == null) {
                failInitialization(TtsError.KOREAN_UNAVAILABLE, "No usable Korean voice (language status: $availability)")
                return
            }
            selectedEngine = engineCandidates[candidateIndex] ?: engine.defaultEngine
            Log.i(TAG, "TTS_KOREAN_VOICE_SELECTED engine=$selectedEngine ${describeVoice(selected)}")

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
        initializationError = null
        handler.removeCallbacks(initTimeout)
        scheduleCurrent()
    }

    private fun isInstalled(voice: Voice): Boolean =
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()

    private fun isLocalKorean(voice: Voice): Boolean =
        voice.locale.language == locale.language && isInstalled(voice) && !voice.isNetworkConnectionRequired

    private fun describeVoice(voice: Voice): String =
        "${voice.name}, locale=${voice.locale.toLanguageTag()}, quality=${voice.quality}, " +
            "requiresNetwork=${voice.isNetworkConnectionRequired}, installed=${isInstalled(voice)}"

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            handler.post {
                current?.takeIf { it.id == utteranceId }?.let {
                    playbackStarted = true
                    Log.i(TAG, "TTS_PLAYBACK_STARTED engine=$selectedEngine utterance=$utteranceId")
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
                if (current?.id != utteranceId) return@post
                Log.w(TAG, "TTS_PLAYBACK_ERROR engine=$selectedEngine code=$errorCode utterance=$utteranceId")
                val reason = if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET)
                    TtsError.KOREAN_UNAVAILABLE else TtsError.PLAYBACK
                // Retry the whole prompt only when synthesis failed before playback because voice data was missing.
                if (reason == TtsError.KOREAN_UNAVAILABLE && !playbackStarted &&
                    !retryAfterPlayback && candidateIndex + 1 < engineCandidates.size) {
                    retryAfterPlayback = true
                    handler.removeCallbacks(playbackTimeout)
                    current = current?.copy(id = UUID.randomUUID().toString())
                    failInitialization(reason, "Voice data disappeared during synthesis")
                    return@post
                }
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
            Log.i(TAG, "TTS_SPEAK_REQUESTED engine=$selectedEngine utterance=${utterance.id}")
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
        Log.i(TAG, "TTS_PLAYBACK_TERMINAL engine=$selectedEngine utterance=$id event=$event")
        if (detail != null) Log.w(TAG, detail)
        utterance.onEvent(event)
    }

    private fun failInitialization(reason: TtsError, detail: String) {
        initializationError = reason
        handler.removeCallbacks(initTimeout)
        Log.w(TAG, "TTS initialization failed: $reason ($detail)")
        if (candidateIndex + 1 < engineCandidates.size) {
            candidateIndex++
            bindCandidate()
            return
        }
        isInitializing = false
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
