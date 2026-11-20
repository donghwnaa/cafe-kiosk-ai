package com.example.cafe_kiosk_ai

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal fun createVoiceController(
    context: Context,
    sessionId: String?,
    captureProfile: CaptureProfile? = null,
    onEvent: (Long, VoiceRecognitionEvent) -> Unit
): VoiceController = SherpaOfflineSttController(context, sessionId, profileOverride = captureProfile, onEvent = onEvent)

internal const val voiceEngineName = "SenseVoice"

internal suspend fun prepareVoiceRecognizerFiles(context: Context): Unit = withContext(Dispatchers.IO) {
    try {
        SenseVoiceModelFiles.prepare(context)
        Log.i("MalloStt", "SENSEVOICE_FILES_READY")
    } catch (error: IOException) {
        Log.w("MalloStt", "SenseVoice file preparation failed; will retry when loading the recognizer", error)
    }
}

internal suspend fun preloadVoiceRecognizer(context: Context): Boolean = withContext(Dispatchers.IO) {
    val scope = this
    try {
        val lease = ModelMemoryLease.acquire(context.cacheDir, "STT_PRELOAD") { !scope.isActive }
            ?: return@withContext false
        lease.use {
            if (!scope.isActive) return@withContext false
            ResidentSenseVoiceRecognizer.use(context, OfflineSttConfig().useItn) { _, reused ->
                Log.i("MalloStt", "SENSEVOICE_PRELOAD_READY reused=$reused")
                true
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w("MalloStt", "SENSEVOICE_PRELOAD_FAILED", error)
        false
    } catch (error: LinkageError) {
        Log.w("MalloStt", "SENSEVOICE_PRELOAD_FAILED", error)
        false
    }
}

internal suspend fun releaseIdleVoiceRecognizer() = withContext(Dispatchers.IO) {
    ResidentSenseVoiceRecognizer.release()
}
