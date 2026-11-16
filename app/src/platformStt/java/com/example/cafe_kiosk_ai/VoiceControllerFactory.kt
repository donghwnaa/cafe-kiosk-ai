package com.example.cafe_kiosk_ai

import android.content.Context

internal fun createVoiceController(
    context: Context,
    sessionId: String?,
    @Suppress("UNUSED_PARAMETER") captureProfile: CaptureProfile? = null,
    onEvent: (Long, VoiceRecognitionEvent) -> Unit
): VoiceController = AndroidSpeechRecognizerController(context, onEvent)

internal const val voiceEngineName = "AndroidSpeechRecognizer"
