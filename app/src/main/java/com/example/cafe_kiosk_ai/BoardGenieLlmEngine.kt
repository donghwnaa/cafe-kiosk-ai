package com.example.cafe_kiosk_ai

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Runs the board-provisioned Genie bundle with the same app UID as the kiosk. */
internal class BoardGenieLlmEngine(private val cacheDir: File) : LlmEngine {
    override suspend fun functionCallJson(text: String, context: NluContext): String =
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val prompt = File.createTempFile("mallo-order-", ".txt", cacheDir)
            try {
                val format = JsonParser.parseString(File(bundle, "prompt_format.json").readText()).asJsonObject
                // The final newlines are significant to this model. Pass the file byte-for-byte.
                prompt.writeText(format["prompt_prefix"].asString + userTurn(text, context) + format["prompt_suffix"].asString)
                val scope = this
                val waitStarted = SystemClock.elapsedRealtime()
                val json = execution.withLock {
                    val lease = ModelMemoryLease.acquire(cacheDir, "GENIE") { !scope.isActive }
                        ?: throw CancellationException("Order cancelled before Genie started")
                    lease.use {
                        Log.i(TAG, "GENIE_REQUEST waitMs=${SystemClock.elapsedRealtime() - waitStarted}")
                        val output = if (Build.VERSION.SDK_INT >= 26) resident?.query(prompt) else null
                        output?.trim() ?: runCli(prompt)
                    }
                }
                val function = JsonParser.parseString(json).asJsonObject["function"].asString
                Log.i(TAG, "GENIE_RESULT backend=QnnHtp function=$function elapsedMs=${SystemClock.elapsedRealtime() - started}")
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

    suspend fun preload(onLoading: suspend () -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 26) return@withContext
        val scope = this
        execution.withLock {
            val worker = resident?.takeIf { it.isAvailable } ?: return@withLock
            try {
                val lease = ModelMemoryLease.acquire(cacheDir, "GENIE") { !scope.isActive } ?: return@withLock
                lease.use {
                    onLoading()
                    worker.preload()?.let { Log.i(TAG, "GENIE_PRELOAD_READY initMs=$it") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "GENIE_PRELOAD_FAILED", error)
            }
        }
    }

    suspend fun releaseIdleWorker(shouldRelease: () -> Boolean) = withContext(Dispatchers.IO) {
        execution.withLock {
            if (Build.VERSION.SDK_INT >= 26 && shouldRelease()) resident?.release()
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

    private suspend fun runCli(prompt: File): String = coroutineScope {
        Log.i(TAG, "GENIE_CLI")
        val process = processBuilder(
            File(bundle, "genie-t2t-run"),
            arrayOf("-c", "genie_config_npu.json", "--prompt_file", prompt.path)
        ).redirectErrorStream(true).start()
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
            val start = output.lastIndexOf("[BEGIN]:")
            val end = output.lastIndexOf("[END]")
            check(start >= 0 && end > start + 8) { "Genie returned no answer" }
            output.substring(start + 8, end).trim()
        } finally {
            process.destroy()
            process.waitFor()
        }
    }

    private companion object {
        const val TAG = "MalloGenie"
        val bundle = File("/data/local/tmp/bundle")
        val execution = Mutex()
        val resident by lazy {
            if (Build.VERSION.SDK_INT >= 26) {
                ResidentGenieWorker(
                    File(bundle, "mallo-genie-worker-v3"),
                    File(bundle, "mallo-genie-worker-v2"),
                    ::processBuilder
                )
            } else null
        }

        fun processBuilder(executable: File, args: Array<String>): ProcessBuilder =
            ProcessBuilder(executable.path, *args).directory(bundle).apply {
                environment()["LD_LIBRARY_PATH"] = "$bundle:/vendor/lib64"
                environment()["ADSP_LIBRARY_PATH"] =
                    "$bundle;/usr/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"
            }
    }
}
