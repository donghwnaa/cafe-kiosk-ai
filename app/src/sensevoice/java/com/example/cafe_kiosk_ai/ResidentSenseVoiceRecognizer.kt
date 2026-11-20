package com.example.cafe_kiosk_ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.io.IOException

/** The lock covers native initialization, decoding and release, but not microphone capture. */
internal object ResidentSenseVoiceRecognizer {
    private data class Cached(val useItn: Boolean, val recognizer: OfflineRecognizer)

    private var cached: Cached? = null

    @Synchronized
    fun <T> use(
        context: Context,
        useItn: Boolean,
        block: (recognizer: OfflineRecognizer, reused: Boolean) -> T
    ): T {
        val existing = cached?.takeIf { it.useItn == useItn }
        val recognizer = existing?.recognizer ?: run {
            release()
            val files = try {
                SenseVoiceModelFiles.prepare(context)
            } catch (error: IOException) {
                Log.w(TAG, "SenseVoice file preparation failed; using bundled assets", error)
                null
            }
            val started = SystemClock.elapsedRealtime()
            createSenseVoiceRecognizer(context.assets, useItn, files).also {
                cached = Cached(useItn, it)
                val source = if (files == null) "asset" else "file"
                Log.i(TAG, "SENSEVOICE_INIT source=$source elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
        }
        return try {
            block(recognizer, existing != null)
        } catch (error: RuntimeException) {
            release()
            throw error
        }
    }

    @Synchronized
    fun release() {
        val previous = cached ?: return
        cached = null
        previous.recognizer.release()
        Log.i(TAG, "SENSEVOICE_RELEASED")
    }

    private const val TAG = "MalloStt"
}
