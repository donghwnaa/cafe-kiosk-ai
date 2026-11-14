package com.example.cafe_kiosk_ai

import android.content.Context

internal fun createVoiceController(
    context: Context,
    onEvent: (VoiceRecognitionEvent) -> Unit
): VoiceController = SherpaOfflineSttController(context, onEvent = onEvent)
