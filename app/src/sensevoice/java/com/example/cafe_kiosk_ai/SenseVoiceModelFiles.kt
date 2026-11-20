package com.example.cafe_kiosk_ai

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal const val SENSEVOICE_SAMPLE_RATE = 16_000

internal data class SenseVoiceModelFiles(val model: File, val tokens: File) {
    companion object {
        private const val MANIFEST = "model-files.txt"
        private const val MODEL = "model.int8.onnx"
        private const val TOKENS = "tokens.txt"

        @Synchronized
        fun prepare(context: Context): SenseVoiceModelFiles {
            val assets = context.assets
            val manifest = assets.open("stt/$MANIFEST").bufferedReader().use { it.readText() }
            val metadata = manifest.lineSequence().toList()
            if (metadata.size != 3) throw IOException("Invalid SenseVoice asset manifest")
            val modelSize = metadata[1].toLongOrNull() ?: throw IOException("Invalid SenseVoice model size")
            val tokensSize = metadata[2].toLongOrNull() ?: throw IOException("Invalid SenseVoice tokens size")
            val dir = File(context.noBackupFilesDir, "sensevoice")
            val marker = File(dir, MANIFEST)
            val model = File(dir, MODEL)
            val tokens = File(dir, TOKENS)
            if (marker.isFile && marker.readText() == manifest && model.isFile && tokens.isFile &&
                model.length() == modelSize && tokens.length() == tokensSize
            ) return SenseVoiceModelFiles(model, tokens)

            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create SenseVoice model directory")
            val started = SystemClock.elapsedRealtime()
            // Publish the manifest last so a partial update is always retried.
            copyAsset(assets, model, modelSize)
            copyAsset(assets, tokens, tokensSize)
            copyAsset(assets, marker, manifest.toByteArray(Charsets.UTF_8).size.toLong())
            Log.i("MalloStt", "SENSEVOICE_FILES_PREPARED elapsedMs=${SystemClock.elapsedRealtime() - started}")
            return SenseVoiceModelFiles(model, tokens)
        }

        private fun copyAsset(assets: AssetManager, destination: File, expectedSize: Long) {
            val pending = File(destination.parentFile, destination.name + ".pending")
            try {
                val copied = assets.open("stt/${destination.name}", AssetManager.ACCESS_STREAMING).use { input ->
                    FileOutputStream(pending).use { output ->
                        input.copyTo(output, 128 * 1024).also { output.fd.sync() }
                    }
                }
                if (copied != expectedSize || !pending.renameTo(destination)) {
                    throw IOException("Could not prepare SenseVoice file ${destination.name}")
                }
            } finally {
                pending.delete()
            }
        }
    }
}

internal fun createSenseVoiceRecognizer(
    assets: AssetManager,
    useItn: Boolean,
    files: SenseVoiceModelFiles?
): OfflineRecognizer = OfflineRecognizer(
    assetManager = assets.takeIf { files == null },
    config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SENSEVOICE_SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = files?.model?.absolutePath ?: "stt/model.int8.onnx",
                language = "ko",
                useInverseTextNormalization = useItn
            ),
            tokens = files?.tokens?.absolutePath ?: "stt/tokens.txt",
            numThreads = 2,
            provider = "cpu"
        )
    )
)
