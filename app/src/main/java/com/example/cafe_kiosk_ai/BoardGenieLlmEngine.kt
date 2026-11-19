package com.example.cafe_kiosk_ai

import android.util.Log
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Runs the board-provisioned Genie bundle with the same app UID as the kiosk. */
internal class BoardGenieLlmEngine(private val cacheDir: File) : LlmEngine {
    private val bundle = File("/data/local/tmp/bundle")

    override suspend fun functionCallJson(text: String, context: NluContext): String =
        withContext(Dispatchers.IO) {
            val prompt = File.createTempFile("mallo-order-", ".txt", cacheDir)
            try {
                val format = JsonParser.parseString(File(bundle, "prompt_format.json").readText()).asJsonObject
                // The final newlines are significant to this model. Pass the file byte-for-byte.
                prompt.writeText(format["prompt_prefix"].asString + userTurn(text, context) + format["prompt_suffix"].asString)
                val scope = this
                val lease = ModelMemoryLease.acquire(cacheDir, "GENIE") { !scope.isActive }
                    ?: throw CancellationException("Order cancelled before Genie started")
                val output = lease.use { runGenie(prompt) }
                val start = output.lastIndexOf("[BEGIN]:")
                val end = output.lastIndexOf("[END]")
                check(start >= 0 && end > start + 8) { "Genie returned no answer" }
                val json = output.substring(start + 8, end).trim()
                val function = JsonParser.parseString(json).asJsonObject["function"].asString
                Log.i(TAG, "GENIE_RESULT backend=QnnHtp function=$function")
                json
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "GENIE_ERROR", e)
                throw e
            } finally {
                prompt.delete()
            }
        }

    private fun userTurn(text: String, context: NluContext): String {
        // First-turn training examples take plain text; later turns use the training dialogue format.
        val history = context.history.dropWhile { !it.fromUser }.takeLast(6)
        if (history.isEmpty()) return text
        return buildString {
            append("[이전 대화]\n")
            for (message in history) {
                append(if (message.fromUser) "사용자: " else "키오스크: ")
                appendLine(message.text.take(150))
            }
            append("\n[현재 사용자 발화]\n")
            append(text)
        }
    }

    private suspend fun runGenie(prompt: File): String = coroutineScope {
        val builder = ProcessBuilder(
            File(bundle, "genie-t2t-run").path,
            "-c", "genie_config_npu.json", "--prompt_file", prompt.path
        ).directory(bundle).redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] = "$bundle:/vendor/lib64"
        builder.environment()["ADSP_LIBRARY_PATH"] =
            "$bundle;/usr/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"
        val process = builder.start()
        try {
            val reader = async(Dispatchers.IO) { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
            val exit = withTimeoutOrNull(180_000L) {
                var status: Int? = null
                while (status == null) {
                    try {
                        status = process.exitValue()
                    } catch (_: IllegalThreadStateException) {
                        delay(100)
                    }
                }
                status
            }
            check(exit != null) { "Genie inference timed out" }
            val output = reader.await()
            check(exit == 0) { "Genie exited with $exit: ${output.lineSequence().lastOrNull { it.isNotBlank() }?.take(240)}" }
            output
        } finally {
            process.destroy()
            process.waitFor()
        }
    }

    private companion object { const val TAG = "MalloGenie" }
}
