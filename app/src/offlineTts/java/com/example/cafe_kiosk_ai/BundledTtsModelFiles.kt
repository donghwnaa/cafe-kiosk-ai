package com.example.cafe_kiosk_ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

internal class BundledTtsModelFiles(private val directory: File) {
    fun path(name: String): String = File(directory, name).absolutePath

    companion object {
        private const val MANIFEST = "model-files.txt"

        @Synchronized
        fun prepare(context: Context): BundledTtsModelFiles {
            val started = SystemClock.elapsedRealtime()
            val manifest = context.assets.open("tts/$MANIFEST").bufferedReader().use { it.readText() }
            val entries = manifest.lineSequence().drop(1).filter { it.isNotBlank() }.map { row ->
                val fields = row.split(' ')
                if (fields.size != 3 || !fields[0].matches(Regex("[A-Za-z0-9_.-]+")) ||
                    fields[1].toLongOrNull() == null || !fields[2].matches(Regex("[0-9a-f]{64}"))
                ) throw IOException("Invalid TTS model file manifest")
                Triple(fields[0], fields[1].toLong(), fields[2])
            }.toList()
            if (entries.size != 7) throw IOException("Incomplete TTS model file manifest")
            val directory = File(context.noBackupFilesDir, "supertonic-3")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create TTS model directory")
            RandomAccessFile(File(directory, "prepare.lock"), "rw").use { handle ->
                handle.channel.lock().use {
                    val marker = File(directory, MANIFEST)
                    val ready = marker.isFile && marker.readText() == manifest && entries.all { (name, size, _) ->
                        File(directory, name).let { file -> file.isFile && file.length() == size }
                    }
                    if (!ready) {
                        for ((name, size, hash) in entries) {
                            copyAsset(context, File(directory, name), size, hash)
                        }
                        val pending = File(directory, "$MANIFEST.pending")
                        try {
                            FileOutputStream(pending).use { output ->
                                output.write(manifest.toByteArray(Charsets.UTF_8))
                                output.fd.sync()
                            }
                            if (!pending.renameTo(marker)) throw IOException("Could not publish TTS model manifest")
                        } finally {
                            pending.delete()
                        }
                        Log.i("MalloOfflineTts", "OFFLINE_TTS_FILES_PREPARED elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    }
                }
            }
            return BundledTtsModelFiles(directory)
        }

        private fun copyAsset(context: Context, destination: File, expectedSize: Long, expectedHash: String) {
            val pending = File(destination.parentFile, destination.name + ".pending")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                context.assets.open("tts/${destination.name}").use { input ->
                    FileOutputStream(pending).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var count = input.read(buffer)
                        while (count != -1) {
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                            count = input.read(buffer)
                        }
                        output.fd.sync()
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (copied != expectedSize || hash != expectedHash || !pending.renameTo(destination)) {
                    throw IOException("Could not prepare TTS model file ${destination.name}")
                }
            } finally {
                pending.delete()
            }
        }
    }
}

internal fun createBundledTts(context: Context, files: BundledTtsModelFiles?): OfflineTts {
    fun path(name: String): String = files?.path(name) ?: "tts/$name"
    return OfflineTts(
        assetManager = context.assets.takeIf { files == null },
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = path("duration_predictor.int8.onnx"),
                    textEncoder = path("text_encoder.int8.onnx"),
                    vectorEstimator = path("vector_estimator.int8.onnx"),
                    vocoder = path("vocoder.int8.onnx"),
                    ttsJson = path("tts.json"),
                    unicodeIndexer = path("unicode_indexer.bin"),
                    voiceStyle = path("voice.bin")
                ),
                provider = "cpu",
                numThreads = 2
            )
        )
    )
}
