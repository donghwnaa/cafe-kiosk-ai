package com.example.cafe_kiosk_ai

import android.content.Context

internal fun createVoiceController(
    context: Context,
    sessionId: String?,
    onEvent: (Long, VoiceRecognitionEvent) -> Unit
): VoiceController = AndroidSpeechRecognizerController(context, onEvent)

internal const val voiceEngineName = "AndroidSpeechRecognizer"
