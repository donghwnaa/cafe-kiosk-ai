package com.example.cafe_kiosk_ai

import android.app.ActivityManager
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class KioskSessionViewModel(application: Application) : AndroidViewModel(application) {
    data class SpokenResponse(
        val text: String,
        val forPayment: Boolean,
        val recording: RecordedPrompt? = RecordedPrompt.forText(text)
    )

    private val spokenResponses = ArrayDeque<SpokenResponse>()
    private var captureSpokenResponses = false
    private val genie = BoardGenieLlmEngine(application.cacheDir)
    val conversation = ConversationManager(
        nlu = LlmNluEngine(genie),
        responseOutput = AiResponseOutput(::enqueueResponse)
    )
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputJob: Job? = null
    val isProcessingInput: Boolean get() = inputJob != null
    private var paymentJob: Job? = null
    private val revision = MutableStateFlow(0)
    val updates = revision.asStateFlow()
    private var isPreparingModels = true
    private var isVoiceCaptureReady = false
    val canStartVoiceInput: Boolean get() = isVoiceCaptureReady || !isPreparingModels

    private val modelPreload = sessionScope.launch {
        try {
            prepareVoiceRecognizerFiles(application)
            genie.preload {
                // Open capture once Genie owns the lease. Decoding waits without loading a second model.
                withContext(Dispatchers.Main.immediate) { enableVoiceCapture() }
            }
            enableVoiceCapture()
            preloadVoiceRecognizer(application)
        } finally {
            isPreparingModels = false
            publish()
        }
    }

    private fun enableVoiceCapture() {
        if (isVoiceCaptureReady) return
        isVoiceCaptureReady = true
        publish()
    }

    init {
        sessionScope.launch {
            modelPreload.join()
            while (isActive) {
                delay(2_000L)
                releaseIdleModelsIfNeeded()
            }
        }
    }

    val hasSpokenResponses: Boolean get() = spokenResponses.isNotEmpty()

    fun captureSpokenResponses(enabled: Boolean) {
        captureSpokenResponses = enabled
        if (!enabled) spokenResponses.clear()
    }

    fun takeSpokenResponse(forPayment: Boolean): SpokenResponse? {
        // A response belongs to the screen it was generated for.
        spokenResponses.removeAll { it.forPayment != forPayment }
        val next = spokenResponses.firstOrNull() ?: return null
        if (isPreparingModels && next.recording == null) return null
        return spokenResponses.removeFirst()
    }

    fun retryWithTts(response: SpokenResponse) {
        if (captureSpokenResponses) spokenResponses.addFirst(response.copy(recording = null))
    }

    private fun enqueueResponse(message: ChatMessage) {
        if (captureSpokenResponses) {
            spokenResponses.addLast(SpokenResponse(message.text, conversation.stage == OrderStage.PAYMENT))
        }
    }

    fun startSession() {
        resetSession()
        conversation.startNewSession()
        publish()
    }

    fun goHome() {
        resetSession()
        publish()
    }

    fun trimIdleModels() {
        sessionScope.launch { releaseIdleModelsIfNeeded() }
    }

    private suspend fun releaseIdleModelsIfNeeded() = withContext(Dispatchers.IO) {
        val memory = memoryInfo() ?: return@withContext
        if (!memory.lowMemory && memory.availMem >= IDLE_MODEL_AVAILABLE_BYTES) return@withContext
        releaseIdleVoiceRecognizer()
        // The recognizer release can restore enough headroom to keep Genie.
        genie.releaseIdleWorker { (memoryInfo()?.availMem ?: Long.MAX_VALUE) < IDLE_MODEL_AVAILABLE_BYTES }
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo? {
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java)
            ?: return null
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    }

    fun submit(text: String): Boolean {
        if (text.isBlank() || isProcessingInput || conversation.stage in setOf(OrderStage.HOME, OrderStage.PAYMENT)) return false
        val job = sessionScope.launch(start = CoroutineStart.LAZY) {
            try {
                modelPreload.join()
                conversation.handleUserInput(text)
            } finally {
                if (inputJob === coroutineContext[Job]) {
                    inputJob = null
                    publish()
                }
            }
        }
        inputJob = job
        publish()
        job.start()
        return true
    }

    fun returnFromPayment() {
        conversation.returnFromPayment()
        publish()
    }

    fun runPaymentTest(success: Boolean) {
        if (!conversation.beginPaymentEvent()) return
        publish()
        paymentJob = sessionScope.launch {
            delay(350)
            conversation.paymentEvent(success)
            publish()
        }
    }

    private fun resetSession() {
        spokenResponses.clear()
        val previousInput = inputJob
        inputJob = null
        previousInput?.cancel()
        paymentJob?.cancel()
        paymentJob = null
        conversation.resetSession()
    }

    private fun publish() { revision.value += 1 }

    override fun onCleared() {
        sessionScope.cancel()
        super.onCleared()
    }
}
