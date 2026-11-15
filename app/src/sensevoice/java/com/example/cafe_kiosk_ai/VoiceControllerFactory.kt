package com.example.cafe_kiosk_ai

import android.content.Context

internal fun createVoiceController(
    context: Context,
    sessionId: String?,
    onEvent: (Long, VoiceRecognitionEvent) -> Unit
): VoiceController = SherpaOfflineSttController(context, sessionId, onEvent = onEvent)

internal const val voiceEngineName = "SenseVoice"
