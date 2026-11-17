package com.example.cafe_kiosk_ai.llm

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.cafe_kiosk_ai.AndroidRuntimeEnvironment
import com.example.cafe_kiosk_ai.BuildConfig
import com.example.cafe_kiosk_ai.LlmEngine
import com.example.cafe_kiosk_ai.NluContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException

internal const val GENIE_TAG = "MALLO_LLM"
internal data class GenieInference(val json: String, val inferenceMs: Long)
internal enum class GenieModelState { UNCHECKED, CHECKING, FILE_VERIFIED, LIBRARY_LOADED, READY, FAILED }

internal data class GenieTurn(
    val sessionId: String,
    val running: Boolean = false,
    val inference: GenieInference? = null,
    val validated: Boolean? = null,
    val error: String? = null
)

internal class LlmUnavailableException(
    val kioskMessage: String,
    val code: String = "GENIE_UNAVAILABLE",
    cause: Throwable? = null
) : IllegalStateException(kioskMessage, cause)

internal class GenieLlmEngine(private val context: Context) : LlmEngine {
    private data class Query(val text: String, val context: NluContext, val onStarted: (() -> Unit)?)

    companion object {
        fun canRunOnDevice(): Boolean =
            BuildConfig.MALLO_GENIE_NATIVE && Process.is64Bit() &&
                Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
                AndroidRuntimeEnvironment.hasQualcommPlatform
    }

    // The worker owns the dialog. Only GenieDialog_signal runs concurrently with a query.
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    private var handle = 0L
    private val prompt by lazy { GeniePrompt.load(context.assets) }
    private val modelState = MutableStateFlow(GenieModelState.UNCHECKED)
    val model = modelState.asStateFlow()
    @Volatile
    var initializationError: String? = null
        private set
    private val turnState = MutableStateFlow<GenieTurn?>(null)
    val turn = turnState.asStateFlow()

    override suspend fun functionCallJson(text: String, context: NluContext): String {
        turnState.value = GenieTurn(context.sessionId)
        try {
            val result = runQuery(text, context, releaseAfter = false, onStarted = {
                turnState.update { it?.copy(running = true) }
            })
            turnState.update { it?.copy(running = false, inference = result) }
            if (BuildConfig.DEBUG) result.json.chunked(1000).forEach { Log.d(GENIE_TAG, "OUTPUT=$it") }
            return result.json
        } catch (error: LlmUnavailableException) {
            val detail = error.cause?.message ?: error.kioskMessage
            turnState.update { it?.copy(running = false, error = "${error.code}: $detail") }
            throw error
        }
    }

    fun recordValidation(valid: Boolean) {
        turnState.update { it?.copy(validated = valid) }
    }

    suspend fun diagnosticQuery(text: String, context: NluContext): String =
        runQuery(text, context, releaseAfter = true).json

    suspend fun demoQuery(text: String, context: NluContext, requestId: String): GenieInference =
        runQuery(text, context, releaseAfter = false, requestId = requestId)

    suspend fun retryInitialization() {
        if (model.value != GenieModelState.FAILED) return
        // Keep the order session intact. A successful setup clears the previous initialization error.
        generate(query = null, releaseAfter = false, trace = "")
        turnState.update { if (it?.inference == null) null else it }
    }

    private suspend fun runQuery(
        text: String, context: NluContext, releaseAfter: Boolean, requestId: String? = null,
        onStarted: (() -> Unit)? = null
    ): GenieInference {
        if (text.length > 180 || text.toByteArray().size > 540) {
            throw LlmUnavailableException("주문이 너무 깁니다. 메뉴와 옵션을 나누어 말씀해 주세요.")
        }
        val trace = requestId?.let { " request=$it" }.orEmpty()
        return withTimeoutOrNull(180_000L) {
            requireNotNull(generate(Query(text, context, onStarted), releaseAfter, trace))
        } ?: run {
            Log.e(GENIE_TAG, "INFERENCE_TIMEOUT limitMs=180000$trace")
            throw LlmUnavailableException("주문 분석 시간이 초과되었습니다. 다시 말씀해 주세요.", "INFERENCE_TIMEOUT")
        }
    }

    private suspend fun generate(
        query: Query?, releaseAfter: Boolean, trace: String
    ): GenieInference? =
        suspendCancellableCoroutine { continuation ->
            if (!busy.compareAndSet(false, true)) {
                continuation.resumeWithException(LlmUnavailableException(
                    "앞선 주문 분석이 끝난 뒤 다시 시도해 주세요.", "GENIE_BUSY"
                ))
                return@suspendCancellableCoroutine
            }
            val lock = Any()
            var activeQuery = 0L
            continuation.invokeOnCancellation {
                // Keep vendor calls off the cancelling thread. The lock prevents a late abort
                // from reaching a subsequent query or a released handle.
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    synchronized(lock) {
                        if (activeQuery != 0L) {
                            runCatching { GenieNative.abort(activeQuery) }.onSuccess {
                                Log.i(GENIE_TAG, "INFERENCE_ABORT_REQUEST status=$it")
                            }.onFailure {
                                Log.e(GENIE_TAG, "INFERENCE_ABORT_FAILED ${it.message}")
                            }
                        }
                    }
                }
            }
            try {
                worker.execute {
                    var initialized = false
                    val started = SystemClock.elapsedRealtime()
                    val outcome = try {
                        if (!continuation.isActive) return@execute
                        if (releaseAfter) check(release()) { "Genie could not release the previous dialog" }
                        if (handle == 0L) initialize {
                            if (!continuation.isActive) throw CancellationException()
                        }
                        initialized = true
                        if (!continuation.isActive) return@execute
                        val inference = query?.let {
                            val input = prompt.format(it.text, it.context).encodeToByteArray(throwOnInvalidSequence = true)
                            GenieNative.reset(handle)
                            synchronized(lock) {
                                if (!continuation.isActive) return@execute
                                activeQuery = handle
                            }
                            it.onStarted?.invoke()
                            if (BuildConfig.DEBUG && it.onStarted != null) Log.d(GENIE_TAG, "INPUT=${it.text}")
                            Log.i(GENIE_TAG, "INFERENCE_START$trace")
                            val queryStart = SystemClock.elapsedRealtime()
                            val result = try {
                                GenieNative.query(handle, input).decodeToString(throwOnInvalidSequence = true)
                            } finally {
                                synchronized(lock) { activeQuery = 0L }
                            }
                            val inferenceMs = SystemClock.elapsedRealtime() - queryStart
                            Log.i(GENIE_TAG, "INFERENCE_COMPLETE ms=$inferenceMs$trace")
                            GenieInference(result, inferenceMs)
                        }
                        if (releaseAfter) check(release()) { "GenieDialog_free failed; restart the app" }
                        Result.success(inference)
                    } catch (_: CancellationException) {
                        if (!initialized) modelState.value = GenieModelState.UNCHECKED
                        Log.i(GENIE_TAG, "Request cancelled")
                        return@execute
                    } catch (error: Exception) {
                        Result.failure(recordFailure(error, initialized, trace, started))
                    } catch (error: LinkageError) {
                        Result.failure(recordFailure(error, initialized, trace, started))
                    } finally {
                        try {
                            if (releaseAfter) release()
                        } finally {
                            busy.set(false)
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(outcome)
                }
            } catch (_: RejectedExecutionException) {
                busy.set(false)
                continuation.cancel()
            }
        }

    private fun recordFailure(error: Throwable, initialized: Boolean, trace: String, started: Long): LlmUnavailableException {
        val stage = (error as? GenieStageException)?.stage ?: if (initialized) "INFERENCE" else "GENIE_INIT"
        val code = "${stage}_FAILED"
        Log.e(GENIE_TAG, "$code$trace ms=${SystemClock.elapsedRealtime() - started}", error)
        runCatching { release() }.onFailure { Log.e(GENIE_TAG, "GENIE_RELEASE_FAILED", it) }
        if (!initialized) {
            initializationError = "$code: ${error.message}"
            modelState.value = GenieModelState.FAILED
        }
        val message = if (initialized) "기기에서 주문을 분석하지 못했습니다. 다시 말씀해 주세요."
        else "주문 분석 모델을 시작할 수 없습니다. 상세 오류를 확인한 뒤 초기화를 다시 시도해 주세요."
        return LlmUnavailableException(message, code, error)
    }

    private fun initialize(checkActive: () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        Log.i(GENIE_TAG, "GENIE_INIT_STARTED")
        modelState.value = GenieModelState.CHECKING
        val installed = LlmModelInstaller.prepare(context, checkActive) {
            modelState.value = GenieModelState.FILE_VERIFIED
        }
        genieStage("CONFIG_VALIDATION_PROMPT") { prompt }
        genieStage("NATIVE_LIBRARY_LOAD") {
            check(canRunOnDevice()) { "Genie requires a Qualcomm build on physical ARM64 Android" }
            System.loadLibrary("mallo_genie")
        }
        checkActive()
        try {
            handle = GenieNative.open(installed.configuration.encodeToByteArray(), installed.dspDirectory.absolutePath.encodeToByteArray()) {
                modelState.value = GenieModelState.LIBRARY_LOADED
                Log.i(GENIE_TAG, "GENIE_INIT START")
            }
            check(handle != 0L) { "Genie returned an empty dialog handle" }
        } catch (error: LinkageError) {
            throw GenieStageException("NATIVE_LIBRARY_LOAD", error)
        } catch (error: Exception) {
            val stage = if (modelState.value == GenieModelState.LIBRARY_LOADED) "GENIE_INIT" else "NATIVE_LIBRARY_LOAD"
            throw GenieStageException(stage, error)
        }
        Log.i(GENIE_TAG, "ENGINE=GENIE GENIE_INIT_SUCCESS ms=${SystemClock.elapsedRealtime() - started}")
        initializationError = null
        modelState.value = GenieModelState.READY
    }

    fun close() {
        worker.execute { release() }
        worker.shutdown()
    }

    private fun release(): Boolean {
        val dialog = handle
        handle = 0L
        if (dialog != 0L) modelState.value = GenieModelState.FILE_VERIFIED
        return dialog == 0L || GenieNative.close(dialog)
    }
}

internal object GenieNative {
    external fun open(config: ByteArray, dspDir: ByteArray, onLibraryLoaded: Runnable): Long
    external fun reset(handle: Long)
    external fun query(handle: Long, input: ByteArray): ByteArray
    external fun abort(handle: Long): Int
    external fun close(handle: Long): Boolean
}
