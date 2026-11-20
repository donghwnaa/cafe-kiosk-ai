package com.example.cafe_kiosk_ai

import android.util.Log
import androidx.annotation.RequiresApi
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Calls are serialized by BoardGenieLlmEngine and run on Dispatchers.IO. */
@RequiresApi(26)
internal class ResidentGenieWorker(
    private val preferredExecutable: File,
    private val legacyExecutable: File,
    private val processBuilder: (File, Array<String>) -> ProcessBuilder
) {
    private class Worker(val process: Process) {
        val input = process.outputStream.buffered()
        val output = DataInputStream(process.inputStream.buffered())
        var supportsCancellation = false
    }

    private data class QueryReply(val text: String?)

    private var worker: Worker? = null
    private var incompatible = false
    private val executable: File get() = if (preferredExecutable.canExecute()) preferredExecutable else legacyExecutable

    val isAvailable: Boolean
        get() = !incompatible && executable.canExecute()

    suspend fun preload(): Long? = exchange("PRELOAD") { output ->
        val header = output.header() ?: throw EOFException("Genie worker exited during preload")
        val fields = header.split(' ')
        check(fields.size == 2 && fields[0] == "READY") { "Genie worker: $header" }
        fields[1].toLong()
    }

    suspend fun query(prompt: File): String? {
        val cancelFile = File("${prompt.absolutePath}.cancel")
        try {
            val reply = exchange(prompt.absolutePath, cancelFile) { output ->
                val header = output.header() ?: throw EOFException("Genie worker exited during request")
                if (header == "CANCELLED") return@exchange QueryReply(null)
                val fields = header.split(' ')
                check(fields.size == 6 && fields[0] == "OK") { "Genie worker: $header" }
                val size = fields[1].toInt()
                check(size in 1..65_536) { "Invalid Genie response size" }
                val bytes = ByteArray(size)
                output.readFully(bytes)
                Log.i(TAG, "GENIE_WORKER reused=${fields[5] == "1"} initMs=${fields[2]} queryMs=${fields[3]} firstMs=${fields[4]}")
                QueryReply(String(bytes, Charsets.UTF_8))
            } ?: return null
            return reply.text ?: throw CancellationException("Genie query cancelled")
        } finally {
            cancelFile.delete()
        }
    }

    // Null is reserved for setups where the worker could not accept a request.
    private suspend fun <T : Any> exchange(
        command: String,
        cancelFile: File? = null,
        readReply: (DataInputStream) -> T
    ): T? {
        val caller = coroutineContext.job
        // Cancelling the caller must leave the pipe reader alive long enough to drain the reply.
        return withContext(NonCancellable) {
            supervisorScope {
                caller.ensureActive()
                if (!isAvailable) return@supervisorScope null
                val current = worker?.takeIf { it.process.isAlive }
                val active = current ?: connect() ?: return@supervisorScope null
                var keep = false
                try {
                    if (current == null) {
                        val handshake = async(Dispatchers.IO) { active.output.header() }
                        val protocol = withContext(caller) { withTimeoutOrNull(3_000L) { handshake.await() } }
                        if (protocol != "MALLO_GENIE_2" && protocol != "MALLO_GENIE_3") {
                            incompatible = true
                            Log.w(TAG, "GENIE_WORKER_UNAVAILABLE handshake failed")
                            return@supervisorScope null
                        }
                        active.supportsCancellation = protocol == "MALLO_GENIE_3"
                    }
                    caller.ensureActive()
                    val io = async(Dispatchers.IO) {
                        active.input.write((command + "\n").toByteArray(Charsets.UTF_8))
                        active.input.flush()
                        readReply(active.output)
                    }
                    try {
                        val reply = withContext(caller) { withTimeoutOrNull(180_000L) { io.await() } }
                            ?: error("Genie worker inference timed out")
                        keep = true
                        reply
                    } catch (cancelled: CancellationException) {
                        if (cancelFile != null && active.supportsCancellation) {
                            keep = runCatching {
                                cancelFile.createNewFile()
                                withTimeoutOrNull(5_000L) {
                                    io.await()
                                    true
                                } == true
                            }.getOrDefault(false)
                            Log.i(TAG, "GENIE_CANCEL workerRetained=$keep")
                        }
                        throw cancelled
                    }
                } finally {
                    if (!keep) release(force = true)
                }
            }
        }
    }

    private fun connect(): Worker? {
        release()
        return try {
            Worker(processBuilder(executable, arrayOf("genie_config_npu.json"))
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                .start()).also { worker = it }
        } catch (error: IOException) {
            incompatible = true
            Log.w(TAG, "GENIE_WORKER_UNAVAILABLE", error)
            null
        }
    }

    fun release(force: Boolean = false) {
        val active = worker ?: return
        worker = null
        val process = active.process
        try {
            if (!force) runCatching { active.input.close() }
            if (force || !process.waitFor(800L, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            process.waitFor()
        } finally {
            runCatching { active.input.close() }
            runCatching { active.output.close() }
        }
    }

    private fun DataInputStream.header(): String? {
        val result = StringBuilder()
        repeat(128) {
            when (val byte = read()) {
                -1 -> return null
                10 -> return result.toString()
                in 32..126 -> result.append(byte.toChar())
                else -> error("Invalid Genie worker header")
            }
        }
        error("Genie worker header is too long")
    }

    private companion object {
        const val TAG = "MalloGenie"
    }
}
